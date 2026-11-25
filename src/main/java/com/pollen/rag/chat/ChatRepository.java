package com.pollen.rag.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatRepository {

    private static final RowMapper<ChatDtos.MessageView> MESSAGE_MAPPER = (rs, rowNum) -> new ChatDtos.MessageView(
            rs.getLong("id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getBoolean("refused"),
            rs.getString("structured"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    private static final RowMapper<ChatDtos.SessionView> SESSION_MAPPER = (rs, rowNum) -> new ChatDtos.SessionView(
            rs.getLong("id"),
            rs.getLong("kb_id"),
            rs.getString("user_id"),
            rs.getString("title"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    private final JdbcTemplate jdbc;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createSession(long kbId, String userId, String title) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chat_session (kb_id, user_id, title) VALUES (?, ?, ?) RETURNING id
                """, Long.class, kbId, userId, title);
        return id == null ? -1 : id;
    }

    public Optional<ChatDtos.SessionView> findSession(long id) {
        return jdbc.query("SELECT * FROM chat_session WHERE id = ?", SESSION_MAPPER, id)
                .stream().findFirst();
    }

    public long insertMessage(long sessionId, String role, String content, String structuredJson, boolean refused) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chat_message (session_id, role, content, structured, refused)
                VALUES (?, ?, ?, CAST(? AS JSONB), ?)
                RETURNING id
                """, Long.class, sessionId, role, content, structuredJson, refused);
        return id == null ? -1 : id;
    }

    public List<ChatDtos.MessageView> recentMessages(long sessionId, int limit) {
        return jdbc.query("""
                SELECT * FROM (
                    SELECT * FROM chat_message WHERE session_id = ? ORDER BY id DESC LIMIT ?
                ) recent ORDER BY id ASC
                """, MESSAGE_MAPPER, sessionId, limit);
    }

    public List<ChatDtos.MessageView> messages(long sessionId) {
        return jdbc.query("""
                SELECT * FROM chat_message WHERE session_id = ? ORDER BY id ASC
                """, MESSAGE_MAPPER, sessionId);
    }

    public void insertCitations(long messageId, List<Citation> citations) {
        for (Citation citation : citations) {
            jdbc.update("""
                    INSERT INTO citation (message_id, doc_id, chunk_id, page_no, snippet, score)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, messageId, citation.docId(), citation.chunkId(), citation.page(),
                    citation.snippet(), citation.score());
        }
    }

    public void insertLog(RetrievalLog log) {
        jdbc.update("""
                INSERT INTO retrieval_log (
                    request_id, session_id, kb_id, question, rewritten_query,
                    dense_hits, sparse_hits, fused_hits, top_score, used_chunks,
                    prompt_tokens, completion_tokens, total_tokens, model,
                    retrieval_ms, llm_ms, latency_ms, refused)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB),
                        ?, ?, ?, ?, ?, ?, ?, ?)
                """, log.requestId(), log.sessionId(), log.kbId(), log.question(), log.rewrittenQuery(),
                log.denseHits(), log.sparseHits(), log.fusedHits(), log.topScore(), log.usedChunksJson(),
                log.promptTokens(), log.completionTokens(), log.totalTokens(), log.model(),
                log.retrievalMs(), log.llmMs(), log.latencyMs(), log.refused());
    }

    public record RetrievalLog(String requestId,
                               Long sessionId,
                               long kbId,
                               String question,
                               String rewrittenQuery,
                               int denseHits,
                               int sparseHits,
                               int fusedHits,
                               Double topScore,
                               String usedChunksJson,
                               Integer promptTokens,
                               Integer completionTokens,
                               Integer totalTokens,
                               String model,
                               int retrievalMs,
                               int llmMs,
                               int latencyMs,
                               boolean refused) {
    }
}
