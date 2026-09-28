# MYSQL-CONN-001: MySQL TLS Connector/J Handshake Fixture

This fixture documents the opt-in reviewer setup for validating Chat2DB's structured MySQL TLS properties against an actual Connector/J jar and TLS-enabled MySQL server.

No credentials, certificates, generated stores, database files, or driver jars belong in this directory. Provide them at runtime through environment variables.

## Unit Coverage

`../connector-j-matrix.csv` is the reproducible no-secret matrix used by `MySqlTlsTranslatorTest`:

- Connector/J 8.x maps `REQUIRED`, `VERIFY_CA`, and `VERIFY_IDENTITY` to `sslMode`.
- Connector/J 5.1 maps `REQUIRED` and `VERIFY_CA` to legacy `useSSL`/`requireSSL`/`verifyServerCertificate`.
- Connector/J 5.1 rejects `VERIFY_IDENTITY` because hostname verification cannot be guaranteed.

## Optional Real-Driver Checks

Use a local MySQL server configured for TLS and a locally available Connector/J jar:

```bash
CHAT2DB_MYSQL_TLS_URL=jdbc:mysql://127.0.0.1:3306/mysql \
CHAT2DB_MYSQL_TLS_USER=root \
CHAT2DB_MYSQL_TLS_PASSWORD=replace-at-runtime \
CHAT2DB_MYSQL_TLS_CA_PEM_FILE=/absolute/path/to/ca.pem \
CHAT2DB_MYSQL_TLS_CONNECTOR_JAR=$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar \
mvn -B -f chat2db-community-server/pom.xml \
  -pl :chat2db-community-spi -am \
  -Dmaven.test.skip=false -DskipTests=false \
  -Dtest=MySqlTlsHandshakeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore=false \
  test
```

`MySqlTlsHandshakeIntegrationTest#configuredConnectorJarVersionSelectsExpectedTlsDialectWithoutSecrets` can run with only `CHAT2DB_MYSQL_TLS_CONNECTOR_JAR` and does not require database credentials. The handshake test itself additionally requires the URL, username, password, and CA PEM.

## Expected Result

The handshake test must establish a JDBC connection and `SHOW STATUS LIKE 'Ssl_cipher'` must return a non-empty cipher. If `CHAT2DB_MYSQL_TLS_DRIVER_CLASS=com.mysql.jdbc.Driver` points at a 5.1 driver, `VERIFY_IDENTITY` is expected to fail before connection with `datasource.tls.verifyIdentityUnsupportedConnectorJ5`.
