USE chat2db_innodb_diag;

SET autocommit = 0;
START TRANSACTION;
UPDATE customers SET last_order_id = 1 WHERE id = 1;

-- Run the second UPDATE in session-a.sql, then continue.
UPDATE orders SET status = 'session-b' WHERE id = 1;
ROLLBACK;
