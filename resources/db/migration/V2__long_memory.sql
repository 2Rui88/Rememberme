CREATE TABLE agent_memory_settings (
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL,
    provider VARCHAR(8),
    context_rounds INT NOT NULL,
    filter_sensitive BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, agent_id)
) CHARACTER SET utf8mb4;

CREATE TABLE long_memories (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_id VARCHAR(129) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_key VARCHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX by_owner_scope (owner_id, scope_key, created_at, id)
) CHARACTER SET utf8mb4;
