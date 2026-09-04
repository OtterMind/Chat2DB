CREATE USER IF NOT EXISTS 'chat2db_tls'@'%' IDENTIFIED BY 'chat2db_fixture_tls';
GRANT ALL PRIVILEGES ON chat2db_tls_fixture.* TO 'chat2db_tls'@'%';
FLUSH PRIVILEGES;
