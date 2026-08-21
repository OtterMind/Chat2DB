# Security Policy

## Reporting a vulnerability

Do not report security vulnerabilities through public Issues or Discussions.

Please use GitHub's private vulnerability reporting for this repository:

<https://github.com/OtterMind/Chat2DB/security/advisories/new>

Include the affected Chat2DB edition and version, deployment type, impact,
reproduction steps, and any relevant logs. Remove passwords, API keys, access
tokens, private URLs, and production data before submitting.

## Scope

This policy covers security vulnerabilities in the Chat2DB Community code and
its public distribution artifacts. Product-specific Pro or Local issues should
still be reported privately when the issue affects shared Community code.

## Community security model

Chat2DB Community is a single-user, local-first application. The operating
system user who starts Chat2DB is the trusted operator. Community does not
provide user accounts, tenant isolation, or authorization boundaries between
multiple users.

### Supported deployment boundary

Supported Community deployments must keep the HTTP service available only on
the local machine. Bind host access to `127.0.0.1` or `::1` and do not expose
the service directly to other users or untrusted networks.

The Docker container listens on its internal container interface so that host
port publishing works. This does not change the security boundary: the
published host port must remain bound to a loopback address. Multi-user,
shared-server, LAN-exposed, and Internet-facing Community deployments are not
supported.

### Trusted executable extensions

Custom JDBC drivers are executable Java code. Installing a custom driver is
equivalent to installing a plugin or running third-party software. Drivers
explicitly selected or uploaded by the trusted operator are inside the
Community trust boundary and must come from a source the operator trusts.

A report that relies only on the trusted operator intentionally installing a
malicious driver is outside this security model. A vulnerability remains in
scope when an untrusted party or untrusted input can install, replace, select,
or execute a driver without the operator's explicit intent.

### Untrusted data

Local-first does not mean that all processed content is trusted. Imported
configuration files and archives, SQL files, database contents, AI responses,
downloaded data, browser origins, and HTTP requests not initiated by the
trusted operator must be treated as untrusted data.

Processing untrusted data must not allow code execution, filesystem escape,
credential disclosure, unauthorized network access, or modification of
trusted application files.

### Credentials supplied for an import

Importing a DBeaver `.dbp` archive can require the DBeaver master password,
because DBeaver encrypts `credentials-config.json` with a key derived from it.
Chat2DB treats that password as request-scoped input:

- It is accepted only as an optional parameter of the import request, is used
  only to decrypt the credential files of that archive, and is never written to
  Chat2DB storage, logs, or error messages.
- The extracted copy of the archive holds decrypted credentials, so it is
  removed when the import finishes, including when the import fails.
- Supplying it is always optional. Without it, connections whose credentials
  cannot be decrypted are imported without a user or password, which is what the
  import dialog already announces.

The password crosses the HTTP boundary in the same request form as the
connection passwords the datasource endpoints already accept, so it inherits the
deployment boundary above rather than adding a new one: the service must stay
bound to a loopback address, where the request does not leave the machine. A
network-reachable Community deployment is unsupported for this reason, and would
expose this password exactly as it exposes every connection password unless the
API is served over HTTPS.

### Out of scope

The following are outside the supported Community security boundary:

- A trusted operator intentionally installing a malicious custom JDBC driver.
- Attacks that require control of the same operating-system account as the
  Chat2DB process.
- Multi-user or remote-network deployments created by overriding the
  loopback-only configuration.
- Damage caused by directly modifying Chat2DB local storage while already
  possessing equivalent filesystem access.
