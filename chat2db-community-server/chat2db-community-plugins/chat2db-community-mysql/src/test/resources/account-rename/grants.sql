CREATE USER IF NOT EXISTS 'chat2db_rename_old'@'127.0.0.1' IDENTIFIED BY 'chat2db_rename_old_password';
DROP USER IF EXISTS 'chat2db_rename_new'@'127.0.0.1';

GRANT SELECT, EXECUTE, SHOW VIEW, TRIGGER, EVENT
ON chat2db_account_rename_fixture.*
TO 'chat2db_rename_old'@'127.0.0.1';

FLUSH PRIVILEGES;
