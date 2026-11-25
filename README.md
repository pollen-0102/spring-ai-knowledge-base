# spring-ai-rag-knowledge-base

基于 **Spring AI + DeepSeek + 本地 ONNX Embedding** 的轻量化 RAG 知识库。
首版把「上传 → 解析 → 分块 → 向量化 → 稠密 + BM25 混合检索 → 带引用流式回答」整条链路跑通，向量层用 Spring AI 的 `VectorStore` 抽象 + `SimpleVectorStore`，后续可平滑切换到 Milvus。

> 完整设计文档见 `spring-ai-rag-knowledge-base-开发文档.md`。本 README 记录的是**首版可运行实现**与设计文档之间的差异。

---

## 1. 当前能力

| 能力 | 状态 |
| --- | --- |
| 知识库创建 / 列表 / 改名 / 软删 | ✅ |
| PDF / DOCX / MD / TXT 上传 | ✅ |
| 异步解析 → 分块 → 向量化 → 索引（状态机 + 重试） | ✅ |
| PDF 页级文本抽取与页码引用 | ✅ |
| 稠密向量检索（cosine） | ✅ |
| 中文 BM25 关键词检索 | ✅ |
| RRF 融合 + 相邻块去重 | ✅ |
| SSE 流式回答 + 引用 + 置信度 + 拒答 | ✅ |
| 多轮会话历史（最近 3 轮） | ✅ |
| Prompt 模板存库 + 版本化 + 预览 | ✅ |
| 检索日志 / token 估算 / P95 延迟 / 零命中统计 | ✅ |
| 向量索引缺失时从 PostgreSQL 自动重建 | ✅ |
| 对账接口（PG chunk ↔ 向量索引） | ✅ |
| Milvus 2.x 混合检索 | ⏭ 第二阶段（接口已预留） |
| 用户级 ACL / JWT | ⏭ 第二阶段（`acl_scope` 字段已预留） |

---

## 2. 技术栈

| 组件 | 选型 |
| --- | --- |
| JDK | 17 |
| Spring Boot | 3.4.13 |
| Spring AI | 1.0.0（DeepSeek starter + Transformers ONNX starter） |
| 对话模型 | DeepSeek `deepseek-chat`（OpenAI 兼容，官方 starter） |
| Embedding | 本地 ONNX `bge-small-zh-v1.5`（512 维，约 91MB） |
| 向量索引 | Spring AI `SimpleVectorStore`（内存 + JSON 持久化，可替换 Milvus） |
| 关键词检索 | 自研中文 BM25（unigram + bigram，免 Lucene 依赖） |
| 业务库 | PostgreSQL 16（Docker，端口 5433） |
| 文档解析 | PDFBox 3（PDF 页级） + Apache Tika 3（DOCX） |
| 迁移 | Flyway |
| 前端 | 单页原生 HTML/CSS/JS（静态资源，无构建步骤） |

---

## 3. 快速启动（Windows / Git Bash）

### 3.1 前置条件

- JDK 17+（本机可用 `C:\Users\HD_PAN\.jdks\ms-17.0.15`）
- Docker Desktop（已启动）
- DeepSeek API Key（没有也可以先用 mock profile）

### 3.2 启动 PostgreSQL

```bash
docker compose up -d postgres
```

> 如果本机已经有一个名为 `rag-pg` 的容器占用 5433，先 `docker rm -f rag-pg`，或直接复用已有容器（库名/账号/密码需为 `rag/rag/rag`）。
> 注意：本机原有的 PostgreSQL 12 在 5432，本项目使用 **5433**，不会冲突。

### 3.3 下载本地 Embedding 模型（首次）

```bash
bash scripts/download-model.sh
```

国内网络走 `hf-mirror.com`，约 91MB。模型目录 `models/` 已加入 `.gitignore`。

### 3.4 配置 DeepSeek Key

推荐用本地 `.env.local`（已被 `.gitignore` 忽略，Spring Boot 会自动导入）：

```bash
cp .env.example .env.local
# 编辑 .env.local，填入：
# LLM_API_KEY=sk-your-deepseek-key
# LLM_BASE_URL=https://api.deepseek.com
# LLM_MODEL=deepseek-chat
```

