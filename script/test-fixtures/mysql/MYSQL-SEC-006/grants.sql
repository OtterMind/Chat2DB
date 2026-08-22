-- MYSQL-SEC-006: Grants for test user
-- sec006_admin holds EXECUTE/ALTER ROUTINE with GRANT OPTION; sec006_user has no
-- routine grants yet (applied through the UI).
FLUSH PRIVILEGES;
