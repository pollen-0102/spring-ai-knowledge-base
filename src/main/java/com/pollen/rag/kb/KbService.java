package com.pollen.rag.kb;

import com.pollen.rag.common.BizException;
import com.pollen.rag.common.ErrorCode;
import com.pollen.rag.config.VectorStoreManager;
import com.pollen.rag.document.ChunkRepository;
import com.pollen.rag.retrieval.Bm25Searcher;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KbService {

    private static final String DEFAULT_EMBEDDING_MODEL = "bge-small-zh-v1.5";
    private static final int DEFAULT_EMBEDDING_DIM = 512;

    private final KbRepository kbs;
    private final ChunkRepository chunks;
    private final VectorStoreManager vectors;
    private final Bm25Searcher bm25;

    public KbService(KbRepository kbs, ChunkRepository chunks,
                     VectorStoreManager vectors, Bm25Searcher bm25) {
        this.kbs = kbs;
        this.chunks = chunks;
        this.vectors = vectors;
        this.bm25 = bm25;
    }

    public KbView create(KbDtos.CreateKbRequest req) {
        String embeddingModel = blankToDefault(req.embeddingModel(), DEFAULT_EMBEDDING_MODEL);
        int dim = req.embeddingDim() != null ? req.embeddingDim() : DEFAULT_EMBEDDING_DIM;
        int chunkSize = req.chunkSize() != null ? req.chunkSize() : 400;
        int overlap = req.chunkOverlap() != null ? req.chunkOverlap() : 2;
        int topK = req.topK() != null ? req.topK() : 5;
        double threshold = req.scoreThreshold() != null ? req.scoreThreshold() : 0.30;
        long id = kbs.insert(req.name().trim(), req.description(), embeddingModel, dim,
                chunkSize, overlap, topK, threshold);
        return get(id);
    }

    public List<KbView> list() {
        return kbs.list();
    }

    public KbView get(long id) {
        return kbs.list().stream().filter(k -> k.id() == id).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + id));
    }

    public Kb require(long id) {
        return kbs.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + id));
    }

    public KbView update(long id, KbDtos.UpdateKbRequest req) {
        Kb kb = require(id);
        String name = req.name() != null && !req.name().isBlank() ? req.name().trim() : kb.name();
        String description = req.description() != null ? req.description() : kb.description();
        int chunkSize = req.chunkSize() != null ? req.chunkSize() : kb.chunkSize();
        int overlap = req.chunkOverlap() != null ? req.chunkOverlap() : kb.chunkOverlap();
        int topK = req.topK() != null ? req.topK() : kb.topK();
        double threshold = req.scoreThreshold() != null ? req.scoreThreshold() : kb.scoreThreshold();
        kbs.update(id, name, description, chunkSize, overlap, topK, threshold);
        return get(id);
    }

    public void delete(long id) {
        require(id);
        List<String> vectorIds = chunks.vectorIdsByKb(id);
        vectors.delete(vectorIds);
        vectors.save();
        bm25.invalidate(id);
        kbs.softDelete(id);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
