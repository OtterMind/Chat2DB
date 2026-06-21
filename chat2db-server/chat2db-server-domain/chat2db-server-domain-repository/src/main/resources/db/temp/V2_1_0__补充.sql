# ALTER TABLE `operation_saved`
#     modify COLUMN  `user_id` bigint unsigned NOT NULL DEFAULT 1 COMMENT '用户id';
#
# update operation_saved
# set user_id= 1;
#
# ALTER TABLE `dashboard`
#     modify   `user_id` bigint unsigned NOT NULL DEFAULT 1 COMMENT '用户id';
# update dashboard
# set user_id= 1;
#
#
# ALTER TABLE `chart`
#     modify `user_id` bigint unsigned NOT NULL DEFAULT 1 COMMENT '用户id';
# update chart
# set user_id= 1;
