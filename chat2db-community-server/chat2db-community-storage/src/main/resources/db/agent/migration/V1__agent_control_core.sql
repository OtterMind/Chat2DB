CREATE TABLE agent_definition (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    avatar VARCHAR(1024),
    description CLOB,
    status VARCHAR(32) NOT NULL,
    runtime_type VARCHAR(64) NOT NULL,
    runtime_profile_id VARCHAR(255),
    model_config_id VARCHAR(255),
    system_prompt CLOB,
    capabilities_json CLOB NOT NULL,
    data_scopes_json CLOB NOT NULL,
    output_contract CLOB,
    created_by BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL
);

CREATE TABLE agent_task (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    description CLOB,
    acceptance_criteria CLOB,
    status VARCHAR(32) NOT NULL,
    priority INTEGER NOT NULL,
    assignee_agent_id VARCHAR(36) NOT NULL,
    created_by BIGINT,
    origin_type VARCHAR(32) NOT NULL,
    origin_session_id VARCHAR(255),
    origin_message_id VARCHAR(255),
    data_scope_snapshot_json CLOB NOT NULL,
    current_run_id VARCHAR(36),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    completed_at BIGINT,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_agent_task_assignee
        FOREIGN KEY (assignee_agent_id) REFERENCES agent_definition(id)
);

CREATE TABLE agent_run (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    agent_id VARCHAR(36) NOT NULL,
    runtime_type VARCHAR(64) NOT NULL,
    runtime_profile_snapshot CLOB,
    trigger_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL,
    parent_run_id VARCHAR(36),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    started_at BIGINT,
    completed_at BIGINT,
    failure_reason VARCHAR(255),
    result_summary CLOB,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_agent_run_task
        FOREIGN KEY (task_id) REFERENCES agent_task(id),
    CONSTRAINT fk_agent_run_agent
        FOREIGN KEY (agent_id) REFERENCES agent_definition(id),
    CONSTRAINT fk_agent_run_parent
        FOREIGN KEY (parent_run_id) REFERENCES agent_run(id)
);

CREATE INDEX idx_agent_definition_status
    ON agent_definition(status, updated_at);

CREATE INDEX idx_agent_task_status_priority
    ON agent_task(status, priority DESC, created_at DESC);

CREATE INDEX idx_agent_task_assignee
    ON agent_task(assignee_agent_id, status, created_at DESC);

CREATE INDEX idx_agent_run_task
    ON agent_run(task_id, created_at ASC);

CREATE INDEX idx_agent_run_status
    ON agent_run(status, created_at ASC);
