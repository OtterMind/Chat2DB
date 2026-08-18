ALTER TABLE agent_runtime_run_lease
    ADD COLUMN last_event_sequence BIGINT NOT NULL DEFAULT 0;

ALTER TABLE agent_run_event
    ADD COLUMN runtime_attempt INTEGER;

ALTER TABLE agent_run_event
    ADD COLUMN runtime_sequence BIGINT;

CREATE UNIQUE INDEX uk_agent_run_event_runtime_sequence
    ON agent_run_event(run_id, runtime_attempt, runtime_sequence);
