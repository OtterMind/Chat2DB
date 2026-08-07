-- MYSQL-OBJ-002: Grants for test user
GRANT ALTER, SELECT, INSERT, UPDATE ON `obj002_test`.* TO 'obj002_admin'@'%';
FLUSH PRIVILEGES;
