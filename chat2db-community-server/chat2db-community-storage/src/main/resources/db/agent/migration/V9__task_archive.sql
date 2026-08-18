ALTER TABLE agent_task ADD COLUMN archived_at BIGINT;
CREATE INDEX idx_agent_task_archived_updated ON agent_task(archived_at, updated_at DESC);
