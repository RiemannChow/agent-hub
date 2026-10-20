-- SeekDB / OceanBase 建表语句（向量 + 全文 + 标量）
-- 兼容 MySQL 协议，可直接在 OceanBase（SeekDB 形态）中执行。

CREATE TABLE IF NOT EXISTS conversation (
    conversation_id VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64),
    title           VARCHAR(256),
    content         TEXT,
    embedding       VECTOR(768),            -- 向量列（维度按实际模型调整）
    agent_state     VARCHAR(32),
    status          VARCHAR(32) DEFAULT 'ACTIVE',
    updated_at      DATETIME
);

-- 全文索引（支撑 MATCH...AGAINST）
ALTER TABLE conversation ADD FULLTEXT INDEX ft_content (content);

CREATE TABLE IF NOT EXISTS message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64),
    type            VARCHAR(32),
    content         TEXT,
    tool_name       VARCHAR(128),
    arguments       TEXT,
    created_at      DATETIME
);
