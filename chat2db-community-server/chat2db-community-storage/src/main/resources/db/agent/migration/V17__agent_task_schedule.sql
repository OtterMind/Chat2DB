ALTER TABLE agent_task ADD COLUMN origin_schedule_id VARCHAR(36);
ALTER TABLE agent_task ADD COLUMN origin_schedule_execution_id VARCHAR(36);
ALTER TABLE agent_task ADD COLUMN planned_at BIGINT;

CREATE INDEX idx_agent_task_origin_schedule
    ON agent_task(origin_schedule_id, created_at DESC);

CREATE TABLE agent_task_schedule (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    task_title VARCHAR(256) NOT NULL,
    task_description CLOB,
    acceptance_criteria CLOB,
    assignee_agent_id VARCHAR(36) NOT NULL,
    priority INTEGER NOT NULL,
    data_scope_snapshot_json CLOB NOT NULL,
    schedule_type VARCHAR(16) NOT NULL,
    scheduled_at BIGINT,
    cron_expression VARCHAR(255),
    timezone VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    concurrency_policy VARCHAR(16) NOT NULL,
    catch_up_policy VARCHAR(32) NOT NULL,
    next_run_at BIGINT,
    last_run_at BIGINT,
    created_by BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL
);

CREATE INDEX idx_agent_task_schedule_owner
    ON agent_task_schedule(created_by, status, updated_at DESC);

CREATE INDEX idx_agent_task_schedule_due
    ON agent_task_schedule(status, next_run_at);

CREATE TABLE agent_task_schedule_execution (
    id VARCHAR(36) PRIMARY KEY,
    schedule_id VARCHAR(36) NOT NULL,
    source VARCHAR(16) NOT NULL,
    planned_at BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_id VARCHAR(36),
    run_id VARCHAR(36),
    attempt INTEGER NOT NULL,
    lease_token VARCHAR(36),
    lease_expires_at BIGINT,
    reason_code VARCHAR(64),
    failure_reason CLOB,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT uq_agent_task_schedule_execution
        UNIQUE(schedule_id, planned_at, source)
);

CREATE INDEX idx_agent_task_schedule_execution_history
    ON agent_task_schedule_execution(schedule_id, planned_at DESC);

CREATE INDEX idx_agent_task_schedule_execution_recovery
    ON agent_task_schedule_execution(status, lease_expires_at);
