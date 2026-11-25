package com.pollen.rag.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

@Repository
public class ChunkRepository {

    private static final RowMapper<ChunkEntity> MAPPER = (rs, rowNum) -> new ChunkEntity(
            rs.getLong("id"),
            rs.getLong("doc_id"),
            rs.getLong("kb_id"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getString("content_hash"),
            (Integer) rs.getObject("token_estimate"),
            (Integer) rs.getObject("page_no"),
            rs.getString("section"),
            rs.getString("vector_id"),
            rs.getString("vector_status"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    private static final RowMapper<ChunkView> VIEW_MAPPER = (rs, rowNum) -> new ChunkView(
            rs.getLong("id"),
            rs.getLong("doc_id"),
            rs.getLong("kb_id"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            (Integer) rs.getObject("page_no"),
            rs.getString("vector_id"),
            rs.getString("doc_name"),
            rs.getString("acl_scope")
    );

    private final JdbcTemplate jdbc;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long docId, long kbId, int chunkIndex, String content, String contentHash,
                       int tokenEstimate, Integer pageNo, String vectorId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO chunk (doc_id, kb_id, chunk_index, content, content_hash,
                                   token_estimate, page_no, vector_id, vector_status)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS INTEGER), ?, 'PENDING')
                RETURNING id
                """, Long.class, docId, kbId, chunkIndex, content, contentHash,
                tokenEstimate, pageNo, vectorId);
        return id == null ? -1 : id;
    }

    public List<ChunkEntity> pageByDoc(long docId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM chunk WHERE doc_id = ?
                ORDER BY chunk_index LIMIT ? OFFSET ?
                """, MAPPER, docId, limit, offset);
    }

    public long countByDoc(long docId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM chunk WHERE doc_id = ?", Long.class, docId);
        return n == null ? 0 : n;
    }

    public List<ChunkView> findViewsByKb(long kbId) {
        return jdbc.query("""
                SELECT c.id, c.doc_id, c.kb_id, c.chunk_index, c.content, c.page_no, c.vector_id,
                       d.file_name AS doc_name, d.acl_scope
                  FROM chunk c
                  JOIN document d ON d.id = c.doc_id
                  JOIN kb k ON k.id = c.kb_id
                 WHERE c.kb_id = ? AND k.deleted = FALSE AND d.status <> 'FAILED'
                 ORDER BY c.doc_id, c.chunk_index
                """, VIEW_MAPPER, kbId);
    }

    public List<ChunkView> findAllViews() {
        return jdbc.query("""
                SELECT c.id, c.doc_id, c.kb_id, c.chunk_index, c.content, c.page_no, c.vector_id,
                       d.file_name AS doc_name, d.acl_scope
                  FROM chunk c
                  JOIN document d ON d.id = c.doc_id
                  JOIN kb k ON k.id = c.kb_id
                 WHERE k.deleted = FALSE AND d.status <> 'FAILED'
                 ORDER BY c.doc_id, c.chunk_index
                """, VIEW_MAPPER);
    }

    public List<String> vectorIdsByDoc(long docId) {
        return jdbc.queryForList("SELECT vector_id FROM chunk WHERE doc_id = ?", String.class, docId);
    }

    public List<String> vectorIdsByKb(long kbId) {
        return jdbc.queryForList("SELECT vector_id FROM chunk WHERE kb_id = ?", String.class, kbId);
    }

    public void markIndexed(List<Long> chunkIds) {
        updateStatus(chunkIds, "INDEXED");
    }

    public void markFailed(List<Long> chunkIds) {
        updateStatus(chunkIds, "FAILED");
    }

    private void updateStatus(List<Long> chunkIds, String status) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
        jdbc.update("UPDATE chunk SET vector_status = ? WHERE id IN (" + placeholders + ")",
                concat(status, chunkIds));
    }

    private Object[] concat(String status, List<Long> ids) {
        Object[] args = new Object[ids.size() + 1];
        args[0] = status;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        return args;
    }

    public void deleteByDoc(long docId) {
        jdbc.update("DELETE FROM chunk WHERE doc_id = ?", docId);
    }

    public long countAll() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM chunk", Long.class);
        return n == null ? 0 : n;
    }

    public long countByKb(long kbId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM chunk WHERE kb_id = ?", Long.class, kbId);
        return n == null ? 0 : n;
    }
}
