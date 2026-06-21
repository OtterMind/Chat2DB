CREATE TABLE IF NOT EXISTS `jdbc_driver` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `db_type` varchar(32) NOT NULL COMMENT 'db类型',
    `jdbc_driver` varchar(512) DEFAULT NULL COMMENT 'jar包',
    `jdbc_driver_class` varchar(512) DEFAULT NULL COMMENT 'driver class类',
    PRIMARY KEY (`id`)
    ) ENGINE=InnoDB  DEFAULT CHARSET=utf8mb4 COMMENT='自定义驱动表'
;
create INDEX idx_db_type on jdbc_driver(db_type) ;
ALTER TABLE `data_source` ADD COLUMN `driver_config` varchar(1024) NULL COMMENT 'driver_config配置';

