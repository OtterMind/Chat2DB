CREATE TABLE agent_runtime_approval (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    lease_attempt INTEGER NOT NULL,
    provider_request_id VARCHAR(256) NOT NULL,
    tool_call_id VARCHAR(256),
    title VARCHAR(512) NOT NULL,
    request_payload CLOB NOT NULL,
    allow_option_id VARCHAR(256) NOT NULL,
    reject_option_id VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_at BIGINT NOT NULL,
    decided_by BIGINT,
    decided_at BIGINT,
    decision VARCHAR(32),
    reason VARCHAR(1024),
    revision BIGINT NOT NULL,
    CONSTRAINT uq_agent_runtime_approval_request
        UNIQUE (run_id, lease_attempt, provider_request_id)
);
