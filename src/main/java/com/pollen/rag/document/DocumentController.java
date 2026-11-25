package com.pollen.rag.document;

import com.pollen.rag.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/kbs/{kbId}/documents")
    public ApiResponse<DocumentDtos.UploadResult> upload(@PathVariable long kbId,
                                                         @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(documentService.upload(kbId, file));
    }

    @GetMapping("/kbs/{kbId}/documents")
    public ApiResponse<DocumentDtos.PageResult<DocumentEntity>> list(
            @PathVariable long kbId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(documentService.list(kbId, status, page, size));
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<DocumentEntity> detail(@PathVariable long id) {
        return ApiResponse.ok(documentService.detail(id));
    }

    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<DocumentDtos.PageResult<ChunkEntity>> chunks(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(documentService.chunks(id, page, size));
    }

    @PostMapping("/documents/{id}/reindex")
    public ApiResponse<DocumentDtos.UploadResult> reindex(@PathVariable long id) {
        return ApiResponse.ok(documentService.reindex(id));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id) {
        documentService.delete(id);
        return ApiResponse.ok(Map.of("id", id, "deleted", true));
    }
}