也可以直接用环境变量：

```bash
export LLM_API_KEY=sk-your-deepseek-key
```

PowerShell：

```powershell
$env:LLM_API_KEY="sk-your-deepseek-key"
```

不想用真实模型时，可以跳过 Key，改用 Mock 模式：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=mock
```

### 3.5 启动应用

```bash
# 先设置 JAVA_HOME 指向 JDK 17
export JAVA_HOME="C:/Users/HD_PAN/.jdks/ms-17.0.15"

./mvnw spring-boot:run
```

打开 **http://localhost:8080**。

> 首次启动 Maven 会下载 Spring AI / Tika / ONNX 依赖，约几分钟。ONNX 模型加载成功后会看到
> `Loaded vector store ...` 或 `Built BM25 index ...` 日志。

### 3.6 演示步骤

1. 左侧「新建知识库」，例如 `ERP 需求库`。
2. 上传 `samples/erp-requirements.md`（或你自己的 PDF/DOCX）。
3. 等待文档状态从 `待处理 → 解析中 → 分块中 → 向量化中 → 索引中 → 就绪`。
4. 提问：
   - `采购订单审批流有几级？`
   - `退货入库是否需要质检？`
   - `供应商准入需要哪些步骤？`
5. 回答下方会显示引用卡片（文档名 + 页码 + 原文片段 + 相似度分）。

### 3.7 一键冒烟测试

应用启动后，另开一个终端：

```bash
bash scripts/smoke-test.sh
```

脚本会自动：健康检查 → 建库 → 上传 `samples/erp-requirements.md` → 等待 READY → 打印分块 → SSE 问答 → 统计。
Windows Git Bash 下脚本已设置 `PYTHONUTF8=1`，中文不会乱码。

---

## 4. 配置说明

`src/main/resources/application.yml` 关键配置：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/rag
  ai:
    model:
      chat: deepseek
      embedding: transformers
    deepseek:
      api-key: "${LLM_API_KEY:sk-not-configured}"
      base-url: "${LLM_BASE_URL:https://api.deepseek.com}"
      chat:
        options:
          model: "${LLM_MODEL:deepseek-chat}"
    embedding:
      transformer:
        onnx:
          model-uri: "file:./models/bge-small-zh/model.onnx"
        tokenizer:
          uri: "file:./models/bge-small-zh/tokenizer.json"

rag:
  retrieval:
    dense-top-k: 20
    sparse-top-k: 20
    fuse-top-n: 5
    rrf-k: 60
    score-threshold: 0.30
  ingestion:
    batch-size: 16
    max-retry: 3
    default-chunk-size: 400
    max-chunk-chars: 500
```

- **Embedding 维度**：`bge-small-zh-v1.5` = 512。知识库表里记录了 `embedding_dim`，换模型时必须新建知识库/集合（Milvus 不允许改维度）。
- **Mock 模式**：`application-mock.yml` 把 `spring.ai.model.chat` 设为 `none`，并注册本地 `MockChatModel`。它只用于验证 SSE / 引用 / 会话落库，不产生真实回答。
- **数据目录**：上传文件在 `data/uploads/`，向量快照在 `data/simple-vectors.json`；都在 `.gitignore` 中。
- **模型未下载**：应用启动会失败（TransformersEmbeddingModel 初始化时读不到 ONNX）。先跑 `scripts/download-model.sh`。

---

## 5. 核心设计

### 5.1 分层

```
Web        KbController / DocumentController / ChatController / PromptController / StatsController / AdminController
应用层      KbService / DocumentService / IngestPipeline / RetrievalService / ChatService / PromptService
领域层      SentenceChunker / RrfFusion / CitationBuilder / ConfidenceCalculator / ChineseTokenizer
基础设施    ChatClient(DeepSeek) / EmbeddingModel(ONNX) / SimpleVectorStore / Tika / PDFBox / JdbcTemplate
```

### 5.2 入库链路

