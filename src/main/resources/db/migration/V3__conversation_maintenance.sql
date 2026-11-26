ALTER TABLE conversations
    ADD COLUMN last_activity_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN completed_turns BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN open_turn BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN memory_through BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN memory_turns BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN memory_requested_through BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN memory_claim CHAR(36),
    ADD COLUMN memory_check_after DATETIME(6),
    ADD COLUMN summary_claim CHAR(36),
    ADD COLUMN summary_check_after DATETIME(6),
    ADD INDEX memory_maintenance_due (memory_check_after, last_activity_at),
    ADD INDEX summary_maintenance_due (summary_check_after, last_activity_at);

CREATE TABLE conversation_active_turns (
    session_key VARCHAR(259) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    turn_id CHAR(36) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (session_key, turn_id),
    INDEX expired_turns (expires_at)
) CHARACTER SET utf8mb4;
