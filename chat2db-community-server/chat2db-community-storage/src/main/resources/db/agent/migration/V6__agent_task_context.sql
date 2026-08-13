CREATE TABLE agent_task_context (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    context_type VARCHAR(32) NOT NULL,
    title VARCHAR(255),
    content CLOB NOT NULL,
    attachment_name VARCHAR(512),
    attachment_mime_type VARCHAR(255),
    attachment_size BIGINT,
    created_by BIGINT,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_agent_task_context_task
        FOREIGN KEY (task_id) REFERENCES agent_task(id)
);

CREATE INDEX idx_agent_task_context_task_created
    ON agent_task_context(task_id, created_at ASC, id ASC);