```
上传 → 存盘 + sha256 去重 → document(PENDING)
  → 异步(PARSING): PDFBox/Tika 解析
  → CHUNKING: 按中文句号/问号/分号切句，~600 字/块，overlap 2 句，单块 ≤ 2000 字
  → EMBEDDING/INDEXING: 写 chunk(PG) + 批量写 SimpleVectorStore
  → READY: 回写 chunk_count / indexed_count / char_count
  → 失败: 指数退避重试 3 次 → FAILED(错误落库)
```

**向量主键确定性**：`md5(docId + ":" + chunkIndex + ":" + sha256(content))`，重复入库/重建天然幂等。

### 5.3 检索链路

```
问题
 ├─ 稠密：SimpleVectorStore cosine topK=20（kb_id 过滤）
 ├─ 稀疏：内存 BM25（中文 unigram + bigram）topK=20
 └─ RRF(k=60) 融合 → 相邻块去重 → topN=5
    → 阈值判定（top dense < threshold 且无稀疏命中 → 拒答）
    → 组装 [1] 来源：xxx（第 N 页）上下文
    → DeepSeek 流式生成
    → citation / confidence / done 事件
```

**置信度**（服务端计算，不依赖模型自评）：

```
confidence = 0.5 * topFusedScoreNorm + 0.3 * citationCoverage + 0.2 * agreement
```

### 5.4 为什么首版不用 Milvus

设计文档里 Milvus 是主方案；首版为了**最低时间成本跑通**，先用了 `SimpleVectorStore` + 自研 BM25：

- 不需要额外拉 Milvus/etcd/MinIO 镜像，不需要等 Milvus 启动；
- 保留 Spring AI `VectorStore` 抽象与 `RetrievalService` 接口，稠密/稀疏两路可独立替换；
- PostgreSQL 仍是唯一事实源，向量文件可随时删除并从 `chunk` 表重建（`VectorRebuildRunner` + 对账接口已验证）。

第二阶段切换到 Milvus 时，只需要替换：
- `DenseSearcher` → Milvus `VectorStore` / `MilvusClientV2` ANN；
- `Bm25Searcher` → Milvus BM25 `hybridSearch`（或保留本地 BM25 做兜底）；
- `IngestPipeline` 的向量写入 → Milvus upsert；
- `AdminController` 的 health/reconcile → Milvus 集合实体数 / loadState。

---

## 6. 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/kbs` | 创建知识库 |
| GET | `/api/v1/kbs` | 知识库列表（含文档数/分块数） |
| PATCH | `/api/v1/kbs/{id}` | 改名/调 topK/阈值 |
| DELETE | `/api/v1/kbs/{id}` | 软删 + 删向量 |
| POST | `/api/v1/kbs/{kbId}/documents` | 上传（multipart `file`） |
| GET | `/api/v1/kbs/{kbId}/documents` | 文档列表（`status` / `page` / `size`） |
| GET | `/api/v1/documents/{id}` | 文档详情 |
| GET | `/api/v1/documents/{id}/chunks` | 分块预览（调参用） |
| POST | `/api/v1/documents/{id}/reindex` | 重建索引 |
| DELETE | `/api/v1/documents/{id}` | 删除文档 + 向量 |
| POST | `/api/v1/kbs/{kbId}/chat` | SSE 流式问答 |
| GET | `/api/v1/sessions/{id}/messages` | 会话历史 |
| GET | `/api/v1/kbs/{kbId}/prompts` | Prompt 模板列表 |
| PUT | `/api/v1/prompts/{id}` | 更新 Prompt（版本 +1） |
| POST | `/api/v1/prompts/{id}/preview` | 渲染预览 |
| GET | `/api/v1/stats/overview` | 调用量 / token / P95 / 拒答率 |
| GET | `/api/v1/stats/top-missed` | 零命中 Top10 |
| GET | `/api/v1/admin/milvus/health` | 向量索引健康 |
| POST | `/api/v1/admin/milvus/reconcile` | PG ↔ 向量索引对账/修复 |

SSE 事件序列：

```
event: meta      {requestId, sessionId, denseHits, sparseHits, fusedHits, vectorStore}
event: delta     {text}
event: citation  {index, docId, chunkId, docName, page, snippet, score, source}
event: done      {messageId, sessionId, confidence, refused, usage}
event: error     {code, message}
```

