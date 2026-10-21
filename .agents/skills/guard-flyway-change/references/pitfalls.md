# Flyway and MySQL pitfalls

- Never “fix” a successful migration in place. A checksum mismatch is evidence to investigate code/database drift, not permission to edit history or run `repair` blindly.
- A failed, unapplied migration and a successful historical migration are different cases. Verify Flyway history before deciding; preserve successful files.
- Trigger compatibility tables can delete or overwrite facts during test cleanup. Scope cleanup to the test’s unique keys and protect shared reference state.
- CI tests sharing one MySQL service cannot assume an empty database, global queue head, or global counts. Query by the test’s unique business key.
- Local integration uses the dedicated `feedback_platform_test` database and the repository wrapper; do not point tests at the business database or install Docker to bypass the intended environment.
- Offset timestamps stored in timezone-free columns require an explicit business-time-zone conversion. Backfills must reject impossible future dates and retain parsing provenance.
- Table counts, Flyway versions, governance catalog counts, and active-rule counts are different metrics. State the exact definition and evidence date.
- SQL migrations, schema and operational SQL must be normal Git text. Reject files beginning with the Git LFS pointer header.
