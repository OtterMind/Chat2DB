-- MYSQL-SEC-002: Auth plugins and TLS requirements
-- Test fixture: accounts with various auth plugins and TLS settings

-- Administrator
CREATE USER IF NOT EXISTS 'sec002_admin'@'%' IDENTIFIED BY 'AdminPass123!';
GRANT CREATE USER, SYSTEM_USER ON *.* TO 'sec002_admin'@'%';

-- Account with default auth plugin (caching_sha2_password on 8.0)
CREATE USER IF NOT EXISTS 'sec002_default'@'%' IDENTIFIED BY 'Pass123!';

-- Account with mysql_native_password
CREATE USER IF NOT EXISTS 'sec002_native'@'%' IDENTIFIED WITH mysql_native_password BY 'Pass123!';

-- Account requiring SSL
CREATE USER IF NOT EXISTS 'sec002_ssl'@'%' IDENTIFIED BY 'Pass123!' REQUIRE SSL;

-- Account requiring X509
CREATE USER IF NOT EXISTS 'sec002_x509'@'%' IDENTIFIED BY 'Pass123!' REQUIRE X509;

-- Account with no TLS requirement
CREATE USER IF NOT EXISTS 'sec002_none'@'%' IDENTIFIED BY 'Pass123!' REQUIRE NONE;

FLUSH PRIVILEGES;
