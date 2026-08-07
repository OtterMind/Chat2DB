-- MYSQL-IMPORT-002: Grants for test user
GRANT INSERT ON `import002_test`.* TO 'import002_admin'@'%';
FLUSH PRIVILEGES;
