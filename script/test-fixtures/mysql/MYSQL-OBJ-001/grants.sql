-- MYSQL-OBJ-001: Grants for test user
-- ALTER is needed on the database and the tables to change defaults.
GRANT ALTER ON `obj001_test`.* TO 'obj001_admin'@'%';
FLUSH PRIVILEGES;
