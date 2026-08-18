CREATE TABLE agent_runtime_profile (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    transport VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    executable VARCHAR(1024),
    model VARCHAR(255),
    working_directory_policy VARCHAR(64) NOT NULL,
    custom_arguments_json CLOB NOT NULL,
    environment_references_json CLOB NOT NULL,
    mcp_configuration CLOB,
    timeout_seconds INTEGER NOT NULL,
    max_concurrency INTEGER NOT NULL,
    thinking_mode VARCHAR(64),
    service_tier VARCHAR(64),
    session_resume_enabled BOOLEAN NOT NULL,
    approval_bridge_enabled BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_by BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL
);

CREATE INDEX idx_agent_runtime_profile_provider
    ON agent_runtime_profile(provider, enabled, updated_at DESC);

CREATE TABLE agent_runtime_instance (
    id VARCHAR(36) PRIMARY KEY,
    daemon_id VARCHAR(128) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_version VARCHAR(128) NOT NULL,
    protocol_version VARCHAR(64) NOT NULL,
    capabilities_json CLOB NOT NULL,
    max_concurrency INTEGER NOT NULL,
    active_runs INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_heartbeat_at BIGINT NOT NULL,
    registered_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT uk_agent_runtime_instance_daemon_provider
        UNIQUE (daemon_id, provider)
);

CREATE INDEX idx_agent_runtime_instance_health
    ON agent_runtime_instance(status, last_heartbeat_at DESC);
