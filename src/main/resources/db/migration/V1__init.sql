-- ============================================================
-- PostgreSQL is the source of truth.
-- Milvus / SimpleVectorStore is a derived index and can be rebuilt
-- from the chunk table at any time.
-- ============================================================

-- ============================================================
-- 本文件共 8 张表，对应整个 RAG 系统的业务数据：
--
--   kb               知识库（一组文档的容器 + 检索参数）
--   document         文档（用户上传的一个文件）
--   chunk            分块（文件切出的小段，★ 事实源核心）
--   prompt_template  Prompt 模板（问答提示词，可版本化）
--   chat_session     会话（一次对话）
--   chat_message     消息（对话里的一问/一答）
--   citation         引用（回答里的一条来源）
--   retrieval_log    检索日志（可观测性：耗时/token/命中率）
--
-- 表关系：
--   kb 1-N document 1-N chunk
--   kb 1-N prompt_template        （kb_id=0 表示全局默认模板）
--   kb 1-N chat_session 1-N chat_message 1-N citation
--   kb 1-N retrieval_log          （日志直接挂 kb）
--
-- 核心设计：chunk 表是唯一事实源，存原文但没有向量列；
-- 向量存在向量库（当前是 data/simple-vectors.json），
-- 靠 chunk.vector_id 与向量库一一对应，删了向量库可从 chunk 重建。
-- ============================================================


-- ============================================================
-- 表：kb（知识库）
-- 定位：一个知识库 = 一组文档 + 一套独立的检索参数。
-- 关键字段：
--   embedding_model/dim  用的向量模型与维度（512），换模型需重建
--   chunk_size/overlap   分块大小（400 字）与重叠（2 句），每库可调
--   top_k/score_threshold 检索取几条、拒答阈值，每库独立可调
--   deleted              软删标记（删了但数据还在，历史会话仍可查）
-- ============================================================
CREATE TABLE kb (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(128) NOT NULL,
    description       VARCHAR(512),
    embedding_model   VARCHAR(64)  NOT NULL DEFAULT 'bge-small-zh-v1.5',
    embedding_dim     INTEGER      NOT NULL DEFAULT 512,
    milvus_collection VARCHAR(64)  NOT NULL DEFAULT 'rag_chunk',
    chunk_size        INTEGER      NOT NULL DEFAULT 400,
    -- overlap is measured in sentences for SentenceChunker
    chunk_overlap     INTEGER      NOT NULL DEFAULT 2,
    top_k             INTEGER      NOT NULL DEFAULT 5,
    score_threshold   REAL         NOT NULL DEFAULT 0.30,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);


-- ============================================================
-- 表：document（文档）
-- 定位：用户上传的一个文件，一条记录对应一份文件。
-- 关键字段：
--   storage_path  文件实际存放位置（指向 data/uploads/）
--   file_hash     sha256，用于去重（配合唯一约束 uk_doc_hash）
--   status        入库状态机：
--                 PENDING → PARSING → CHUNKING → EMBEDDING
--                 → INDEXING → READY / FAILED
--   chunk_count   切出的分块总数
--   indexed_count 成功写入向量库的条数（对账时与 chunk_count 比较）
--   retry_count   入库失败重试次数（最多 3 次）
--   acl_scope     权限范围（首版默认 'ALL'，二期做权限用）
-- ============================================================
CREATE TABLE document (
    id            BIGSERIAL PRIMARY KEY,
    kb_id         BIGINT NOT NULL REFERENCES kb(id),
    file_name     VARCHAR(255) NOT NULL,
    file_type     VARCHAR(16)  NOT NULL,
    storage_path  VARCHAR(512) NOT NULL,
    file_size     BIGINT       NOT NULL,
    file_hash     CHAR(64)     NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    chunk_count   INTEGER      NOT NULL DEFAULT 0,
    indexed_count INTEGER      NOT NULL DEFAULT 0,
    char_count    INTEGER      NOT NULL DEFAULT 0,
    error_msg     VARCHAR(1024),
    retry_count   SMALLINT     NOT NULL DEFAULT 0,
    acl_scope     VARCHAR(64)  NOT NULL DEFAULT 'ALL',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_doc_hash UNIQUE (kb_id, file_hash)
);
CREATE INDEX idx_doc_kb_status ON document (kb_id, status);


