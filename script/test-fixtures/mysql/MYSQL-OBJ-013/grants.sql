-- MYSQL-OBJ-013: Grants for test user
-- EVENT privilege is needed to create/drop/alter events in the database.
GRANT EVENT ON `obj013_test`.* TO 'obj013_admin'@'%';
FLUSH PRIVILEGES;
