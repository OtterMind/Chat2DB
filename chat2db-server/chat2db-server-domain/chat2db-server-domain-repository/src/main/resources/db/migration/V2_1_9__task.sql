CREATE TABLE IF NOT EXISTS `task` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `data_source_id` bigint unsigned NULL COMMENT '数据源连接ID',
    `database_name` varchar(128) DEFAULT NULL COMMENT 'db名称',
    `schema_name` varchar(128) DEFAULT NULL COMMENT 'schema名称',
    `table_name` varchar(128) DEFAULT NULL COMMENT 'table_name',
    `deleted` varchar(10) DEFAULT NULL COMMENT '是否被删除,y表示删除,n表示未删除',
    `user_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '用户id',
    `task_type` varchar(128) DEFAULT NULL COMMENT 'task type, such as: DOWNLOAD_DATA, UPLOAD_TABLE_DATA, DOWNLOAD_TABLE_STRUCTURE, UPLOAD_TABLE_STRUCTURE,',
    `task_status` varchar(128) DEFAULT NULL COMMENT 'task status',
    `task_progress` varchar(128) DEFAULT NULL COMMENT 'task progress',
    `task_name` varchar(128) DEFAULT NULL COMMENT 'task name',
    `content` blob DEFAULT NULL COMMENT 'task content',
    `download_url` varchar(512) DEFAULT NULL COMMENT 'down load url',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='TASK TABLE'
;
create INDEX idx_task_user_id on task(user_id) ;