-- MYSQL-SEC-005: Grants for test user
-- sec005_admin can grant column privileges (holds GRANT OPTION on the database).
-- sec005_user starts with no grants; column grants are added through the UI.
FLUSH PRIVILEGES;
