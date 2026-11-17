"""Internal authentication.

The core API calls this service with a short-lived token signed with a key the two services
share. The tenant is read from that token and from nowhere else, so a request body cannot ask for
another tenant's knowledge.
"""

import base64
import binascii
from uuid import UUID

import jwt

AUDIENCE = "servora-ai-service"
ISSUER = "servora-core-api"


def verify_token(token: str, secret_base64: str) -> UUID:
    """Returns the tenant id from a valid token, or raises ValueError."""
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
        return UUID(claims["tenant_id"])
    except (jwt.PyJWTError, KeyError, ValueError, TypeError) as error:
        raise ValueError("invalid token") from error
