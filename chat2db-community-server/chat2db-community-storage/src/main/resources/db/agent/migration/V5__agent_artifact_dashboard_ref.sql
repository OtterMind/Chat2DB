CREATE TABLE agent_artifact_dashboard_ref (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    artifact_id VARCHAR(36) NOT NULL,
    artifact_version INTEGER NOT NULL,
    chart_index INTEGER NOT NULL,
    dashboard_id BIGINT NOT NULL,
    chart_id BIGINT NOT NULL,
    content_mode VARCHAR(32) NOT NULL,
    published_by BIGINT NOT NULL,
    published_at BIGINT NOT NULL,
    CONSTRAINT fk_agent_artifact_dashboard_ref_task
        FOREIGN KEY (task_id) REFERENCES agent_task(id),
    CONSTRAINT fk_agent_artifact_dashboard_ref_version
        FOREIGN KEY (artifact_id, artifact_version)
        REFERENCES agent_artifact_version(artifact_id, version),
    CONSTRAINT uq_agent_artifact_dashboard_publication
        UNIQUE (artifact_id, artifact_version, chart_index, dashboard_id, content_mode),
    CONSTRAINT uq_agent_artifact_dashboard_chart UNIQUE (chart_id)
);

CREATE INDEX idx_agent_artifact_dashboard_ref_task
    ON agent_artifact_dashboard_ref(task_id, published_at DESC);
