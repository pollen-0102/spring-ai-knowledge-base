package com.pollen.rag.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class DocumentRepository {

    private static final RowMapper<DocumentEntity> MAPPER = (rs, rowNum) -> new DocumentEntity(
            rs.getLong("id"),
            rs.getLong("kb_id"),
            rs.getString("file_name"),
            rs.getString("file_type"),
            rs.getString("storage_path"),
            rs.getLong("file_size"),
            rs.getString("file_hash"),
            rs.getString("status"),
            rs.getInt("chunk_count"),
            rs.getInt("indexed_count"),
            rs.getInt("char_count"),
            rs.getString("error_msg"),
            rs.getInt("retry_count"),
            rs.getString("acl_scope"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
    );

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long kbId, String fileName, String fileType, String storagePath,
                       long fileSize, String fileHash, String aclScope) {
        Long id = jdbc.queryForObject("""
                INSERT INTO document (kb_id, file_name, file_type, storage_path, file_size, file_hash,
                                      status, acl_scope)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                RETURNING id
                """, Long.class, kbId, fileName, fileType, storagePath, fileSize, fileHash, aclScope);
        return id == null ? -1 : id;
    }

    public Optional<DocumentEntity> findById(long id) {
        return jdbc.query("SELECT * FROM document WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<DocumentEntity> findByHash(long kbId, String fileHash) {
        return jdbc.query("SELECT * FROM document WHERE kb_id = ? AND file_hash = ?", MAPPER, kbId, fileHash)
                .stream().findFirst();
    }

    public List<DocumentEntity> list(long kbId, String status, int limit, int offset) {
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM document WHERE kb_id = ?
                    ORDER BY id DESC LIMIT ? OFFSET ?
                    """, MAPPER, kbId, limit, offset);
        }
        return jdbc.query("""
                SELECT * FROM document WHERE kb_id = ? AND status = ?
                ORDER BY id DESC LIMIT ? OFFSET ?
                """, MAPPER, kbId, status, limit, offset);
    }

    public long countByKb(long kbId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM document WHERE kb_id = ?", Long.class, kbId);
        return n == null ? 0 : n;
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE document SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    public void updateParsed(long id, String status, int charCount) {
        jdbc.update("""
                UPDATE document SET status = ?, char_count = ?, updated_at = now() WHERE id = ?
                """, status, charCount, id);
    }

    public void updateChunks(long id, String status, int chunkCount) {
        jdbc.update("""
                UPDATE document SET status = ?, chunk_count = ?, updated_at = now() WHERE id = ?
                """, status, chunkCount, id);
    }

    public void updateReady(long id, int chunkCount, int indexedCount, int charCount) {
        jdbc.update("""
                UPDATE document
                   SET status = 'READY', chunk_count = ?, indexed_count = ?, char_count = ?,
                       error_msg = NULL, updated_at = now()
                 WHERE id = ?
                """, chunkCount, indexedCount, charCount, id);
    }

    public void updateFailed(long id, String errorMsg, int retryCount) {
        String safeError = errorMsg == null ? "unknown error" : errorMsg;
        if (safeError.length() > 1000) {
            safeError = safeError.substring(0, 1000);
        }
        jdbc.update("""
                UPDATE document
                   SET status = 'FAILED', error_msg = ?, retry_count = ?, updated_at = now()
                 WHERE id = ?
                """, safeError, retryCount, id);
    }

    public void updateRetryCount(long id, int retryCount) {
        jdbc.update("UPDATE document SET retry_count = ?, updated_at = now() WHERE id = ?", retryCount, id);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM document WHERE id = ?", id);
    }
}