-- ============================================================
-- 表：chunk（分块）★ 整个系统的核心
-- 定位：文档切出来的小段，是检索与引用的最小单位，也是唯一事实源。
--      向量库删了，能从这张表重新算向量、重建一切。
-- 关键字段：
--   content       段落完整原文（重建向量就靠它）
--   content_hash  原文 sha256，用于生成确定性 vector_id
--   chunk_index   该文档内的段落序号（0,1,2...），配合 uk_chunk 唯一
--   page_no       PDF 页码（引用「第 N 页」就靠它；MD/TXT 为 null）
--   vector_id     ★ 向量库主键，两边连接键：
--                 md5(docId + ":" + chunkIndex + ":" + contentHash)
--                 确定性生成 → 重试/重建幂等，不产生重复向量
--   vector_status 向量写入状态：PENDING / INDEXED / FAILED
-- 注意：本表没有向量列；向量的唯一归属是向量库（不是数据库）。
-- ============================================================
CREATE TABLE chunk (
    id             BIGSERIAL PRIMARY KEY,
    doc_id         BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    kb_id          BIGINT NOT NULL,
    chunk_index    INTEGER NOT NULL,
    content        TEXT    NOT NULL,
    content_hash   CHAR(64) NOT NULL,
    token_estimate INTEGER,
    page_no        INTEGER,
    section        VARCHAR(255),
    vector_id      VARCHAR(64) NOT NULL,
    vector_status  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_chunk UNIQUE (doc_id, chunk_index),
    CONSTRAINT uk_chunk_vector UNIQUE (vector_id)
);
CREATE INDEX idx_chunk_kb ON chunk (kb_id);
CREATE INDEX idx_chunk_vstatus ON chunk (vector_status);


-- ============================================================
-- 表：prompt_template（Prompt 模板）
-- 定位：问答时套用的提示词，存库以支持热更新与版本化。
-- 关键字段：
--   code           模板类型标识，如 QA_ANSWER（问答）/ QUERY_REWRITE / REFUSAL
--   kb_id          0 表示全局默认模板；>0 表示某个库专属模板
--   system_prompt  系统提示词，含 {{context}} {{question}} {{kb_name}} 等占位符
--   user_prompt    用户提示词，含 {{question}}
--   version        版本号；改模板 = 插入新 version + 旧版 enabled 置 false
--   enabled        是否启用（版本切换/回滚用）
-- ============================================================
CREATE TABLE prompt_template (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(64) NOT NULL,
    -- 0 means the global default template
    kb_id         BIGINT NOT NULL DEFAULT 0,
    name          VARCHAR(128) NOT NULL,
    system_prompt TEXT NOT NULL,
    user_prompt   TEXT NOT NULL,
    version       INTEGER NOT NULL DEFAULT 1,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_prompt UNIQUE (code, kb_id, version)
);


