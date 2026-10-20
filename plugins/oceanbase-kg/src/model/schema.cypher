// ============================================================
// OceanBase 知识图谱 Schema：约束 / 索引 / 全文索引
// 所有语句带 IF NOT EXISTS，可重复执行（幂等）。
// ============================================================

// ---- 唯一约束（身份键）----
CREATE CONSTRAINT doc_url IF NOT EXISTS FOR (n:Document)   REQUIRE n.url IS UNIQUE;
CREATE CONSTRAINT sec_id  IF NOT EXISTS FOR (n:Section)    REQUIRE n.id  IS UNIQUE;
CREATE CONSTRAINT repo_name IF NOT EXISTS FOR (n:Repo)     REQUIRE n.name IS UNIQUE;
CREATE CONSTRAINT mod_path  IF NOT EXISTS FOR (n:Module)   REQUIRE n.path IS UNIQUE;
CREATE CONSTRAINT file_path IF NOT EXISTS FOR (n:File)     REQUIRE n.path IS UNIQUE;
CREATE CONSTRAINT concept_name IF NOT EXISTS FOR (n:Concept) REQUIRE n.name IS UNIQUE;

// ---- 属性索引（加速按名/路径查找）----
CREATE INDEX doc_title  IF NOT EXISTS FOR (n:Document) ON (n.title);
CREATE INDEX concept_name_idx IF NOT EXISTS FOR (n:Concept) ON (n.name);
CREATE INDEX class_name_idx  IF NOT EXISTS FOR (n:Class)   ON (n.name);
CREATE INDEX file_path_idx   IF NOT EXISTS FOR (n:File)    ON (n.path);

// ---- 全文索引（供 Java 侧 ob_doc_search / Cypher 检索）----
CREATE FULLTEXT INDEX obFulltext IF NOT EXISTS
  FOR (n:Document|Section|Concept|File|Class)
  ON EACH [n.title, n.text, n.name, n.path];
