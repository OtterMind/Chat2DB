-- MYSQL-OBJ-001: Grants for test user
-- ALTER changes defaults; SELECT lets the table editor read columns and indexes.
GRANT ALTER, SELECT ON `obj001_test`.* TO 'obj001_admin'@'%';
GRANT SELECT ON `obj001_test`.* TO 'obj001_viewer'@'%';
FLUSH PRIVILEGES;
