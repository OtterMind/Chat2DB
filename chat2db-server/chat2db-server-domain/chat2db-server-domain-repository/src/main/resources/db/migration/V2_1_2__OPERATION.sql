ALTER TABLE `operation_log`
    ADD COLUMN  `status` varchar(20)  DEFAULT 'success' COMMENT '状态';
ALTER TABLE `operation_log`
    ADD COLUMN  `operation_rows` bigint unsigned  COMMENT '操作行数';
ALTER TABLE `operation_log`
    ADD COLUMN  `use_time` bigint unsigned COMMENT '使用时长';
ALTER TABLE `operation_log`
    ADD COLUMN  `extend_info` varchar(1024) COMMENT '扩展信息';