# MySQL TLS Fixture Matrix

`connector-j-matrix.csv` pins the local unit coverage for Chat2DB's structured MySQL TLS payload against Connector/J `8.0.30` and `5.1.47`.
`MYSQL-CONN-001/` documents the reproducible real-driver fixture for reviewer handshakes without checking secrets into the repository.

The real handshake fixture remains opt-in because it needs a local TLS-enabled MySQL instance and driver jar:

```bash
CHAT2DB_MYSQL_TLS_URL=jdbc:mysql://127.0.0.1:3306/mysql \
CHAT2DB_MYSQL_TLS_USER=root \
CHAT2DB_MYSQL_TLS_PASSWORD=secret \
CHAT2DB_MYSQL_TLS_CA_PEM_FILE=/path/to/ca.pem \
CHAT2DB_MYSQL_TLS_CONNECTOR_JAR=$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.30/mysql-connector-j-8.0.30.jar \
mvn -B -f chat2db-community-server/pom.xml -pl :chat2db-community-spi -am \
  -Dmaven.test.skip=false -DskipTests=false -Dtest=MySqlTlsHandshakeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=false test
```
