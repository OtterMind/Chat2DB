ALTER TABLE agent_runtime_run_lease
    ADD COLUMN lease_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE agent_runtime_run_lease
    ADD COLUMN released_at BIGINT;

ALTER TABLE agent_runtime_run_lease
    ADD COLUMN terminal_event_id VARCHAR(128);

CREATE INDEX idx_agent_runtime_run_lease_reconcile
    ON agent_runtime_run_lease(lease_state, lease_expires_at ASC);
