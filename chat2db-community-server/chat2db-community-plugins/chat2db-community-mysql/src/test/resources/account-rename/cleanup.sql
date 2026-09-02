DROP EVENT IF EXISTS chat2db_account_rename_fixture.ev_account_rename_source;
DROP TRIGGER IF EXISTS chat2db_account_rename_fixture.tr_account_rename_source_bi;
DROP PROCEDURE IF EXISTS chat2db_account_rename_fixture.pr_account_rename_source;
DROP FUNCTION IF EXISTS chat2db_account_rename_fixture.fn_account_rename_source;
DROP VIEW IF EXISTS chat2db_account_rename_fixture.v_account_rename_source;
DROP TABLE IF EXISTS chat2db_account_rename_fixture.account_rename_source;
DROP DATABASE IF EXISTS chat2db_account_rename_fixture;

DROP USER IF EXISTS 'chat2db_rename_old'@'127.0.0.1';
DROP USER IF EXISTS 'chat2db_rename_new'@'127.0.0.1';

FLUSH PRIVILEGES;
