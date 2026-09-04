DROP USER IF EXISTS 'import004_limited'@'%';
CREATE USER 'import004_limited'@'%' IDENTIFIED BY 'import004_fixture_only';
GRANT SELECT, INSERT, UPDATE, DELETE ON chat2db_import004.import004_innodb TO 'import004_limited'@'%';
FLUSH PRIVILEGES;
