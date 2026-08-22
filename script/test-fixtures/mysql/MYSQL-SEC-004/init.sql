-- MYSQL-SEC-004: Role lifecycle, grants, defaults, and active-role state
-- Test fixture: users, roles, and nested relationships (MySQL 8.0+)

-- Administrator
CREATE USER IF NOT EXISTS 'sec004_admin'@'%' IDENTIFIED BY 'AdminPass123!';
GRANT CREATE USER, SYSTEM_USER, ROLE_ADMIN ON *.* TO 'sec004_admin'@'%';

-- Test users
CREATE USER IF NOT EXISTS 'sec004_user1'@'%' IDENTIFIED BY 'Pass123!';
CREATE USER IF NOT EXISTS 'sec004_user2'@'%' IDENTIFIED BY 'Pass123!';

-- Roles
CREATE ROLE IF NOT EXISTS 'sec004_role_reader';
CREATE ROLE IF NOT EXISTS 'sec004_role_writer';
CREATE ROLE IF NOT EXISTS 'sec004_role_admin';

-- Grant privileges to roles
GRANT SELECT ON *.* TO 'sec004_role_reader';
GRANT SELECT, INSERT, UPDATE, DELETE ON *.* TO 'sec004_role_writer';
GRANT ALL PRIVILEGES ON *.* TO 'sec004_role_admin';

-- Grant roles to users
GRANT 'sec004_role_reader' TO 'sec004_user1'@'%';
GRANT 'sec004_role_writer' TO 'sec004_user1'@'%' WITH ADMIN OPTION;
GRANT 'sec004_role_reader' TO 'sec004_user2'@'%';

-- Nested role grants (role to role)
GRANT 'sec004_role_reader' TO 'sec004_role_writer';

-- Default roles
ALTER USER 'sec004_user1'@'%' DEFAULT ROLE 'sec004_role_reader';

FLUSH PRIVILEGES;
