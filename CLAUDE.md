# Servora agent instructions

The governing instruction for any coding agent working in this repository is [docs/agent/master-implementation-prompt.md](docs/agent/master-implementation-prompt.md). Read it before making changes.

Key rules, in short:

- Work one milestone at a time and stop at the milestone boundary. Current status is in [docs/milestones.md](docs/milestones.md).
- Tenant isolation is non-negotiable. Follow the rules in [CONTRIBUTING.md](CONTRIBUTING.md) and add a cross-tenant negative test for every new resource family.
- Use the versions in [docs/architecture/version-matrix.md](docs/architecture/version-matrix.md). Verify against official documentation before changing one.
- Record significant decisions as ADRs in [docs/architecture/adr](docs/architecture/adr/README.md).
- Never claim a test passed unless it was run and passed.
- Never commit secrets. Only `.env.example` with placeholders is tracked.
