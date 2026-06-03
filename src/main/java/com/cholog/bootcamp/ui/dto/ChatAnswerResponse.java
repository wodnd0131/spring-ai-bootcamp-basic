package com.cholog.bootcamp.ui.dto;


import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

public record ChatAnswerResponse(String answer, TokenUsage tokenUsage) {

    public static ChatAnswerResponse of(ChatResponse chatResponse) {
        String answer = chatResponse.getResult().getOutput().getText();

        Usage usage = chatResponse.getMetadata().getUsage();
        TokenUsage tokenUsage = new TokenUsage(
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens()
        );

        return new ChatAnswerResponse(answer, tokenUsage);
    }

    public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    }
}
