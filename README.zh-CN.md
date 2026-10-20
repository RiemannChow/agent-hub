# agent-hub

> 基于 **AgentScope Java 2.0 + Spring Boot WebFlux + SeekDB** 构建的对话式数据分析智能体，演示清晰的 DDD 分层架构、ReAct 编排、**A2UI** 结构化卡片，以及混合检索（向量 + 全文 + 标量）。

🇬🇧 English documentation: [README.md](README.md)

## 功能特性

- **ReAct 编排**：基于 AgentScope 2.0 的 `ReActAgent`，由单一混元（`hy4`）大模型通过腾讯云 LKEAP 的 Anthropic 兼容网关驱动。
- **A2UI（Agent-to-UI）**：工具返回带 `type` 字段的结构化 JSON（`hybrid` / `chart` / `table`），前端渲染为原生卡片（柱状图/饼图/混合检索面板），并约束大模型不在文本里重复罗列结果。
- **混合检索**：本地哈希 Embedder 的向量检索 + 全文 + 标量过滤，图书检索用 RRF（Reciprocal Rank Fusion）融合精排；对话记忆用向量近邻召回。
- **双记忆机制**：短期记忆（会话内历史）+ 长期记忆（异步 `MemoryExtractor` 抽取稳定事实，跨会话持久化并语义召回注入上下文）。
- **可切换行业人格**：仅替换 `AgentPersona` 即可服务不同领域（电商问数 / OceanBase 知识图谱问答）。
- **多 profile 配置**：`seekdb`（默认）、`mongo`、`oceanbase`。
- **认证**：手机号+验证码、微信扫码登录；会话存于 MongoDB，由 WebFilter 统一拦截。

## 整体架构

代码遵循严格的 **DDD 分层**（依赖方向始终向内，绝不反向）：

![agent-hub DDD 架构图](doc/architecture.png)

上图为依据实际包结构绘制的分层图：`presentation → application → domain ← infrastructure`，外部系统仅由基础设施层适配器访问。

### ReAct 事件流

`ReActAgent` 产出 31 种强类型事件，`AgentEventMapper` 将其收敛为 5 种领域事件，经 SSE 推送到浏览器：

`Thinking` → `ToolCalling(toolName, args)` → `ToolResult(toolName, result)` → `Token`（流式回答）→ `Completed`

### 工具集（seekdb profile）

| 工具                        | 用途                                                     |
| ------------------------- | ------------------------------------------------------ |
| `seek_sql_query`          | 在 SeekDB 执行 SELECT；两列聚合结果自动渲染为柱状图（A2UI `type: chart`）。 |
| `seek_hybrid_search`      | 对历史对话/知识库做语义混合检索。                                      |
| `seek_book_search`        | 图书混合检索（向量 + 全文 + 标量），返回 A2UI `type: hybrid` 卡片。        |
| `seek_book_count_by_year` | 按年份统计图书数量，渲染柱状/饼图（`type: chart`）。                      |
| `seek_fork_sandbox`       | 为实验性写操作 fork 一个 COW 沙箱库。                               |
| `save_memory`             | 把稳定事实写入长期记忆。                                           |

### A2UI（Agent-to-UI）

工具不把结果以 markdown 散文直接输出，而是返回一个带 `type` 字段的**类型化 JSON 信封**。浏览器将其挂载为原生卡片，而非重新解析文本，因此结果始终保持结构化，并且提示词明确要求大模型不要在正文里重复罗列这些结果。

支持的卡片类型：

| `type`   | 渲染为            | 由谁产出                                      |
| -------- | ----------------- | --------------------------------------------- |
| `chart`  | 柱状 / 饼图（纯 CSS） | `seek_sql_query`（两列聚合）、`seek_book_count_by_year` |
| `hybrid` | 混合检索面板（向量 + 全文 + 标量命中） | `seek_book_search`、`seek_hybrid_search`      |
| `table`  | Markdown / HTML 表格 | 标量查询结果                                  |

前端（`src/main/resources/static/chat.html`）通过 `isUiJson()` 识别信封，并用 `addUiBlock()` 挂载为卡片。**不依赖任何图表库**——柱状图用 `div` 高度，饼图用 CSS `conic-gradient`，完全自包含。

![A2UI 渲染卡片](doc/A2UI.png)

### 混合检索

检索融合三种信号并统一排序：

- **向量** —— 本地哈希 `LocalEmbedder` 生成嵌入，按 L2 距离召回最近邻。
- **全文** —— 对文档文本（书名 + 作者）做 `LIKE` / `$contains` 匹配。
- **标量** —— 对 `year`、`genre`、`price`、`user_rating`、`reviews` 等字段做结构化过滤。