---

## 7. 验证命令

```bash
# 健康检查
curl -s http://localhost:8080/actuator/health

# 创建知识库
curl -s -X POST http://localhost:8080/api/v1/kbs \
  -H 'Content-Type: application/json' \
  -d '{"name":"ERP 需求库","description":"演示"}'

# 上传示例文档（假设 kbId=1）
curl -s -X POST http://localhost:8080/api/v1/kbs/1/documents \
  -F 'file=@samples/erp-requirements.md'

# 查看文档状态
curl -s 'http://localhost:8080/api/v1/kbs/1/documents'

# 查看分块
curl -s 'http://localhost:8080/api/v1/documents/1/chunks?size=5'

# SSE 问答（-N 关闭缓冲）
curl -N -X POST http://localhost:8080/api/v1/kbs/1/chat \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"question":"采购订单审批流有几级？","topK":5}'

# 向量索引健康 / 对账
curl -s http://localhost:8080/api/v1/admin/milvus/health
curl -s -X POST http://localhost:8080/api/v1/admin/milvus/reconcile
```

单元测试（无需数据库 / 模型）：

```bash
./mvnw test
```

---

## 8. 与设计文档的差异（首版取舍）

| 设计文档 | 首版实现 | 原因 |
| --- | --- | --- |
| Milvus 2.5 稠密 + BM25 hybrid | SimpleVectorStore + 自研中文 BM25 + RRF | 今天可运行；接口已隔离，第二阶段替换 |
| PGVector 降级 | 未实现 | 首版不需要第二套向量库 |
| Ollama 本地 LLM | DeepSeek API | 避免下载 4.7GB 模型，效果更好 |
| Ollama bge-m3（1024 维） | 本地 ONNX bge-small-zh（512 维） | 免 Key、免 Ollama，约 91MB；维度已落在 kb 表 |
| 多租户 / ACL | `acl_scope` 字段预留，默认 `ALL` | 首版单用户演示 |
| Query 改写 | 未实现（直接把原问题送检索） | 降低 LLM 调用次数与延迟；多轮历史已进 Prompt |
| Rerank（BGE-reranker） | 未实现 | P2，先保证链路 |
| Flyway + PostgreSQL 16 | ✅ | 与设计一致 |
| 引用 / 置信度 / 拒答 / 日志 | ✅ | 服务端计算，见上文 |

---

## 9. 已知限制

- **向量索引是单机内存 JSON**：适合演示与中小规模；数据量大时切换到 Milvus（第二阶段）。
- **BM25 索引全量加载**：每个知识库首次检索时从 PG 加载 chunk 建索引，之后缓存 60s；入库/删除会主动失效。50 万 chunk 级别需要换 Lucene / Milvus BM25。
- **PDF 扫描件不支持**：无文本层会明确 `FAILED`，OCR 二期。
- **DOCX/MD/TXT 无页码**：引用显示文档名，`page` 为 `null`；PDF 有页码。
- **token 为估算值**：流式场景未拿到完整 usage，按字符数估算（中文近似 1 字 1 token），`done` 事件里标记 `estimated: true`。
- **DeepSeek Key 未配置时**：默认 profile 仍能启动，但问答会返回 `50301 / 401`；请设置 `LLM_API_KEY` 或使用 mock profile。
- **`score_threshold` 的经验值**：bge-small-zh 的 cosine 分布与 bge-m3 不同，首版默认 0.30；请用自己的文档调参。
- **Windows 控制台中文日志**：可能显示乱码，不影响功能；IDE 或 UTF-8 终端正常。

---

## 10. 目录结构

