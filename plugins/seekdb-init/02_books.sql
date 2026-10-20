-- SeekDB 图书混合检索示例库（Java 版落库结构）
-- 由容器 INIT_SCRIPTS_PATH 在首次启动时执行（SEEKDB_DATABASE=demo 已默认选中，
-- 这里再显式建独立的 demo_books 库，承载 Amazon 畅销书数据）。
--
-- 设计说明（对应 Python 版 pyseekdb 的 book_info collection）：
--   Python 集合 c$v1$book_info 内部用 metadata(JSON) + document + embedding(VECTOR) 三列；
--   Java 版用关系表 book_info 把 metadata 字段展开为独立列，便于用标准 SQL 做标量过滤与索引，
--   同时保留 document（用于 $contains 文本过滤）与 embedding（用于向量近邻检索）。
--   向量维度与 agent-hub 现有 LocalEmbedder 保持一致：VECTOR(256)，距离用 l2_distance。

CREATE DATABASE IF NOT EXISTS demo_books;
USE demo_books;

CREATE TABLE IF NOT EXISTS book_info (
    id           VARCHAR(16)  PRIMARY KEY,
    name         VARCHAR(500),
    author       VARCHAR(200),
    user_rating  FLOAT,
    reviews      INT,
    price        FLOAT,
    year         INT,
    genre        VARCHAR(100),
    document     TEXT,                       -- 用于 $contains 子串匹配
    embedding    VECTOR(256)                 -- LocalEmbedder 离线嵌入
);

-- 标量/元数据索引：加速 hybrid_search 中的 WHERE 过滤（对应 Python 版的 create_metadata_indexes）
CREATE INDEX idx_book_genre      ON book_info(genre);
CREATE INDEX idx_book_year       ON book_info(year);
CREATE INDEX idx_book_rating     ON book_info(user_rating);
CREATE INDEX idx_book_author     ON book_info(author);
CREATE INDEX idx_book_reviews    ON book_info(reviews);
CREATE INDEX idx_book_price      ON book_info(price);

-- 全文倒排索引：让 hybrid_search 的全文支路真正走 SeekDB 倒排索引（而非 LIKE 子串扫描）。
-- book_info.document = 书名 + 作者，内容为拉丁文本，故用默认 parser（CJK 才需要 ngram parser）。
-- 全文支路用 MATCH(document) AGAINST (? IN BOOLEAN MODE) 取相关性得分，参与粗排 + RRF 精排。
CREATE FULLTEXT INDEX ft_document ON book_info(document);
