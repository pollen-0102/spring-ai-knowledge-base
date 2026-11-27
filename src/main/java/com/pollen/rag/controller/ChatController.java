package com.pollen.rag.controller;

import com.pollen.rag.chat.ChatDtos;
import com.pollen.rag.chat.ChatService;
import com.pollen.rag.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/kbs/{kbId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable long kbId,
                           @Valid @RequestBody ChatDtos.ChatRequest request,
                           @RequestHeader(value = "X-User-Id", defaultValue = "demo") String userId) {
        return chatService.chat(kbId, request, userId);
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<ChatDtos.MessageView>> messages(@PathVariable long id) {
        return ApiResponse.ok(chatService.messages(id));
    }
}
