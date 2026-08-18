CREATE TABLE agent_gateway_channel (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    installation_ref VARCHAR(255) NOT NULL,
    default_agent_id VARCHAR(36) NOT NULL,
    created_by BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    archived_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT uq_agent_gateway_installation UNIQUE (platform, installation_ref, created_by),
    CONSTRAINT fk_agent_gateway_default_agent FOREIGN KEY (default_agent_id) REFERENCES agent_definition(id)
);

CREATE TABLE agent_external_conversation_binding (
    id VARCHAR(36) PRIMARY KEY,
    channel_id VARCHAR(36) NOT NULL,
    chat_id VARCHAR(255) NOT NULL,
    thread_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    archived_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT uq_agent_external_conversation UNIQUE (channel_id, chat_id, thread_id),
    CONSTRAINT fk_agent_external_conversation_channel FOREIGN KEY (channel_id)
        REFERENCES agent_gateway_channel(id)
);

CREATE TABLE agent_inbound_message (
    id VARCHAR(36) PRIMARY KEY,
    channel_id VARCHAR(36) NOT NULL,
    binding_id VARCHAR(36) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    sender_id VARCHAR(255) NOT NULL,
    sender_display_name VARCHAR(255),
    text CLOB NOT NULL,
    mentions_json CLOB NOT NULL,
    attachments_json CLOB NOT NULL,
    agent_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36),
    received_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT uq_agent_inbound_idempotency UNIQUE (channel_id, idempotency_key),
    CONSTRAINT fk_agent_inbound_channel FOREIGN KEY (channel_id) REFERENCES agent_gateway_channel(id),
    CONSTRAINT fk_agent_inbound_binding FOREIGN KEY (binding_id)
        REFERENCES agent_external_conversation_binding(id),
    CONSTRAINT fk_agent_inbound_agent FOREIGN KEY (agent_id) REFERENCES agent_definition(id)
);

CREATE TABLE agent_delivery_outbox (
    id VARCHAR(36) PRIMARY KEY,
    channel_id VARCHAR(36) NOT NULL,
    inbound_message_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36),
    platform VARCHAR(32) NOT NULL,
    installation_ref VARCHAR(255) NOT NULL,
    chat_id VARCHAR(255) NOT NULL,
    thread_id VARCHAR(255) NOT NULL,
    reply_to_message_id VARCHAR(255) NOT NULL,
    content CLOB NOT NULL,
    attachment_refs_json CLOB NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    lease_expires_at BIGINT,
    platform_message_id VARCHAR(255),
    last_error VARCHAR(1024),
    delivered_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT uq_agent_delivery_inbound UNIQUE (inbound_message_id),
    CONSTRAINT uq_agent_delivery_key UNIQUE (channel_id, idempotency_key),
    CONSTRAINT fk_agent_delivery_channel FOREIGN KEY (channel_id) REFERENCES agent_gateway_channel(id),
    CONSTRAINT fk_agent_delivery_inbound FOREIGN KEY (inbound_message_id) REFERENCES agent_inbound_message(id)
);

CREATE INDEX idx_agent_delivery_claim
    ON agent_delivery_outbox(channel_id, status, next_attempt_at, created_at);

CREATE INDEX idx_agent_inbound_task
    ON agent_inbound_message(task_id, created_at);
