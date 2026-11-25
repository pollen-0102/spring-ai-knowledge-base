package com.pollen.rag.document;

import com.pollen.rag.common.BizException;
import com.pollen.rag.common.ErrorCode;
import com.pollen.rag.config.VectorStoreManager;
import com.pollen.rag.document.pipeline.IngestPipeline;
import com.pollen.rag.document.storage.FileStorage;
import com.pollen.rag.document.storage.StoredFile;
import com.pollen.rag.kb.KbService;
import com.pollen.rag.retrieval.Bm25Searcher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

@Service
public class DocumentService {

    private final KbService kbService;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final FileStorage fileStorage;
    private final IngestPipeline ingestPipeline;
    private final VectorStoreManager vectors;
    private final Bm25Searcher bm25;

    public DocumentService(KbService kbService, DocumentRepository documents, ChunkRepository chunks,
                           FileStorage fileStorage, IngestPipeline ingestPipeline,
                           VectorStoreManager vectors, Bm25Searcher bm25) {
        this.kbService = kbService;
        this.documents = documents;
        this.chunks = chunks;
        this.fileStorage = fileStorage;
        this.ingestPipeline = ingestPipeline;
        this.vectors = vectors;
        this.bm25 = bm25;
    }

    public DocumentDtos.UploadResult upload(long kbId, MultipartFile file) {
        kbService.require(kbId);
        StoredFile stored = fileStorage.save(file, file.getOriginalFilename());

        Optional<DocumentEntity> existing = documents.findByHash(kbId, stored.sha256());
        if (existing.isPresent()) {
            fileStorage.delete(stored.path());
            DocumentEntity doc = existing.get();
            return new DocumentDtos.UploadResult(doc.id(), doc.status(), true);
        }

        long docId;
        try {
            docId = documents.insert(kbId, stored.originalName(), stored.fileType(),
                    stored.path(), stored.size(), stored.sha256(), "ALL");
        } catch (DuplicateKeyException e) {
            fileStorage.delete(stored.path());
            DocumentEntity doc = documents.findByHash(kbId, stored.sha256())
                    .orElseThrow(() -> new BizException(ErrorCode.DUPLICATE, "重复文档"));
            return new DocumentDtos.UploadResult(doc.id(), doc.status(), true);
        } catch (RuntimeException e) {
            fileStorage.delete(stored.path());
            throw e;
        }

        try {
            ingestPipeline.processAsync(docId);
        } catch (TaskRejectedException e) {
            documents.updateFailed(docId, "入库队列已满，请稍后重试", 0);
        }
        return new DocumentDtos.UploadResult(docId, "PENDING", false);
    }

    public DocumentDtos.PageResult<DocumentEntity> list(long kbId, String status, int page, int size) {
        kbService.require(kbId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<DocumentEntity> items = documents.list(kbId, status, safeSize, (safePage - 1) * safeSize);
        long total = documents.countByKb(kbId);
        return new DocumentDtos.PageResult<>(items, total, safePage, safeSize);
    }

    public DocumentEntity detail(long id) {
        return require(id);
    }

    public DocumentDtos.PageResult<ChunkEntity> chunks(long docId, int page, int size) {
        require(docId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        List<ChunkEntity> items = chunks.pageByDoc(docId, safeSize, (safePage - 1) * safeSize);
        long total = chunks.countByDoc(docId);
        return new DocumentDtos.PageResult<>(items, total, safePage, safeSize);
    }

    public void delete(long id) {
        DocumentEntity doc = require(id);
        List<String> vectorIds = chunks.vectorIdsByDoc(id);
        vectors.delete(vectorIds);
        vectors.save();
        bm25.invalidate(doc.kbId());
        documents.deleteById(id);
        fileStorage.delete(doc.storagePath());
    }

    public DocumentDtos.UploadResult reindex(long id) {
        DocumentEntity doc = require(id);
        vectors.delete(chunks.vectorIdsByDoc(id));
        chunks.deleteByDoc(id);
        documents.updateStatus(id, "PENDING");
        bm25.invalidate(doc.kbId());
        vectors.save();
        try {
            ingestPipeline.processAsync(id);
        } catch (TaskRejectedException e) {
            documents.updateFailed(id, "入库队列已满，请稍后重试", 0);
            return new DocumentDtos.UploadResult(id, "FAILED", false);
        }
        return new DocumentDtos.UploadResult(id, "PENDING", false);
    }

    private DocumentEntity require(long id) {
        return documents.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "文档不存在: " + id));
    }
}
