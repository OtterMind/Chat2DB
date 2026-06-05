CREATE TABLE IF NOT EXISTS `ai_conversation` (
    `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `conversation_id` varchar(64) NOT NULL COMMENT '客户端会话UUID',
    `user_id` bigint(20) unsigned NOT NULL DEFAULT 0 COMMENT '用户id',
    `title` varchar(256) DEFAULT NULL COMMENT '会话标题(异步AI生成)',
    `data_source_id` bigint(20) unsigned DEFAULT NULL COMMENT '关联数据源',
    `database_name` varchar(128) DEFAULT NULL COMMENT '数据库名',
    `schema_name` varchar(128) DEFAULT NULL COMMENT 'schema名',
    `message_count` int(11) NOT NULL DEFAULT 0 COMMENT '消息数量',
    `last_message_preview` varchar(512) DEFAULT NULL COMMENT '最后一条消息预览',
    `status` varchar(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED/DELETED',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv_id` (`conversation_id`),
    KEY `idx_user_modified` (`user_id`, `gmt_modified`),
    KEY `idx_user_ds` (`user_id`, `data_source_id`),
    KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话表';

CREATE TABLE IF NOT EXISTS `ai_message` (
    `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `conversation_id` varchar(64) NOT NULL COMMENT '会话ID',
    `user_id` bigint(20) unsigned NOT NULL DEFAULT 0 COMMENT '用户id',
    `message_id` varchar(64) NOT NULL COMMENT '客户端消息UUID',
    `role` varchar(16) NOT NULL COMMENT 'user/assistant',
    `content` longtext NOT NULL COMMENT '消息内容',
    `thinking` longtext DEFAULT NULL COMMENT '思考过程',
    `prompt_type` varchar(32) DEFAULT NULL COMMENT 'PromptType',
    `sql_extracted` longtext DEFAULT NULL COMMENT '提取的SQL(用于revision续接)',
    `sequence_no` int(11) NOT NULL COMMENT '消息序号(0,1,2...)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv_msg` (`conversation_id`, `message_id`),
    KEY `idx_conv_seq` (`conversation_id`, `sequence_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 消息表';
