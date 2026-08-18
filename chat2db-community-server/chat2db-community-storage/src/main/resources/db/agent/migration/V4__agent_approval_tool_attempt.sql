CREATE TABLE agent_sql_proposal (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    proposal_version INTEGER NOT NULL,
    sql_snapshot CLOB NOT NULL,
    sql_hash VARCHAR(64) NOT NULL,
    data_source_id BIGINT NOT NULL,
    database_name VARCHAR(255),
    schema_name VARCHAR(255),
    operation_class VARCHAR(32) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    estimated_impact VARCHAR(1024),
    status VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_agent_sql_proposal_run
        FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT uq_agent_sql_proposal_version
        UNIQUE (run_id, proposal_version)
);

CREATE TABLE agent_approval (
    id VARCHAR(36) PRIMARY KEY,
    proposal_id VARCHAR(36) NOT NULL UNIQUE,
    run_id VARCHAR(36) NOT NULL,
    proposal_version INTEGER NOT NULL,
    proposal_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    requested_at BIGINT NOT NULL,
    decided_by BIGINT,
    decided_at BIGINT,
    decision VARCHAR(32),
    reason VARCHAR(1024),
    revision BIGINT NOT NULL,
    CONSTRAINT fk_agent_approval_proposal
        FOREIGN KEY (proposal_id) REFERENCES agent_sql_proposal(id),
    CONSTRAINT fk_agent_approval_run
        FOREIGN KEY (run_id) REFERENCES agent_run(id)
);

CREATE TABLE agent_tool_attempt (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    proposal_id VARCHAR(36) NOT NULL,
    proposal_version INTEGER NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    write_operation BOOLEAN NOT NULL,
    result_content CLOB,
    error_message CLOB,
    prepared_at BIGINT NOT NULL,
    executing_at BIGINT,
    completed_at BIGINT,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_agent_tool_attempt_run
        FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT fk_agent_tool_attempt_proposal
        FOREIGN KEY (proposal_id) REFERENCES agent_sql_proposal(id),
    CONSTRAINT uq_agent_tool_attempt_idempotency
        UNIQUE (run_id, proposal_version, tool_call_id)
);

CREATE INDEX idx_agent_sql_proposal_run
    ON agent_sql_proposal(run_id, proposal_version DESC);

CREATE INDEX idx_agent_sql_proposal_lookup
    ON agent_sql_proposal(run_id, sql_hash, data_source_id);

CREATE INDEX idx_agent_approval_run_status
    ON agent_approval(run_id, status, requested_at ASC);

CREATE INDEX idx_agent_tool_attempt_run
    ON agent_tool_attempt(run_id, prepared_at ASC);
