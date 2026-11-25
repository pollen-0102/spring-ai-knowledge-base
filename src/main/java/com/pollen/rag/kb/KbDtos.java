package com.pollen.rag.kb;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class KbDtos {

    private KbDtos() {
    }

    public record CreateKbRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String description,
            String embeddingModel,
            @Min(64) @Max(4096) Integer embeddingDim,
            @Min(100) @Max(4000) Integer chunkSize,
            @Min(0) @Max(10) Integer chunkOverlap,
            @Min(1) @Max(20) Integer topK,
            @DecimalMin("0.0") @DecimalMax("1.0") Double scoreThreshold) {
    }

    public record UpdateKbRequest(
            @Size(max = 128) String name,
            @Size(max = 512) String description,
            @Min(100) @Max(4000) Integer chunkSize,
            @Min(0) @Max(10) Integer chunkOverlap,
            @Min(1) @Max(20) Integer topK,
            @DecimalMin("0.0") @DecimalMax("1.0") Double scoreThreshold) {
    }
}
