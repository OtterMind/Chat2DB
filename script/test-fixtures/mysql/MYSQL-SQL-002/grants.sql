-- MYSQL-SQL-002: Grants for test user

CREATE USER IF NOT EXISTS 'chat2db_explain_reader'@'%' IDENTIFIED BY 'chat2db_fixture_only';
CREATE USER IF NOT EXISTS 'chat2db_explain_limited'@'%' IDENTIFIED BY 'chat2db_fixture_only';

GRANT SELECT ON *.* TO 'chat2db_explain_reader'@'%';
FLUSH PRIVILEGES;
