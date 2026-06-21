CREATE TABLE IF NOT EXISTS `table_vector_mapping` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `api_key` varchar(128) DEFAULT NULL COMMENT 'api key',
    `data_source_id` bigint unsigned DEFAULT NULL COMMENT '数据源连接ID',
    `database` text DEFAULT NULL COMMENT '数据库名称',
    `schema` text DEFAULT NULL COMMENT 'schema名称',
    `status` varchar(4) DEFAULT NULL COMMENT '向量保存状态',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='milvus映射表保存记录'
;

create INDEX idx_api_key on table_vector_mapping(api_key) ;
