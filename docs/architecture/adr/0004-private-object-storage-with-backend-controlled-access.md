# ADR-004: Private object storage with backend-controlled access

- **Status:** Accepted
- **Date:** 2026-11-16

## Context

Uploaded knowledge documents are private tenant data and must not be reachable at predictable public URLs.

## Decision

Store documents in private S3-compatible object storage behind a storage abstraction in the core API. Object keys are generated, never derived from the user's filename. All access goes through authenticated backend operations. Local development uses an S3-compatible emulator so the code path matches deployment.

## Consequences

- The emulator image is not chosen yet: `minio/minio` is no longer published on Docker Hub as of 2026-11-16. M2 selects an S3-compatible emulator and records it in the version matrix.
- Deleting a document requires coordinating storage and database state, handled through the outbox in ADR-010.

## Revisit when

Revisit if direct browser uploads with pre-signed URLs become necessary for large files.
