-- MYSQL-OBJ-009: Grants for test user
-- ALTER is needed for partition maintenance on the tables.
GRANT ALTER ON `obj009_test`.* TO 'obj009_admin'@'%';
FLUSH PRIVILEGES;
