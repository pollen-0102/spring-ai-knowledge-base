package com.pollen.rag.kb;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class KbRepository {

    private static final RowMapper<Kb> KB_MAPPER = (rs, rowNum) -> new Kb(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("embedding_model"),
            rs.getInt("embedding_dim"),
            rs.getString("milvus_collection"),
            rs.getInt("chunk_size"),
            rs.getInt("chunk_overlap"),
            rs.getInt("top_k"),
            rs.getDouble("score_threshold"),
            rs.getBoolean("deleted"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
    );

    private static final RowMapper<KbView> KB_VIEW_MAPPER = (rs, rowNum) -> new KbView(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("embedding_model"),
            rs.getInt("embedding_dim"),
            rs.getInt("chunk_size"),
            rs.getInt("chunk_overlap"),
            rs.getInt("top_k"),
            rs.getDouble("score_threshold"),
            rs.getLong("document_count"),
            rs.getLong("chunk_count"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    private final JdbcTemplate jdbc;

    public KbRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String name, String description, String embeddingModel, int embeddingDim,
                       int chunkSize, int chunkOverlap, int topK, double scoreThreshold) {
        Long id = jdbc.queryForObject("""
                INSERT INTO kb (name, description, embedding_model, embedding_dim,
                                chunk_size, chunk_overlap, top_k, score_threshold)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, name, description, embeddingModel, embeddingDim,
                chunkSize, chunkOverlap, topK, scoreThreshold);
        return id == null ? -1 : id;
    }

    public Optional<Kb> findById(long id) {
        return jdbc.query("SELECT * FROM kb WHERE id = ? AND deleted = FALSE", KB_MAPPER, id)
                .stream().findFirst();
    }

    public List<KbView> list() {
        return jdbc.query("""
                SELECT k.id, k.name, k.description, k.embedding_model, k.embedding_dim,
                       k.chunk_size, k.chunk_overlap, k.top_k, k.score_threshold, k.created_at,
                       (SELECT count(*) FROM document d WHERE d.kb_id = k.id) AS document_count,
                       (SELECT count(*) FROM chunk c WHERE c.kb_id = k.id) AS chunk_count
                FROM kb k
                WHERE k.deleted = FALSE
                ORDER BY k.id DESC
                """, KB_VIEW_MAPPER);
    }

    public void update(long id, String name, String description, int chunkSize, int chunkOverlap,
                       int topK, double scoreThreshold) {
        jdbc.update("""
                UPDATE kb
                   SET name = ?, description = ?, chunk_size = ?, chunk_overlap = ?,
                       top_k = ?, score_threshold = ?, updated_at = now()
                 WHERE id = ? AND deleted = FALSE
                """, name, description, chunkSize, chunkOverlap, topK, scoreThreshold, id);
    }

    public void softDelete(long id) {
        jdbc.update("UPDATE kb SET deleted = TRUE, updated_at = now() WHERE id = ?", id);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM kb WHERE deleted = FALSE", Long.class);
        return n == null ? 0 : n;
    }
}
