CREATE TABLE agent_connector_conversation (
    id VARCHAR(64) PRIMARY KEY,
    connector_session_id VARCHAR(64) NOT NULL,
    external_session_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at BIGINT NOT NULL,
    last_used_at BIGINT NOT NULL,
    closed_at BIGINT,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_connector_conversation_session
        FOREIGN KEY (connector_session_id) REFERENCES agent_connector_session(id),
    CONSTRAINT uq_connector_external_session
        UNIQUE (connector_session_id, external_session_id)
);

CREATE INDEX idx_connector_conversation_session_activity
    ON agent_connector_conversation (connector_session_id, last_used_at DESC);

CREATE INDEX idx_connector_conversation_task
    ON agent_connector_conversation (task_id);

CREATE TABLE agent_connector_invocation (
    id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    external_call_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    completed_at BIGINT,
    response_json CLOB,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_connector_invocation_conversation
        FOREIGN KEY (conversation_id) REFERENCES agent_connector_conversation(id),
    CONSTRAINT uq_connector_external_call
        UNIQUE (conversation_id, external_call_id)
);

CREATE INDEX idx_connector_invocation_conversation_created
    ON agent_connector_invocation (conversation_id, created_at ASC);

CREATE INDEX idx_connector_invocation_run
    ON agent_connector_invocation (run_id);
