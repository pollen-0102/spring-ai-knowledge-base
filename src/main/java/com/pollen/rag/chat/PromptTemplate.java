package com.pollen.rag.chat;

public record PromptTemplate(long id,
                             String code,
                             long kbId,
                             String name,
                             String systemPrompt,
                             String userPrompt,
                             int version,
                             boolean enabled) {
}
