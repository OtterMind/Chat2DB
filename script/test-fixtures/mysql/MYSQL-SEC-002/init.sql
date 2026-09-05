-- MYSQL-SEC-002: Auth plugins and TLS requirements
-- Test fixture: accounts with various auth plugins and TLS settings

-- Administrator. CREATE USER is available on both MySQL 5.7 and 8.x; SYSTEM_USER is
-- intentionally omitted because MySQL 5.7 does not define that dynamic privilege.
CREATE USER IF NOT EXISTS 'sec002_admin'@'%' IDENTIFIED BY 'AdminPass123!';
GRANT CREATE USER ON *.* TO 'sec002_admin'@'%';

-- Account with default auth plugin (caching_sha2_password on 8.0)
CREATE USER IF NOT EXISTS 'sec002_default'@'%' IDENTIFIED BY 'Pass123!';

-- mysql_native_password is always present on MySQL 5.7 but may be disabled or absent on
-- newer MySQL releases. Create this account only when the server reports the plugin ACTIVE.
SET @sec002_native_active = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.PLUGINS
    WHERE PLUGIN_NAME = 'mysql_native_password' AND PLUGIN_STATUS = 'ACTIVE'
);
SET @sec002_native_sql = IF(
    @sec002_native_active > 0,
    'CREATE USER IF NOT EXISTS ''sec002_native''@''%'' IDENTIFIED WITH mysql_native_password BY ''Pass123!''',
    'SELECT ''mysql_native_password is unavailable; sec002_native skipped'''
);
PREPARE sec002_native_stmt FROM @sec002_native_sql;
EXECUTE sec002_native_stmt;
DEALLOCATE PREPARE sec002_native_stmt;

-- Account requiring SSL
CREATE USER IF NOT EXISTS 'sec002_ssl'@'%' IDENTIFIED BY 'Pass123!' REQUIRE SSL;

-- Account requiring X509
CREATE USER IF NOT EXISTS 'sec002_x509'@'%' IDENTIFIED BY 'Pass123!' REQUIRE X509;

-- Account constrained to the generated test CA/client certificate identities.
CREATE USER IF NOT EXISTS 'sec002_specified'@'%' IDENTIFIED BY 'Pass123!'
    REQUIRE ISSUER '/CN=Chat2DB Test CA' AND SUBJECT '/CN=Chat2DB Test Client';

-- Account with no TLS requirement
CREATE USER IF NOT EXISTS 'sec002_none'@'%' IDENTIFIED BY 'Pass123!' REQUIRE NONE;

FLUSH PRIVILEGES;
