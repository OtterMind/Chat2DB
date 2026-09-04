USE chat2db_import004;
INSERT INTO import004_innodb(id, value_text) VALUES (10, 'first');
INSERT INTO import004_innodb(id, value_text) VALUES (11, 'second');
INSERT INTO import004_innodb(id, value_text) VALUES (10, 'duplicate key forces rollback or stop');
INSERT INTO import004_innodb(id, value_text) VALUES (12, 'must be reported unexecuted in STOP mode');
