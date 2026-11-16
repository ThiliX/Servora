# ADR-004: Private object storage with backend-controlled access

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

Uploaded knowledge documents are private tenant data and must not be reachable at predictable public URLs.

## Decision

Store documents in private S3-compatible object storage behind a storage abstraction in the core API. Object keys are generated, never derived from the user's filename. All access goes through authenticated backend operations. Local development uses an S3-compatible emulator so the code path matches deployment.

## Consequences

- Local development uses `versity/versitygw` (an S3 gateway over a plain directory), chosen in M2 because `minio/minio` is no longer published on Docker Hub. It needs only two environment variables and one volume. It was exercised end to end with the AWS SDK v2 client (core API) and boto3 (worker), both using path-style addressing.
- Deleting a document requires coordinating storage and database state, handled through the outbox in ADR-010.

## Revisit when

Revisit if direct browser uploads with pre-signed URLs become necessary for large files.