```
spring-ai-rag-knowledge-base
├── pom.xml
├── docker-compose.yml                 # postgres（默认）+ milvus stack（profile）
├── scripts/download-model.sh          # 下载 ONNX embedding 模型
├── samples/                           # 演示文档
├── models/                            # 本地 ONNX 模型（gitignored）
├── data/                              # 上传文件 + 向量快照（gitignored）
└── src/main/java/com/example/rag
    ├── RagApplication.java
    ├── common/                        # ApiResponse / BizException / ErrorCode / GlobalExceptionHandler
    ├── config/                        # RagProperties / AiConfig / VectorStoreManager / VectorRebuildRunner / MockChatModel
    ├── kb/
    ├── document/
    │   ├── parser/                    # PDFBox + Tika
    │   ├── chunker/                   # SentenceChunker
    │   ├── pipeline/                  # IngestPipeline
    │   ├── storage/                   # FileStorage
    │   └── ...
    ├── retrieval/                     # DenseSearcher / Bm25Searcher / RrfFusion / RetrievalService
    ├── chat/                          # ChatService / PromptService / CitationBuilder / ConfidenceCalculator
    ├── prompt/
    ├── stats/
    └── admin/
```

---

## 11. 第二阶段：切 Milvus

1. 启动 Milvus 栈：

   ```bash
   docker compose --profile milvus up -d
   ```

   本机已有 `milvusdb/milvus:v2.6.24`、etcd、MinIO 镜像，可直接复用。

2. 先做 M0 冒烟：用 `MilvusClientV2` 建带 `Function(BM25)` 的集合，插入 3 条，`hybridSearch` 能返回，`describeCollection` / `getCollectionStats` 能读。
3. 新建 `MilvusVectorStore` 适配 `DenseSearcher`，`Bm25Searcher` 切到 Milvus `text` 字段 + `BM25`，融合仍走 `RRF`。
4. 用 `rag.vector-store.type=milvus` 装配；保留 Simple 作为降级 profile。
5. 对账：`/api/v1/admin/milvus/reconcile` 改为 PG chunk ↔ Milvus 实体数比对。

---

## 12. 已验证（2026-11-25，本机）

- JDK 17.0.15 + Maven 3.6.3（Wrapper 已生成）+ Spring Boot 3.4.13 + Spring AI 1.0.0。
- PostgreSQL 16（Docker，5433）+ Flyway V1 建表成功。
- ONNX `bge-small-zh-v1.5`（512 维）加载成功；示例 MD 解析为 3 个分块并完成向量化。
- 稠密 3 / BM25 3 / RRF 融合 2；SSE `meta → delta → citation → done` 正常。
- 引用分数为真实 cosine（0.73 / 0.66）；置信度服务端计算。
- 无关问题在 `scoreThreshold=0.99` 下正确拒答（`refused=true`）。
- 重复上传幂等（`duplicated=true`）；PG chunk 与向量索引对账 3/3、4/4 一致。
- 两个知识库交叉验证：医疗库提问只命中 `medical-guidelines.md`，无越库。
- `samples/erp-requirements.pdf`（PDFBox 生成的中文 2 页 PDF）解析为 pageNo 1/2，引用返回 `erp-requirements.pdf · page 1 · cosine 0.729`。
- **删除向量快照后重启**：`VectorRebuildRunner` 从 PostgreSQL 的 6 个 chunk 自动重建 6 条向量，健康检查 6/6 一致。
- `/api/v1/stats/overview` 返回调用量、token、P95、拒答率。
- Mock 模式与**真实 DeepSeek 调用均已验证**：真实回答正确引用 `erp-requirements.md`（`[1]`），置信度 0.77，`deepseek-chat` 耗时约 2.1s、1298 tokens（估算）；`retrieval_log.model = deepseek-chat`。
- 应用当前以默认 profile（真实 DeepSeek）运行在 8080；`.env.local` 已配置且被 `.gitignore` 忽略。
- 用户已通过 UI 上传自己的 PDF（`java开发-潘泓达-本.pdf`，19 个分块，READY），真实文档入库与前端上传链路正常。

---

## 13. 安全提醒

- `LLM_API_KEY` 只放环境变量或本地 `.env.local`，**不要提交**；`.gitignore` 已忽略 `.env` / `.env.local`。
- 演示环境的 PostgreSQL 账号 `rag/rag` 仅用于本地，不要暴露到公网。
- 上传文件大小限制 50MB；生产环境应加病毒扫描、MinIO/OSS 存储与按用户限流。
