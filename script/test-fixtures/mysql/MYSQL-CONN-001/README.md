# MYSQL-CONN-001: MySQL TLS handshake

This fixture starts a loopback-only MySQL 8 server with a short-lived, locally generated CA and server
certificate. Generated keys and certificates are ignored and must not be committed.

## Start

```bash
bash generate-certs.sh
docker compose up -d --wait
```

## Connector/J 8 verification

```bash
CHAT2DB_MYSQL_TLS_URL='jdbc:mysql://localhost:33306/chat2db_tls_fixture' \
CHAT2DB_MYSQL_TLS_USER='chat2db_tls' \
CHAT2DB_MYSQL_TLS_PASSWORD='chat2db_fixture_tls' \
CHAT2DB_MYSQL_TLS_CA_PEM_FILE="$PWD/certs/ca.pem" \
CHAT2DB_MYSQL_TLS_CONNECTOR_JAR="$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar" \
mvn -B -f ../../../../chat2db-community-server/pom.xml \
  -pl :chat2db-community-spi -am \
  -Dmaven.test.skip=false -DskipTests=false \
  -Dtest=MySqlTlsHandshakeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=false test
```

The test asserts a non-empty `Ssl_cipher`. It also validates the configured Connector/J jar version without
printing credentials or key material.

## Connector/J 5.1 verification

Point `CHAT2DB_MYSQL_TLS_CONNECTOR_JAR` to `mysql-connector-java-5.1.47.jar` and set
`CHAT2DB_MYSQL_TLS_DRIVER_CLASS=com.mysql.jdbc.Driver`. `REQUIRED` and `VERIFY_CA` are supported.
`VERIFY_IDENTITY` must fail before connection with `datasource.tls.verifyIdentityUnsupportedConnectorJ5`
because Connector/J 5.1 cannot guarantee hostname verification.

## Stop

```bash
docker compose down -v
```
