package com.pollen.rag.prompt;

import com.pollen.rag.chat.PromptService;
import com.pollen.rag.chat.PromptTemplate;
import com.pollen.rag.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PromptController {

    private final PromptService promptService;

    public PromptController(PromptService promptService) {
        this.promptService = promptService;
    }

    @GetMapping("/kbs/{kbId}/prompts")
    public ApiResponse<List<PromptTemplate>> list(@PathVariable long kbId) {
        return ApiResponse.ok(promptService.list(kbId));
    }

    @PutMapping("/prompts/{id}")
    public ApiResponse<PromptTemplate> update(@PathVariable long id,
                                              @Valid @RequestBody UpdatePromptRequest request) {
        return ApiResponse.ok(promptService.update(id, request.name(),
                request.systemPrompt(), request.userPrompt()));
    }

    @PostMapping("/prompts/{id}/preview")
    public ApiResponse<Map<String, String>> preview(@PathVariable long id,
                                                    @RequestBody PreviewRequest request) {
        return ApiResponse.ok(promptService.preview(id, request.context(), request.question(),
                request.history(), request.kbName()));
    }

    public record UpdatePromptRequest(
            @Size(max = 128) String name,
            @NotBlank String systemPrompt,
            @NotBlank String userPrompt) {
    }

    public record PreviewRequest(String context, String question, String history, String kbName) {
    }
}
