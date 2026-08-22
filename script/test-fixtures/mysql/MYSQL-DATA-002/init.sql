-- MYSQL-DATA-002: JSON invalid-submission protection and scalar/NULL semantics
-- Test fixture: table with JSON columns covering all 6 legal root types

CREATE TABLE IF NOT EXISTS `obj002_json_test` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `json_obj` JSON,
    `json_arr` JSON,
    `json_str` JSON,
    `json_num` JSON,
    `json_bool` JSON,
    `json_null` JSON,
    `nullable_text` TEXT,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `obj002_json_test` (`json_obj`, `json_arr`, `json_str`, `json_num`, `json_bool`, `json_null`, `nullable_text`) VALUES
    ('{"name":"Alice","age":30}', '[1,2,3]', '"hello"', '42', 'true', 'null', 'text1'),
    ('{"name":"Bob"}', '["a","b"]', '"world"', '3.14', 'false', 'null', 'text2'),
    (NULL, NULL, NULL, NULL, NULL, NULL, NULL);
