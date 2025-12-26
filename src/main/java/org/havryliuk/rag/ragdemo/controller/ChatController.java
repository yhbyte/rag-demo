package org.havryliuk.rag.ragdemo.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.havryliuk.rag.ragdemo.model.ChatRequest;
import org.havryliuk.rag.ragdemo.model.ChatResponse;
import org.havryliuk.rag.ragdemo.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.ask(request.question());
    }
}
