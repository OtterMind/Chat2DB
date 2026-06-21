CREATE TABLE IF NOT EXISTS `data_source` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL COMMENT '修改时间',
    `alias` varchar(128) DEFAULT NULL COMMENT '别名',
    `url` varchar(1024) DEFAULT NULL COMMENT '连接地址',
    `user_name` varchar(128) DEFAULT NULL COMMENT '用户名',
    `password` varchar(256) DEFAULT NULL COMMENT '密码',
    `type` varchar(32) DEFAULT NULL COMMENT '数据库类型',
    `env_type` varchar(32) DEFAULT NULL COMMENT '环境类型',
    `user_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '用户id',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='数据源连接表'
;

CREATE TABLE IF NOT EXISTS `operation_log` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `data_source_id` bigint unsigned NOT NULL COMMENT '数据源连接ID',
    `database_name` varchar(128) DEFAULT NULL COMMENT 'db名称',
    `type` varchar(32) NOT NULL COMMENT '数据库类型',
    `ddl` text DEFAULT NULL COMMENT 'ddl内容',
    `user_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '用户id',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='我的执行记录表'
;

CREATE TABLE IF NOT EXISTS `operation_saved` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `data_source_id` bigint unsigned NOT NULL COMMENT '数据源连接ID',
    `database_name` varchar(128) DEFAULT NULL COMMENT 'db名称',
    `name` varchar(128) DEFAULT NULL COMMENT '保存名称',
    `type` varchar(32) NOT NULL COMMENT '数据库类型',
    `status` varchar(32) NOT NULL COMMENT 'ddl语句状态:DRAFT/RELEASE',
    `ddl` text DEFAULT NULL COMMENT 'ddl内容',
    `tab_opened` text DEFAULT NULL COMMENT '是否在tab中被打开,y表示打开,n表示未打开',
    `user_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '用户id',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='我的保存表'
;


CREATE TABLE IF NOT EXISTS `dbhub_user` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `user_name` varchar(32) NOT NULL COMMENT '用户名',
    `password` varchar(256) DEFAULT NULL COMMENT '密码',
    `nick_name` varchar(256) DEFAULT NULL COMMENT '昵称',
    `email` varchar(256) DEFAULT NULL COMMENT '邮箱',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB  DEFAULT CHARSET=utf8mb4 COMMENT='数据源连接表'
;

CREATE TABLE IF NOT EXISTS `system_config` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `code` varchar(32) NOT NULL COMMENT '配置项编码',
    `content` varchar(256) DEFAULT NULL COMMENT '配置项内容',
    `summary` varchar(256) DEFAULT NULL COMMENT '配置项说明',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB  DEFAULT CHARSET=utf8mb4 COMMENT='配置中心表'
;
create UNIQUE INDEX uk_code on system_config(code) ;

INSERT INTO `dbhub_user` (`user_name`,`password`,`nick_name`) VALUES ('dbhub','$2a$10$yElafjDHPoPHSaCo6cjJGuWmtXWNVz/cOOOtDg99eNfvUfalzfane','管理员');