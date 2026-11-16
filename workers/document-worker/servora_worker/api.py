"""Internal enqueue endpoint (ADR-010).

Reachable only on the internal network. The core API calls it with a short-lived signed token; the
tenant and document are read from that token and from nowhere else, so a caller cannot ask for
another tenant's document by changing a request body.
"""

import base64
import binascii
from collections.abc import Callable
from typing import Annotated
from uuid import UUID

import jwt
from fastapi import Depends, FastAPI, Header, HTTPException

from .config import Settings, get_settings

AUDIENCE = "servora-document-worker"
ISSUER = "servora-core-api"

Enqueuer = Callable[[UUID, UUID], None]


def verify_token(token: str, secret_base64: str) -> tuple[UUID, UUID]:
    """Returns (tenant_id, document_id) from a valid token, or raises ValueError."""
    try:
        key = base64.b64decode(secret_base64, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError("invalid signing key") from error
    try:
        claims = jwt.decode(
            token,
            key,
            algorithms=["HS256"],
            audience=AUDIENCE,
            issuer=ISSUER,
            options={"require": ["exp", "iat", "aud", "iss"]},
            leeway=5,
        )
        return UUID(claims["tenant_id"]), UUID(claims["document_id"])
    except (jwt.PyJWTError, KeyError, ValueError, TypeError) as error:
        raise ValueError("invalid token") from error


def get_enqueuer() -> Enqueuer:
    # Imported here so the API module can be loaded, and tested, without a broker.
    from .tasks import enqueue_ingestion

    return enqueue_ingestion


# No interactive docs: this is an internal service with a single endpoint.
app = FastAPI(title="Servora worker API", docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/internal/v1/ingestion-jobs", status_code=202)
def enqueue_ingestion_job(
    settings: Annotated[Settings, Depends(get_settings)],
    enqueue: Annotated[Enqueuer, Depends(get_enqueuer)],
    authorization: Annotated[str | None, Header()] = None,
) -> dict[str, str]:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="unauthenticated")
    try:
        tenant_id, document_id = verify_token(
            authorization.removeprefix("Bearer "), settings.internal_token_secret.get_secret_value()
        )
    except ValueError:
        raise HTTPException(status_code=401, detail="unauthenticated") from None
    try:
        enqueue(tenant_id, document_id)
    except Exception:
        # The broker is unavailable. The core API's outbox retries.
        raise HTTPException(status_code=503, detail="queue unavailable") from None
    return {"status": "accepted"}
