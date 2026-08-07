DELIMITER //
CREATE PROCEDURE import004_ping()
BEGIN
  SELECT 1;
END//
DELIMITER ;
INSERT INTO import004_items (name, amount) VALUES ('routine', 99.00);
