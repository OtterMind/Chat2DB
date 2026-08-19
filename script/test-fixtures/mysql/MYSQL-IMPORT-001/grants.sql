-- MYSQL-IMPORT-001: Grants for test user
-- INSERT on the target tables is required for import.
GRANT INSERT ON `import001_test`.* TO 'import001_admin'@'%';
FLUSH PRIVILEGES;
