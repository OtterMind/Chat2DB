CREATE DATABASE IF NOT EXISTS chat2db_innodb_diag;

USE chat2db_innodb_diag;

CREATE TABLE IF NOT EXISTS orders (
  id BIGINT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS customers (
  id BIGINT PRIMARY KEY,
  last_order_id BIGINT NULL
) ENGINE=InnoDB;

INSERT INTO orders (id, customer_id, status)
VALUES (1, 1, 'new')
ON DUPLICATE KEY UPDATE customer_id = VALUES(customer_id), status = VALUES(status);

INSERT INTO customers (id, last_order_id)
VALUES (1, NULL)
ON DUPLICATE KEY UPDATE last_order_id = VALUES(last_order_id);
