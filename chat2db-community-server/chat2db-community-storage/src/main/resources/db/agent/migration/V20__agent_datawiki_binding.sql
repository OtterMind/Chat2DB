ALTER TABLE agent_definition
    ADD COLUMN data_wiki_ids_json CLOB DEFAULT '[]' NOT NULL;
