CREATE TABLE agent_connector_pairing (
    id VARCHAR(64) PRIMARY KEY,
    client_name VARCHAR(128) NOT NULL,
    poll_token_hash VARCHAR(64) NOT NULL,
    user_code VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    agent_id VARCHAR(64),
    agent_name VARCHAR(128),
    owner_id BIGINT,
    exchange_code VARCHAR(128),
    session_id VARCHAR(64),
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    decided_at BIGINT,
    revision BIGINT NOT NULL
);

CREATE INDEX idx_agent_connector_pairing_status_expiry
    ON agent_connector_pairing (status, expires_at);

CREATE TABLE agent_connector_session (
    id VARCHAR(64) PRIMARY KEY,
    client_name VARCHAR(128) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    agent_name VARCHAR(128) NOT NULL,
    owner_id BIGINT,
    task_id VARCHAR(64),
    run_id VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    access_token_hash VARCHAR(64) NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL,
    access_expires_at BIGINT NOT NULL,
    refresh_expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    last_used_at BIGINT NOT NULL,
    revoked_at BIGINT,
    revision BIGINT NOT NULL
);

CREATE INDEX idx_agent_connector_session_owner_created
    ON agent_connector_session (owner_id, created_at);
