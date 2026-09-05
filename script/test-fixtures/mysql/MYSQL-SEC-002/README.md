# MYSQL-SEC-002: Auth plugins and TLS requirements

## Prerequisites

- MySQL 5.7 or 8.x with an account allowed to run `CREATE USER` and `ALTER USER`.
- The `mysql` command-line client.
- OpenSSL when running TLS scenarios.
- `mysql_native_password` scenarios run only when the server reports that plugin as `ACTIVE`.

Do not use these fixed fixture passwords outside an isolated local test server.

## Setup

1. Load `init.sql`, then `grants.sql`, as a local administrative account.
2. For TLS tests, generate disposable certificates:

   ```bash
   ./tls/generate-certs.sh
   ```

3. Replace the paths in `tls/my.cnf.example`, restart the local MySQL server with that
   configuration, and reload `init.sql` so the certificate-constrained account is present.

`init.sql` creates accounts for the server default plugin, optional
`mysql_native_password`, `REQUIRE NONE`, `SSL`, `X509`, and certificate issuer/subject
constraints. It does not install plugins or modify server TLS configuration.

## Product Verification

1. Open the account panel as `sec002_admin`; confirm the plugin options match
   `INFORMATION_SCHEMA.PLUGINS` and each account reloads its plugin/TLS state.
2. Change `sec002_default` to any active alternative plugin and provide a new password.
   The preview must contain `IDENTIFIED WITH ... BY '******'`, never the plaintext value.
3. Confirm the change, refresh, and verify the new plugin is shown.
4. Run `verify-connections.sh` with the same new password to open a separate connection
   as the changed account. This proves the saved authentication method works independently
   of the administrative connection.
5. Exercise `NONE -> SSL -> X509 -> SPECIFIED -> NONE`. For `SPECIFIED`, set issuer and
   subject to the generated certificate identities. Add a supported cipher when testing
   the CIPHER clause.
6. Confirm cancelling before execution sends no request. For an unavailable plugin or an
   invalid certificate constraint, confirm the original MySQL error is shown and account
   metadata remains unchanged after refresh.

## Connection Commands

Set local-only inputs without placing passwords on the command line:

```bash
export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=3306
export MYSQL_SEC_002_PASSWORD='Pass123!'
./verify-connections.sh
```

The script verifies plain, required-TLS, and X.509 connections and prints the authenticated
account plus negotiated cipher. Set `MYSQL_SEC_002_TLS_DIR` when certificates were generated
outside `tls/generated`.

To verify a plugin transition with a different password:

```bash
MYSQL_SEC_002_USER=sec002_default \
MYSQL_SEC_002_PASSWORD='<new-fixture-password>' \
MYSQL_SEC_002_SSL_MODE=PREFERRED \
./verify-connections.sh --single
```

Expected result: the command exits zero and `CURRENT_USER()` identifies the selected fixture
account. Authentication, TLS, permission, or certificate errors must remain nonzero and retain
the server error text.

## Cleanup

Run `cleanup.sql`, stop the local TLS-enabled server, and delete `tls/generated`. Generated
private keys are ignored by Git and must never be committed.
