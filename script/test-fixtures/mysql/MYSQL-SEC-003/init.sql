-- MYSQL-SEC-003: Password expiration and resource limits
-- Test fixture: test accounts with various policies

-- Administrator account (for running tests)
CREATE USER IF NOT EXISTS 'sec003_admin'@'%' IDENTIFIED BY 'AdminPass123!';
GRANT CREATE USER, SYSTEM_USER ON *.* TO 'sec003_admin'@'%';

-- Test account with default password policy
CREATE USER IF NOT EXISTS 'sec003_default'@'%' IDENTIFIED BY 'Pass123!';

-- Test account with never-expire policy
CREATE USER IF NOT EXISTS 'sec003_never'@'%' IDENTIFIED BY 'Pass123!' PASSWORD EXPIRE NEVER;

-- Test account with interval expiration (90 days)
CREATE USER IF NOT EXISTS 'sec003_interval'@'%' IDENTIFIED BY 'Pass123!' PASSWORD EXPIRE INTERVAL 90 DAY;

-- Test account with resource limits
CREATE USER IF NOT EXISTS 'sec003_limited'@'%' IDENTIFIED BY 'Pass123!'
    WITH MAX_QUERIES_PER_HOUR 100 MAX_UPDATES_PER_HOUR 50 MAX_CONNECTIONS_PER_HOUR 10 MAX_USER_CONNECTIONS 5;

-- Test account with zero limits (limits removed)
CREATE USER IF NOT EXISTS 'sec003_unlimited'@'%' IDENTIFIED BY 'Pass123!'
    WITH MAX_QUERIES_PER_HOUR 0 MAX_UPDATES_PER_HOUR 0 MAX_CONNECTIONS_PER_HOUR 0 MAX_USER_CONNECTIONS 0;

FLUSH PRIVILEGES;
