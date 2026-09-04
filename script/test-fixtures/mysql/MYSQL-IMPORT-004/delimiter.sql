USE chat2db_import004;

DELIMITER $$
CREATE PROCEDURE import004_insert(IN p_id BIGINT, IN p_value VARCHAR(255))
BEGIN
    INSERT INTO import004_innodb(id, value_text) VALUES (p_id, p_value);
END$$

CREATE TRIGGER import004_before_insert
BEFORE INSERT ON import004_innodb
FOR EACH ROW
BEGIN
    SET NEW.value_text = TRIM(NEW.value_text);
END$$
DELIMITER ;

CALL import004_insert(1, ' delimiter fixture ');
