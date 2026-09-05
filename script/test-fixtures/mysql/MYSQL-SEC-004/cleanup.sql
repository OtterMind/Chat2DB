-- MYSQL-SEC-004: Cleanup

DROP ROLE IF EXISTS 'sec004_role_admin';
DROP ROLE IF EXISTS 'sec004_role_writer';
DROP ROLE IF EXISTS 'sec004_role_reader';
DROP ROLE IF EXISTS 'sec004_role_standalone';
DROP USER IF EXISTS 'sec004_user1'@'%';
DROP USER IF EXISTS 'sec004_user2'@'%';
DROP USER IF EXISTS 'sec004_admin'@'%';
