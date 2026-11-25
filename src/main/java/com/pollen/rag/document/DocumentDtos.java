package com.pollen.rag.document;

import java.util.List;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record UploadResult(long docId, String status, boolean duplicated) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }
}
