CREATE TABLE IF NOT EXISTS `dashboard` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `name` varchar(128) DEFAULT NULL COMMENT '报表名称',
    `description` varchar(128) DEFAULT NULL COMMENT '报表描述',
    `schema` text DEFAULT NULL COMMENT '报表布局信息',
    `deleted` text DEFAULT NULL COMMENT '是否被删除,y表示删除,n表示未删除',
    `user_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '用户id',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='自定义报表表'
;

CREATE TABLE IF NOT EXISTS `chart` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `name` varchar(128) DEFAULT NULL COMMENT '图表名称',
    `description` varchar(128) DEFAULT NULL COMMENT '图表描述',
    `schema` text DEFAULT NULL COMMENT '图表信息',
    `data_source_id` bigint unsigned DEFAULT NULL COMMENT '数据源连接ID',
    `type` varchar(32) DEFAULT NULL COMMENT '数据库类型',
    `database_name` varchar(128) DEFAULT NULL COMMENT 'db名称',
    `ddl` text DEFAULT NULL COMMENT 'ddl内容',
    `deleted` text DEFAULT NULL COMMENT '是否被删除,y表示删除,n表示未删除',
    `user_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '用户id',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='自定义报表表'
;

CREATE TABLE IF NOT EXISTS `dashboard_chart_relation` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `dashboard_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '报表id',
    `chart_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '图表id',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='自定义报表表'
;
