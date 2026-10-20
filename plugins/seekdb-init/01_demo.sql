-- SeekDB / OceanBase 初始化脚本
-- 由容器 INIT_SCRIPTS_PATH 在首次启动时执行，也可手动执行：
--   docker exec -i <cid> sh -c 'mysql -uroot -h127.0.0.1 -P2881 -Ddemo' < schema-seekdb.sql
--
-- 说明：本镜像（seekdb-v1.4.0.0）【支持】VECTOR 列类型与 l2_distance 向量距离函数，
-- 也支持 FULLTEXT 全文索引（CJK 需 WITH PARSER ngram）。下面的 conversation 表即承载
-- 向量混合检索所需的 embedding 列与全文索引。

CREATE DATABASE IF NOT EXISTS demo;
USE demo;

-- ============ 电商结构化数据（问数用，detail 见 seed-ecommerce.sql） ============
CREATE TABLE IF NOT EXISTS products (
    product_id VARCHAR(16) PRIMARY KEY,
    name       VARCHAR(64),
    price      INT,
    category   VARCHAR(32)
);
CREATE TABLE IF NOT EXISTS users (
    user_id VARCHAR(16) PRIMARY KEY,
    city    VARCHAR(32),
    gender  VARCHAR(4),
    age     INT,
    reg_date DATE
);
CREATE TABLE IF NOT EXISTS orders (
    order_id   VARCHAR(16) PRIMARY KEY,
    user_id    VARCHAR(16),
    product_id VARCHAR(16),
    amount     INT,
    status     VARCHAR(16),
    created_at DATE
);

-- ============ 对话 / 知识库（向量混合检索用） ============
DROP TABLE IF EXISTS message;
DROP TABLE IF EXISTS conversation;
CREATE TABLE conversation (
    conversation_id VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64),
    title           VARCHAR(255) DEFAULT 'untitled',
    content         TEXT,
    embedding       VECTOR(256),
    status          VARCHAR(32)   DEFAULT 'ACTIVE',
    agent_state     VARCHAR(64),
    updated_at      TIMESTAMP
);
-- CJK 全文索引必须使用 ngram 解析器，否则 MATCH...AGAINST 无结果
ALTER TABLE conversation ADD FULLTEXT INDEX ft_content (content) WITH PARSER ngram;

CREATE TABLE message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64),
    type            VARCHAR(32),
    content         TEXT,
    tool_name       VARCHAR(128),
    arguments       TEXT,
    created_at      TIMESTAMP,
    INDEX idx_conv (conversation_id)
);
