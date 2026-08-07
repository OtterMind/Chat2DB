-- MYSQL-SEC-001: Grants for test user
-- The sec001_admin account has CREATE USER and SYSTEM_USER

GRANT SELECT ON mysql.user TO 'sec001_admin'@'%';
GRANT SELECT ON mysql.proc TO 'sec001_admin'@'%' ON MySQL 5.7;
FLUSH PRIVILEGES;
