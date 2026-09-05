-- MYSQL-SEC-002: Cleanup

DROP USER IF EXISTS 'sec002_admin'@'%';
DROP USER IF EXISTS 'sec002_default'@'%';
DROP USER IF EXISTS 'sec002_native'@'%';
DROP USER IF EXISTS 'sec002_ssl'@'%';
DROP USER IF EXISTS 'sec002_x509'@'%';
DROP USER IF EXISTS 'sec002_specified'@'%';
DROP USER IF EXISTS 'sec002_none'@'%';
