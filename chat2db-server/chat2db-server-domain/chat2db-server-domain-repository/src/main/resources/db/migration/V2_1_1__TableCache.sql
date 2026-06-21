CREATE TABLE IF NOT EXISTS `table_cache_version` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `data_source_id` bigint unsigned NOT NULL COMMENT '数据源连接ID',
    `database_name` varchar(256) DEFAULT NULL COMMENT 'db名称',
    `schema_name` varchar(256) DEFAULT NULL COMMENT 'schema名称',
    `key` varchar(256) DEFAULT NULL COMMENT '唯一索引',
    `version` bigint unsigned NOT NULL COMMENT '版本',
    `table_count` bigint unsigned NOT NULL COMMENT '表数量',
    `status` varchar(256) DEFAULT NULL COMMENT '状态',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='table cache version'
;
create INDEX idx_table_cache_version_data_source_id on table_cache_version(`data_source_id`) ;
create UNIQUE INDEX uk_table_cache_version_key on table_cache_version(`key`) ;

CREATE TABLE IF NOT EXISTS `table_cache` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `data_source_id` bigint unsigned NOT NULL COMMENT '数据源连接ID',
    `database_name` varchar(256) DEFAULT NULL COMMENT 'db名称',
    `schema_name` varchar(256) DEFAULT NULL COMMENT 'schema名称',
    `table_name` varchar(256) DEFAULT NULL COMMENT 'table名称',
    `key` varchar(256) DEFAULT NULL COMMENT '唯一索引',
    `version` bigint unsigned NOT NULL COMMENT '版本',
    `columns` varchar(2048) DEFAULT NULL COMMENT '表字段',
    `extend_info` varchar(2048) NULL COMMENT '自定义扩展字段json',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='table cache'
;
create INDEX idx_table_cache_data_source_id on table_cache(`data_source_id`) ;
create INDEX idx_table_cache_key_version on table_cache(`key`,`version`) ;
create INDEX idx_table_cache_key_table_name on table_cache(`key`,`table_name`) ;



