ALTER TABLE agent_run
    ADD COLUMN provider_session_id VARCHAR(512);

CREATE INDEX idx_agent_run_provider_session
    ON agent_run(runtime_provider, provider_session_id);
