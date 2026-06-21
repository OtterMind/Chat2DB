CREATE TABLE IF NOT EXISTS `environment`
(
    `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create`       datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified`     datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `create_user_id`   bigint unsigned NOT NULL COMMENT '创建人用户id',
    `modified_user_id` bigint unsigned NOT NULL COMMENT '修改人用户id',
    `name`             varchar(128)                 NOT NULL DEFAULT '' COMMENT '环境名称',
    `short_name`       varchar(128)                 DEFAULT NULL COMMENT '环境缩写',
    `color`            varchar(32)                  DEFAULT NULL COMMENT '颜色',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='数据库连接环境'
;

INSERT INTO `environment`
(`id`, `create_user_id`, `modified_user_id`, `name`, `short_name`, `color`)
VALUES (1, 1, 1, 'Release Environment', 'RELEASE', 'RED');
INSERT INTO `environment`
(`id`, `create_user_id`, `modified_user_id`, `name`, `short_name`, `color`)
VALUES (2, 1, 1, 'Test Environment', 'TEST', 'GREEN');

ALTER TABLE `data_source`
    ADD COLUMN `environment_id` bigint unsigned NOT NULL DEFAULT 2 COMMENT '环境id';

ALTER TABLE `data_source`
    modify COLUMN `user_id` bigint unsigned NOT NULL DEFAULT 1 COMMENT '用户id';

ALTER TABLE `data_source`
    ADD COLUMN `kind` varchar(32) NOT NULL DEFAULT 'PRIVATE' COMMENT '连接类型';

update data_source
set user_id= 1;

ALTER TABLE `dbhub_user`
    ADD COLUMN `role_code` varchar(32) DEFAULT NULL COMMENT '角色编码';

ALTER TABLE `dbhub_user`
    ADD `status` varchar(32) NOT NULL DEFAULT 'VALID' COMMENT '用户状态';

ALTER TABLE `dbhub_user`
    ADD `create_user_id`  bigint unsigned NOT NULL DEFAULT 1 COMMENT '创建人用户id';

ALTER TABLE `dbhub_user`
    ADD `modified_user_id` bigint unsigned NOT NULL DEFAULT 1 COMMENT '修改人用户id';

update dbhub_user
set role_code= 'DESKTOP',user_name='_desktop_default_user_name',password='_desktop_default_user_name',nick_name='Desktop User'
where id = 1;
INSERT INTO DBHUB_USER (USER_NAME, PASSWORD, NICK_NAME, EMAIL, ROLE_CODE) VALUES ('chat2db', 'chat2db', 'Administrator', null, 'ADMIN');

create UNIQUE INDEX uk_user_user_name on dbhub_user (user_name);


CREATE TABLE IF NOT EXISTS `team`
(
    `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create`       datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified`     datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `create_user_id`   bigint unsigned NOT NULL COMMENT '创建人用户id',
    `modified_user_id` bigint unsigned NOT NULL COMMENT '修改人用户id',
    `code`             varchar(128)                 NOT NULL DEFAULT '' COMMENT '团队编码',
    `name`             varchar(512)                 DEFAULT NULL COMMENT '团队名称',
    `status`           varchar(32)         NOT NULL DEFAULT 'VALID' COMMENT '团队状态',
    `description`      text                         DEFAULT NULL COMMENT '团队描述',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='团队'
;

create UNIQUE INDEX uk_team_code on team (code);


CREATE TABLE IF NOT EXISTS `team_user`
(
    `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create`       datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified`     datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `create_user_id`   bigint unsigned NOT NULL COMMENT '创建人用户id',
    `modified_user_id` bigint unsigned NOT NULL COMMENT '修改人用户id',
    `team_id`          bigint unsigned NOT NULL COMMENT '团队id',
    `user_id`    bigint unsigned NOT NULL COMMENT '用户id',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户团队表'
;

create INDEX idx_team_user_team_id on team_user (`team_id`);
create INDEX idx_team_user_user_id on team_user (`user_id`);
create UNIQUE INDEX uk_team_user on team_user (`team_id`,`user_id`);

CREATE TABLE IF NOT EXISTS `data_source_access`
(
    `id`                 bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `gmt_create`         datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified`       datetime            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `create_user_id`     bigint unsigned NOT NULL COMMENT '创建人用户id',
    `modified_user_id`   bigint unsigned NOT NULL COMMENT '修改人用户id',
    `data_source_id`     bigint unsigned NOT NULL COMMENT '数据源id',
    `access_object_type` varchar(32)         NOT NULL COMMENT '授权类型',
    `access_object_id`   bigint unsigned NOT NULL COMMENT '授权id,根据类型区分是用户还是团队',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='数据源授权'
;

create INDEX idx_data_source_access_data_source_id on data_source_access (`data_source_id`);
create INDEX idx_data_source_access_access_object_id on data_source_access (`access_object_type`, `access_object_id`);
create UNIQUE INDEX uk_data_source_access on data_source_access (`data_source_id`,`access_object_type`, `access_object_id`);

ALTER TABLE `operation_saved`
    modify COLUMN  `user_id` bigint unsigned NOT NULL DEFAULT 1 COMMENT '用户id';

update operation_saved
set user_id= 1;

ALTER TABLE `operation_log`
    modify COLUMN  `user_id` bigint unsigned NOT NULL DEFAULT 1 COMMENT '用户id';

update operation_log
set user_id= 1;

ALTER TABLE `dashboard`
    modify   `user_id` bigint unsigned NOT NULL DEFAULT 1 COMMENT '用户id';
update dashboard
set user_id= 1;


ALTER TABLE `chart`
    modify `user_id` bigint unsigned NOT NULL DEFAULT 1 COMMENT '用户id';
update chart
set user_id= 1;