图书检索用 **RRF（Reciprocal Rank Fusion，倒数排名融合）** 融合向量 + 全文 + 标量；对话记忆则用向量最近邻召回。该流程对离线友好：嵌入在导入时（`BookDataImporter`）一次性算好，因此查询阶段无需任何外部向量服务。（设计细节见 [`doc/hybrid-search-architecture.html`](doc/hybrid-search-architecture.html)。）

聊天界面中渲染的混合检索卡片：

![混合检索界面](doc/hybrid-search-view.png)

SeekDB 中底层数据模型（图书元数据 + 预计算向量嵌入）：

![混合检索数据模型](doc/hybrid-search-db.png)

## 技术栈

- Java 21、Spring Boot 3.3.4（WebFlux，响应式）
- AgentScope Java 2.0.3（`io.agentscope`）
- OceanBase / SeekDB JDBC、MongoDB 响应式驱动、Neo4j Java 驱动
- Reactor（projectreactor）
- 前端：原生 HTML/CSS/JS（无框架）

## 环境要求

- JDK 21
- Maven 3.9+
- Docker + Docker Compose（用于 SeekDB、MongoDB、Neo4j）

## 构建与运行

### 1. 启动基础设施

```bash
docker-compose up -d
```

启动 SeekDB（`:2881`）、MongoDB（`:27017`）、Neo4j（`:7687`），并执行 `plugins/seekdb-init/` 下的初始化 SQL。

### 2. 配置模型密钥

LLM API Key **绝不**入库。以环境变量方式提供：

```bash
export AGENT_MODEL_API_KEY=sk-xxxxxxxxxxxxxxxx
```

（完整变量见 `.env.example`。）

### 3. 构建

```bash
mvn clean package -DskipTests
```

### 4. 运行

```bash
java -jar target/agent-hub-1.0.0.jar
# 或开发期直接：
mvn spring-boot:run
```

### 5. 打开界面

访问 `http://localhost:8080/chat.html`（先用验证码或微信扫码登录）。

### Profile 切换

| Profile      | 说明                           |
| ------------ | ---------------------------- |
| `seekdb`（默认） | 基于 SeekDB + MongoDB 的电商问数。   |
| `mongo`      | 同样的业务，MongoDB 作为主存储。         |
| `oceanbase`  | 基于 Neo4j 的 OceanBase 知识图谱问答。 |

```bash
java -jar target/agent-hub-1.0.0.jar --spring.profiles.active=oceanbase
```

## 配置项

| 变量                     | 必填           | 默认值                                                  | 说明                        |
| ---------------------- | ------------ | ---------------------------------------------------- | ------------------------- |
| `AGENT_MODEL_API_KEY`  | ✅            | —                                                    | 混元 / LKEAP API Key。       |
| `AGENT_MODEL_BASE_URL` | ❌            | `https://api.lkeap.cloud.tencent.com/plan/anthropic` | Anthropic 兼容网关。           |
| `AGENT_MODEL_NAME`     | ❌            | `hy4-preview`                                        | 模型名。                      |
| `NEO4J_URI`            | ❌（oceanbase） | `bolt://localhost:7687`                              | Neo4j bolt 地址。            |
| `NEO4J_USER`           | ❌            | `neo4j`                                              | Neo4j 用户。                 |
| `NEO4J_PASSWORD`       | ❌            | `oceanbase123`                                       | Neo4j 密码（本地演示）。           |
| `SEEKDB_PASSWORD`      | ❌            | （空）                                                  | SeekDB/OceanBase JDBC 密码。 |

## 项目结构

```
agent-hub/
├── src/main/java/com/riemann/agent/
│   ├── presentation/   # HTTP/SSE 控制器、认证、静态 UI
│   ├── application/    # 用例：ConversationAppService、MemoryExtractor、AgentEventMapper
│   ├── domain/         # 模型与端口：Conversation、Message、AgentEvent、AgentPersona、*Repository
│   └── infrastructure/ # 适配器：AgentToolkit（@Tool）、SeekDb 仓储、HybridSearch、LocalEmbedder、config
├── src/main/resources/ # application.yml、static/chat.html（A2UI 渲染器）
├── plugins/            # 可插拔组件
│   ├── seekdb-init/    # SeekDB 初始化 SQL（docker 挂载到 /init）
│   ├── neo4j/          # Neo4j（OceanBase 知识图谱）本地启动脚本
│   └── oceanbase-kg/   # （子工程）Python 爬虫 + 知识图谱构建
├── doc/                # 架构图（architecture.png）与专项设计文档
├── sql/                # 种子数据与 SQL 脚本（bestsellers_with_categories.csv、schema-seekdb.sql、seed-ecommerce.sql）
├── docker-compose.yml  # SeekDB + MongoDB + Neo4j
└── pom.xml
```

## 子工程

- **`plugins/oceanbase-kg/`** — 爬取 OceanBase 文档/代码/社区并构建 Neo4j 知识图谱的 Python 工具集，详见其 `requirements.txt` / `.env.example`。

## 许可证

[Apache License 2.0](LICENSE)。
