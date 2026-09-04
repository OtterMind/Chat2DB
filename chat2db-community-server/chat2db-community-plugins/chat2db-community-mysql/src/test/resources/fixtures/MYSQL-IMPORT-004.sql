-- MYSQL-IMPORT-004: repeatable SQL import fixture for parser and transaction preflight coverage.
SET NAMES utf8mb4;

DELIMITER //
CREATE PROCEDURE import_004_routine()
BEGIN
    INSERT INTO orders(id, name) VALUES (1, 'routine');
END//

CREATE TRIGGER import_004_trigger BEFORE INSERT ON orders
FOR EACH ROW
BEGIN
    SET NEW.name = CONCAT('checked-', NEW.name);
END//
DELIMITER ;

INSERT INTO orders(id, name) VALUES (2, 'insert');
UPDATE orders SET name = 'updated' WHERE id = 2;
DELETE FROM orders WHERE id = 3;
REPLACE INTO orders(id, name) VALUES (4, 'replace');
