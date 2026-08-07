-- MYSQL-IMPORT-003: Grants for test user
GRANT INSERT ON `import003_test`.* TO 'import003_admin'@'%';
FLUSH PRIVILEGES;
