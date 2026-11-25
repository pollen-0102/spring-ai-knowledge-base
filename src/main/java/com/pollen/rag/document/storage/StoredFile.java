package com.pollen.rag.document.storage;

public record StoredFile(String path, String originalName, String fileType, long size, String sha256) {
}
