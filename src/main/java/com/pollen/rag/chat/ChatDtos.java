package com.pollen.rag.chat;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record ChatRequest(
            Long sessionId,
            @NotBlank String question,
            @Min(1) @Max(20) Integer topK,
            @DecimalMin("0.0") @DecimalMax("1.0") Double scoreThreshold,
            Boolean stream) {
    }

    public record MessageView(long id,
                              String role,
                              String content,
                              boolean refused,
                              String structured,
                              OffsetDateTime createdAt) {
    }

    public record SessionView(long id,
                              long kbId,
                              String userId,
                              String title,
                              OffsetDateTime createdAt) {
    }
}
