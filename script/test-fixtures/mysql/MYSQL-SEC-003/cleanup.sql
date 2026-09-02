-- MYSQL-SEC-003: Cleanup

DROP USER IF EXISTS 'sec003_admin'@'%';
DROP USER IF EXISTS 'sec003_default'@'%';
DROP USER IF EXISTS 'sec003_never'@'%';
DROP USER IF EXISTS 'sec003_interval'@'%';
DROP USER IF EXISTS 'sec003_limited'@'%';
DROP USER IF EXISTS 'sec003_unlimited'@'%';
