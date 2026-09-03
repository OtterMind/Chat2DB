CREATE USER IF NOT EXISTS 'chat2db_diag'@'%' IDENTIFIED BY 'chat2db_diag_password';
GRANT SELECT, UPDATE ON chat2db_innodb_diag.* TO 'chat2db_diag'@'%';

-- SHOW ENGINE INNODB STATUS normally requires PROCESS.
GRANT PROCESS ON *.* TO 'chat2db_diag'@'%';
