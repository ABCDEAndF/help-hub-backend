CREATE TABLE assistant_conversations (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_conversation_user_time (user_id, updated_at)
);

CREATE TABLE assistant_messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id CHAR(36) NOT NULL,
    role ENUM('USER', 'ASSISTANT', 'TOOL') NOT NULL,
    content TEXT NOT NULL,
    tool_name VARCHAR(100) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
      REFERENCES assistant_conversations(id) ON DELETE CASCADE,
    INDEX idx_messages_conversation (conversation_id, id)
);