-- ============================================================
-- 表：chat_session（会话）
-- 定位：一次对话的容器，与 chat_message 是一对多。
-- 关键字段：
--   kb_id    在哪个知识库下进行的对话
--   user_id  提问用户（首版取 X-User-Id 请求头，默认 'demo'）
--   title    会话标题（取第一句问题的前 50 字）
-- ============================================================
CREATE TABLE chat_session (
    id         BIGSERIAL PRIMARY KEY,
    kb_id      BIGINT NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    title      VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- ============================================================
-- 表：chat_message（消息）
-- 定位：会话里的一条消息，USER（问）和 ASSISTANT（答）各一条。
-- 关键字段：
--   role       'USER' 或 'ASSISTANT'
--   content    消息正文
--   structured JSONB 结构化数据，如
--             {"citations":[...],"confidence":0.77,"refused":false}
--   refused    是否拒答（单独抽出，便于统计拒答率，免解析 JSONB）
-- ============================================================
CREATE TABLE chat_message (
    id         BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role       VARCHAR(16) NOT NULL,
    content    TEXT NOT NULL,
    structured JSONB,
    refused    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_message_session ON chat_message (session_id, id);


-- ============================================================
-- 表：citation（引用）
-- 定位：回答里的「一条」引用来源（一个回答可带多条，即多条记录）。
-- 关键字段：
--   message_id  属于哪条 ASSISTANT 消息
--   doc_id/chunk_id  引用来自哪个文档的哪个分块
--   page_no     页码（引用「第 N 页」）
--   snippet     原文片段（展示给用户）
--   score       相似度分数（cosine，用于展示可信度）
-- 说明：chat_message.structured 已冗余存了 citations，本表是关系化
--       副本，便于按「引用维度」查询（如某 chunk 被引用次数）。
-- ============================================================
CREATE TABLE citation (
    id         BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
    doc_id     BIGINT NOT NULL,
    chunk_id   BIGINT NOT NULL,
    page_no    INTEGER,
    snippet    TEXT NOT NULL,
    score      REAL NOT NULL
);
CREATE INDEX idx_citation_message ON citation (message_id);


-- ============================================================
-- 表：retrieval_log（检索日志）★ 可观测性核心
-- 定位：每次问答留一条，用于算成本、延迟、命中率、拒答率。
-- 关键字段：
--   question / rewritten_query  原问题 / 改写后问题（当前未做改写）
--   dense_hits / sparse_hits / fused_hits  三路检索各自命中数
--   top_score   融合后 top1 分数
--   used_chunks  JSONB，实际使用的 chunk：[{chunkId, score, source}]
--   prompt/completion/total_tokens  token 消耗（流式为估算值）
--   model        用的模型（deepseek-chat / mock）
--   retrieval_ms / llm_ms / latency_ms  检索/模型/总耗时
--   refused      是否拒答
-- 说明：stats 接口（调用量、token、P95 延迟、拒答率、零命中 Top10）
--       全部从本表查得。
-- ============================================================
CREATE TABLE retrieval_log (
    id                BIGSERIAL PRIMARY KEY,
    request_id        VARCHAR(64) NOT NULL,
    session_id        BIGINT,
    kb_id             BIGINT NOT NULL,
    question          TEXT NOT NULL,
    rewritten_query   TEXT,
    dense_hits        SMALLINT NOT NULL DEFAULT 0,
    sparse_hits       SMALLINT NOT NULL DEFAULT 0,
    fused_hits        SMALLINT NOT NULL DEFAULT 0,
    top_score         REAL,
    used_chunks       JSONB,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    model             VARCHAR(64),
    retrieval_ms      INTEGER,
    llm_ms            INTEGER,
    latency_ms        INTEGER,
    refused           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_log_kb_time ON retrieval_log (kb_id, created_at DESC);

-- Default global QA prompt (kb_id = 0)
INSERT INTO prompt_template (code, kb_id, name, system_prompt, user_prompt, version, enabled)
VALUES (
    'QA_ANSWER',
    0,
    '默认问答模板',
    $prompt$你是企业内部知识库助手。请严格基于【参考资料】回答问题。

规则：
1. 只能使用参考资料中的信息，不得使用常识补充、不得推测。
2. 每个结论后必须标注来源编号，格式 [1]、[2]；多条来源写作 [1][2]。
3. 若参考资料不足以回答，直接回复"根据现有资料无法确定"，并说明缺少什么信息，禁止编造。
4. 回答使用简体中文，先给结论，再给依据；涉及流程/步骤时用有序列表。
5. 不要复述参考资料原文超过 3 句，需用自己的话归纳。

【知识库】{{kb_name}}

【参考资料】
{{context}}

【历史对话】
{{history}}$prompt$,
    '{{question}}',
    1,
    TRUE
);
