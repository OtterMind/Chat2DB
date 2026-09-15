USE chat2db_innodb_diag;

SET autocommit = 0;
START TRANSACTION;
UPDATE orders SET status = 'session-a' WHERE id = 1;

-- Run session-b.sql through its first UPDATE, then continue.
UPDATE customers SET last_order_id = 1 WHERE id = 1;
ROLLBACK;
