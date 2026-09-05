-- MYSQL-SEC-002: Grants for test user

GRANT CREATE USER ON *.* TO 'sec002_admin'@'%';
FLUSH PRIVILEGES;
