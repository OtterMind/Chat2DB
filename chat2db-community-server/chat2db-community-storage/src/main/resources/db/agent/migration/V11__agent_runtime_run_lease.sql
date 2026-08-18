ALTER TABLE agent_run
    ADD COLUMN runtime_profile_id VARCHAR(36);

ALTER TABLE agent_run
    ADD COLUMN runtime_provider VARCHAR(32);

UPDATE agent_run
SET runtime_profile_id = runtime_profile_snapshot
WHERE runtime_type = 'EXTERNAL_AGENT'
  AND runtime_profile_snapshot IS NOT NULL
  AND LENGTH(runtime_profile_snapshot) <= 36;

UPDATE agent_run r
SET runtime_provider = (
    SELECT p.provider
    FROM agent_runtime_profile p
    WHERE p.id = r.runtime_profile_id
)
WHERE r.runtime_type = 'EXTERNAL_AGENT'
  AND r.runtime_profile_id IS NOT NULL;

CREATE INDEX idx_agent_run_runtime_queue
    ON agent_run(runtime_type, status, runtime_provider, created_at ASC);

CREATE TABLE agent_runtime_run_lease (
    run_id VARCHAR(36) PRIMARY KEY,
    runtime_instance_id VARCHAR(36) NOT NULL,
    lease_attempt INTEGER NOT NULL,
    lease_token_hash VARCHAR(64) NOT NULL,
    task_token_hash VARCHAR(64) NOT NULL,
    claimed_at BIGINT NOT NULL,
    lease_expires_at BIGINT NOT NULL,
    last_renewed_at BIGINT NOT NULL,
    started_at BIGINT,
    runtime_execution_id VARCHAR(255),
    cancel_requested_at BIGINT,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_agent_runtime_run_lease_run
        FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT fk_agent_runtime_run_lease_instance
        FOREIGN KEY (runtime_instance_id) REFERENCES agent_runtime_instance(id)
);

CREATE INDEX idx_agent_runtime_run_lease_expiry
    ON agent_runtime_run_lease(lease_expires_at ASC);

CREATE INDEX idx_agent_runtime_run_lease_instance
    ON agent_runtime_run_lease(runtime_instance_id, lease_expires_at ASC);
