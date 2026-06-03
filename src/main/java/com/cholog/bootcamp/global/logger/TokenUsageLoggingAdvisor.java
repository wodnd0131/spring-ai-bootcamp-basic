package com.cholog.bootcamp.global.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;

public class TokenUsageLoggingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageLoggingAdvisor.class);

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();

        ChatClientResponse response = chain.nextCall(request);  // 실제 모델 호출

        long latency = System.currentTimeMillis() - start;
        ChatResponse cr = response.chatResponse();
        if (cr != null) {
            var meta = cr.getMetadata();
            var usage = meta.getUsage();
            log.info("AI call | model={} finish={} prompt={} completion={} total={} latencyMs={}",
                    meta.getModel(),
                    cr.getResult().getMetadata().getFinishReason(),
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens(),
                    latency);
        }
        return response;
    }

    @Override
    public String getName() {
        return "tokenUsageLogging";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
