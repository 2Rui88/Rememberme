CREATE TABLE conversations (
    session_key VARCHAR(259) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    summary_through BIGINT NOT NULL DEFAULT 0,
    summary MEDIUMTEXT
) CHARACTER SET utf8mb4;

CREATE TABLE conversation_messages (
    session_key VARCHAR(259) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    payload JSON NOT NULL,
    PRIMARY KEY (session_key, sequence_no),
    FOREIGN KEY (session_key) REFERENCES conversations (session_key)
) CHARACTER SET utf8mb4;
