# Backup and restore

## What is backed up

| Data | Where | Backed up |
|---|---|---|
| Accounts, workspaces, configuration, conversations, tickets, chunks and embeddings, audit and usage | PostgreSQL | Yes: `pg_dump` in custom format, including Row-Level Security policies and grants |
| Uploaded document files | Object storage volume | Yes: an archive of the volume |
| Rate-limit counters, the worker's queue | Redis | No: they are rebuilt. A document queued at the moment of the backup may need reprocessing |
| Database roles and their passwords | PostgreSQL cluster | No: created from `.env` by `infrastructure/database/init` on a new volume. Keep `.env` safe separately |

Backups contain customer data and password hashes. Store them encrypted, with restricted access, and delete them according to your retention policy.

## Back up

```bash
PROJECT=servora infrastructure/backup/backup.sh /path/to/backups
```

`COMPOSE` overrides the compose command (default: the production profile). The script writes `servora-<UTC timestamp>/` with `database.dump`, `storage.tar.gz` and `SHA256SUMS`, while the stack keeps running.

## Restore

Into a stack whose volumes are new and empty, for example after `docker compose ... down -v` or on a new machine with the same `.env`:

```bash
PROJECT=servora infrastructure/backup/restore.sh /path/to/backups/servora-20261117T150059Z
```

The script verifies the checksums, starts PostgreSQL alone (creating the roles), restores the database in one transaction, restores the storage volume, and starts the stack. It refuses to restore into a database that already has Servora tables, or a storage volume that is not empty.

## Drill, 2026-11-17

Performed on the production profile (project `servora-prod`) with the data left by a browser-test run: two workspaces, four users, two indexed documents, three conversations, a ticket and an internal note.

1. Recorded the state: row counts of the main tables, the number of RLS policies and runtime-role grants, the schema version, and a checksum over every stored document file.
2. Ran `backup.sh`: 173 KB database dump, 1 KB storage archive.
3. Destroyed the stack and its volumes with `down -v`; no `servora-prod` volume remained.
4. Ran `restore.sh`. Backup, destruction and restore took 50 seconds in total.
5. Compared the state:

| Check | Before | After |
|---|---|---|
| Users, workspaces, documents, chunks | 4, 2, 2, 4 | 4, 2, 2, 4 |
| Conversations, messages, tickets, notes | 3, 10, 1, 1 | 3, 10, 1, 1 |
| Audit events, usage events | 12, 8 | 12, 8 |
| RLS policies, runtime-role grants | 70, 77 | 70, 77 |
| Schema version | 8 | 8 (migrations: "up to date") |
| Stored files, checksum over contents | 4, `ca30e1ea12c30b61` | 4, `ca30e1ea12c30b61` |

6. Used the restored system: signed in as a restored owner; reprocessed a restored document, which the worker downloaded from the restored storage and indexed again; asked the restored TechStore widget about returns and got the answer from its document, with the citation.

**Observation:** the storage volume reported 48 KB on disk after the restore against 60 KB before, with identical file contents. The archive tool in the restore script does not keep extended file attributes; the storage service and the worker read the restored files without problems, but this is noted in case a future storage version relies on them.

**Not exercised:** restoring onto a different machine; restoring from a backup taken under write load; point-in-time recovery, which this approach does not provide (the recovery point is the time of the last backup).
