-- MYSQL-IMPORT-004: Grants for test user
GRANT INSERT, CREATE, ALTER, DROP ON `import004_test`.* TO 'import004_admin'@'%';
FLUSH PRIVILEGES;
