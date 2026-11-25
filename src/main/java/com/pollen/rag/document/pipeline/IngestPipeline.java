package com.pollen.rag.document.pipeline;

import com.pollen.rag.common.BizException;
import com.pollen.rag.common.ErrorCode;
import com.pollen.rag.common.Hashes;
import com.pollen.rag.config.RagProperties;
import com.pollen.rag.config.VectorStoreManager;
import com.pollen.rag.document.ChunkRepository;
import com.pollen.rag.document.DocumentEntity;
import com.pollen.rag.document.DocumentRepository;
import com.pollen.rag.document.chunker.ChunkDraft;
import com.pollen.rag.document.chunker.SentenceChunker;
import com.pollen.rag.document.parser.DocumentParser;
import com.pollen.rag.document.parser.ParsedDocument;
import com.pollen.rag.document.storage.FileStorage;
import com.pollen.rag.kb.Kb;
import com.pollen.rag.kb.KbRepository;
import com.pollen.rag.retrieval.Bm25Searcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final KbRepository kbs;
    private final FileStorage storage;
    private final DocumentParser parser;
    private final SentenceChunker chunker;
    private final VectorStoreManager vectors;
    private final Bm25Searcher bm25;
    private final RagProperties props;

    public IngestPipeline(DocumentRepository documents, ChunkRepository chunks, KbRepository kbs,
                          FileStorage storage, DocumentParser parser, SentenceChunker chunker,
                          VectorStoreManager vectors, Bm25Searcher bm25, RagProperties props) {
        this.documents = documents;
        this.chunks = chunks;
        this.kbs = kbs;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.vectors = vectors;
        this.bm25 = bm25;
        this.props = props;
    }

    @Async("ingestExecutor")
    public void processAsync(long docId) {
        DocumentEntity doc = documents.findById(docId).orElse(null);
        if (doc == null) {
            return;
        }
        int maxRetry = Math.max(1, props.getIngestion().getMaxRetry());
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                ingest(doc, attempt);
                return;
            } catch (Exception e) {
                log.warn("ingest doc={} attempt {}/{} failed: {}", docId, attempt, maxRetry, e.toString());
                cleanup(doc);
                if (attempt >= maxRetry) {
                    documents.updateFailed(docId, e.getMessage(), attempt);
                    bm25.invalidate(doc.kbId());
                    safeSaveVectors();
                    return;
                }
                documents.updateStatus(docId, "PENDING");
                documents.updateRetryCount(docId, attempt);
                sleep(attempt * 1000L);
            }
        }
    }

    private void ingest(DocumentEntity doc, int attempt) {
        Kb kb = kbs.findById(doc.kbId()).orElseThrow(
                () -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + doc.kbId()));

        documents.updateStatus(doc.id(), "PARSING");
        ParsedDocument parsed = parser.parse(storage.path(doc.storagePath()), doc.fileType());
        documents.updateParsed(doc.id(), "CHUNKING", parsed.totalChars());

        int chunkSize = kb.chunkSize() > 0 ? kb.chunkSize() : props.getIngestion().getDefaultChunkSize();
        int overlap = Math.max(0, kb.chunkOverlap());
        int maxChars = Math.max(200, props.getIngestion().getMaxChunkChars());
        List<ChunkDraft> drafts = chunker.split(parsed, chunkSize, overlap, maxChars);
        if (drafts.isEmpty()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "文档未产生有效分块");
        }
        documents.updateChunks(doc.id(), "EMBEDDING", drafts.size());

        List<VecRow> rows = new ArrayList<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            String contentHash = Hashes.sha256Hex(draft.content());
            String vectorId = Hashes.md5Hex(doc.id() + ":" + draft.chunkIndex() + ":" + contentHash);
            long chunkId = chunks.insert(doc.id(), doc.kbId(), draft.chunkIndex(), draft.content(),
                    contentHash, draft.content().length(), draft.pageNo(), vectorId);
            rows.add(new VecRow(chunkId, vectorId, contentHash, draft));
        }

        documents.updateStatus(doc.id(), "INDEXING");
        int batchSize = Math.max(1, props.getIngestion().getBatchSize());
        int indexed = 0;
        for (int i = 0; i < rows.size(); i += batchSize) {
            List<VecRow> batch = rows.subList(i, Math.min(i + batchSize, rows.size()));
            List<Document> vectorDocuments = batch.stream()
                    .map(row -> toVectorDocument(doc, row))
                    .toList();
            vectors.add(vectorDocuments);
            chunks.markIndexed(batch.stream().map(VecRow::chunkId).toList());
            indexed += batch.size();
        }

        vectors.save();
        documents.updateReady(doc.id(), drafts.size(), indexed, parsed.totalChars());
        bm25.invalidate(doc.kbId());
        log.info("ingested doc={} attempt={} chunks={} chars={}",
                doc.id(), attempt, drafts.size(), parsed.totalChars());
    }

    private Document toVectorDocument(DocumentEntity doc, VecRow row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkId", row.chunkId());
        metadata.put("docId", doc.id());
        // keep kbId as Integer so SimpleVectorStore's SpEL filter (kbId == N) matches the literal type
        metadata.put("kbId", (int) doc.kbId());
        metadata.put("chunkIndex", row.draft().chunkIndex());
        if (row.draft().pageNo() != null) {
            metadata.put("pageNo", row.draft().pageNo());
        }
        metadata.put("docName", doc.fileName());
        metadata.put("aclScope", doc.aclScope());
        metadata.put("contentHash", row.contentHash());
        return new Document(row.vectorId(), row.draft().content(), metadata);
    }

    private void cleanup(DocumentEntity doc) {
        try {
            List<String> vectorIds = chunks.vectorIdsByDoc(doc.id());
            vectors.delete(vectorIds);
            chunks.deleteByDoc(doc.id());
        } catch (Exception e) {
            log.warn("cleanup doc={} failed: {}", doc.id(), e.getMessage());
        }
    }

    private void safeSaveVectors() {
        try {
            vectors.save();
        } catch (Exception e) {
            log.warn("save vector store after failure failed: {}", e.getMessage());
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record VecRow(long chunkId, String vectorId, String contentHash, ChunkDraft draft) {
    }
}
