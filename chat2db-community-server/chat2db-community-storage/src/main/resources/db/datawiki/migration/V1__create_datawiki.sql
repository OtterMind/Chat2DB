CREATE TABLE datawiki_definition (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description CLOB,
    resources_json CLOB NOT NULL,
    created_by BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL
);

CREATE INDEX idx_datawiki_definition_owner_updated
    ON datawiki_definition (created_by, updated_at);
