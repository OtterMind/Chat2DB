-- MYSQL-OPS-003: Grants for test user
-- PROCESS exposes other sessions. Performance Schema and sys grants cover both
-- MySQL 8.0 data_locks and the cross-version metadata-wait view.
GRANT PROCESS ON *.* TO 'ops003_admin'@'%';
SET @ops003_grant_data_locks = IF(
    EXISTS(
        SELECT 1
        FROM `information_schema`.`tables`
        WHERE `table_schema` = 'performance_schema' AND `table_name` = 'data_locks'
    ),
    'GRANT SELECT ON `performance_schema`.`data_locks` TO ''ops003_admin''@''%''',
    'DO 0'
);
PREPARE ops003_grant_statement FROM @ops003_grant_data_locks;
EXECUTE ops003_grant_statement;
DEALLOCATE PREPARE ops003_grant_statement;

SET @ops003_grant_data_lock_waits = IF(
    EXISTS(
        SELECT 1
        FROM `information_schema`.`tables`
        WHERE `table_schema` = 'performance_schema' AND `table_name` = 'data_lock_waits'
    ),
    'GRANT SELECT ON `performance_schema`.`data_lock_waits` TO ''ops003_admin''@''%''',
    'DO 0'
);
PREPARE ops003_grant_statement FROM @ops003_grant_data_lock_waits;
EXECUTE ops003_grant_statement;
DEALLOCATE PREPARE ops003_grant_statement;

GRANT SELECT ON `performance_schema`.`metadata_locks` TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`threads` TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`events_statements_current` TO 'ops003_admin'@'%';
GRANT SELECT ON `sys`.`schema_table_lock_waits` TO 'ops003_admin'@'%';
GRANT SELECT ON `sys`.`sys_config` TO 'ops003_admin'@'%';
GRANT EXECUTE ON FUNCTION `sys`.`ps_thread_account` TO 'ops003_admin'@'%';
GRANT EXECUTE ON FUNCTION `sys`.`format_statement` TO 'ops003_admin'@'%';
GRANT EXECUTE ON FUNCTION `sys`.`sys_get_config` TO 'ops003_admin'@'%';
FLUSH PRIVILEGES;
