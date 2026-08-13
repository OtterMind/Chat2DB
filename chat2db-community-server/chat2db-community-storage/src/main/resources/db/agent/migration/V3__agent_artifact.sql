CREATE TABLE agent_artifact (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    title VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_version INTEGER NOT NULL,
    created_by_run_id VARCHAR(36),
    created_by BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_agent_artifact_task
        FOREIGN KEY (task_id) REFERENCES agent_task(id),
    CONSTRAINT fk_agent_artifact_run
        FOREIGN KEY (created_by_run_id) REFERENCES agent_run(id),
    CONSTRAINT uq_agent_artifact_run_type
        UNIQUE (task_id, created_by_run_id, artifact_type)
);

CREATE TABLE agent_artifact_version (
    artifact_id VARCHAR(36) NOT NULL,
    version INTEGER NOT NULL,
    content_mode VARCHAR(32) NOT NULL,
    content_json CLOB NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_by_run_id VARCHAR(36),
    created_at BIGINT NOT NULL,
    supersedes_version INTEGER,
    PRIMARY KEY (artifact_id, version),
    CONSTRAINT fk_agent_artifact_version_artifact
        FOREIGN KEY (artifact_id) REFERENCES agent_artifact(id),
    CONSTRAINT fk_agent_artifact_version_run
        FOREIGN KEY (created_by_run_id) REFERENCES agent_run(id)
);

CREATE TABLE agent_artifact_evidence (
    id VARCHAR(36) PRIMARY KEY,
    artifact_id VARCHAR(36) NOT NULL,
    artifact_version INTEGER NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    tool_attempt_id VARCHAR(36),
    data_source_id BIGINT,
    database_name VARCHAR(255),
    schema_name VARCHAR(255),
    sql_snapshot CLOB,
    sql_hash VARCHAR(64),
    executed_at BIGINT,
    row_count BIGINT,
    result_snapshot_id VARCHAR(128),
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_agent_artifact_evidence_version
        FOREIGN KEY (artifact_id, artifact_version)
        REFERENCES agent_artifact_version(artifact_id, version),
    CONSTRAINT fk_agent_artifact_evidence_run
        FOREIGN KEY (run_id) REFERENCES agent_run(id)
);

CREATE INDEX idx_agent_artifact_task
    ON agent_artifact(task_id, updated_at DESC);

CREATE INDEX idx_agent_artifact_evidence_artifact
    ON agent_artifact_evidence(artifact_id, artifact_version, created_at ASC);
