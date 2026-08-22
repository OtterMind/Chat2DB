-- MYSQL-OPS-006: Grants for test user

GRANT PROCESS ON *.* TO 'ops006_admin'@'%';
FLUSH PRIVILEGES;
