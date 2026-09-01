-- MYSQL-OBJ-007: Cleanup

DROP TABLE IF EXISTS `obj007_categories`;
DROP TABLE IF EXISTS `obj007_project_members`;
DROP TABLE IF EXISTS `obj007_projects`;
DROP TABLE IF EXISTS `obj007_employees`;
DROP TABLE IF EXISTS `obj007_departments`;
DROP TABLE IF EXISTS `obj007_type_mismatch_child`;
DROP TABLE IF EXISTS `obj007_type_mismatch_parent`;
DROP TABLE IF EXISTS `obj007_missing_index_parent`;
DROP TABLE IF EXISTS `obj007_orphan_child`;
DROP TABLE IF EXISTS `obj007_orphan_parent`;
DROP TABLE IF EXISTS `obj007_partition_child`;
DROP TABLE IF EXISTS `obj007_partition_parent`;
