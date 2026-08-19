INSERT INTO agent_definition (
    id, name, avatar, description, status, runtime_type,
    runtime_profile_id, model_config_id, system_prompt,
    capabilities_json, data_scopes_json, output_contract,
    created_by, created_at, updated_at, revision
)
SELECT
    'chat2db-default-agent', 'Chat2DB', '/logo-transparent.webp', NULL, 'ACTIVE', 'EMBEDDED_SPRING_AI',
    NULL, NULL, NULL,
    '["METADATA_READ","DATA_READ"]', '[]', NULL,
    NULL, 0, 0, 1
WHERE NOT EXISTS (
    SELECT 1 FROM agent_definition WHERE name = 'Chat2DB'
);
