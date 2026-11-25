package com.pollen.rag.retrieval;

public record Hit(long chunkId,
                  long docId,
                  long kbId,
                  int chunkIndex,
                  Integer pageNo,
                  String docName,
                  String content,
                  double denseScore,
                  double sparseScore,
                  double fusedScore,
                  String source) {

    public Hit merge(Hit other) {
        String preferredContent = this.content != null ? this.content : other.content;
        String preferredDocName = this.docName != null ? this.docName : other.docName;
        return new Hit(this.chunkId, this.docId, this.kbId, this.chunkIndex,
                this.pageNo != null ? this.pageNo : other.pageNo,
                preferredDocName, preferredContent,
                Math.max(this.denseScore, other.denseScore),
                Math.max(this.sparseScore, other.sparseScore),
                this.fusedScore, this.source);
    }

    public Hit withFused(double fused, String newSource) {
        return new Hit(chunkId, docId, kbId, chunkIndex, pageNo, docName, content,
                denseScore, sparseScore, fused, newSource);
    }
}
