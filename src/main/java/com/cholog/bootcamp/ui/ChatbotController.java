package com.cholog.bootcamp.ui;

import com.cholog.bootcamp.global.logger.TokenUsageLoggingAdvisor;
import com.cholog.bootcamp.ui.dto.ChatAnswerResponse;
import com.cholog.bootcamp.ui.dto.ChatRequest;
import javax.validation.Valid;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatbotController {

    private final ChatClient chatClient;

    public ChatbotController(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultAdvisors(new TokenUsageLoggingAdvisor())
                .build();
    }

    @PostMapping
    public ResponseEntity<ChatAnswerResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse chatResponse = chatClient.prompt()
                .user(request.question())
                .call()
                .chatResponse();
        if (chatResponse == null) {
            throw new RuntimeException("LLM Response Null");
        }
        ChatAnswerResponse response = ChatAnswerResponse.of(chatResponse);
        return ResponseEntity.ok().body(response);
    }
}
