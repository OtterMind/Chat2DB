CREATE TABLE IF NOT EXISTS `pin_table` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `data_source_id` bigint unsigned NOT NULL COMMENT '数据源连接ID',
    `database_name` varchar(128) DEFAULT NULL COMMENT 'db名称',
    `schema_name` varchar(128) DEFAULT NULL COMMENT 'schema名称',
    `table_name` varchar(128) DEFAULT NULL COMMENT 'table_name',
    `deleted` text DEFAULT NULL COMMENT '是否被删除,y表示删除,n表示未删除',
    `user_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '用户id',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='PIN TABLES'
;
create INDEX idx_user_id_data_source_id on pin_table(user_id,data_source_id) ;