-- MYSQL-SEC-003: Grants for test user
-- The sec003_admin account has CREATE USER and SYSTEM_USER privileges

-- For running tests as a limited user:
-- CREATE USER 'chat2db_test'@'%' IDENTIFIED BY 'TestPass123!';
-- GRANT SELECT ON mysql.user TO 'chat2db_test'@'%';
FLUSH PRIVILEGES;
