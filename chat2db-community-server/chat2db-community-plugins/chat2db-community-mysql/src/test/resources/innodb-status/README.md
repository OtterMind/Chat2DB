# InnoDB Status Fixtures

These fixtures are sanitized samples for `SHOW ENGINE INNODB STATUS` parser tests.

- `mysql57-deadlock.txt`: MySQL 5.7-style monitor output with a latest deadlock.
- `mysql80-no-deadlock.txt`: MySQL 8.0-style monitor output without a deadlock section.
- `unknown-truncated.txt`: unknown section and truncated latest-deadlock text.
- `init.sql`, `grants.sql`, `cleanup.sql`: repeatable setup, least test grants, and cleanup.
- `session-a.sql`, `session-b.sql`: deterministic two-session deadlock reproduction scripts.

The parser must preserve the complete raw text because InnoDB Monitor output is not a stable API.
