package com.cholog.bootcamp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "org.springframework.ai.openai.api.OpenAiApi")
public class OpenAiExtraBodyWorkaround {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer extraBodyMixinCustomizer() {
        return builder -> builder.mixIn(
                OpenAiApi.ChatCompletionRequest.class,
                ExtraBodyExcludeMixin.class
        );
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private abstract static class ExtraBodyExcludeMixin {

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("extra_body")
        abstract Map<String, Object> extraBody();
    }

    @Bean
    public RestClientCustomizer openAiExtraBodyStripper(final ObjectMapper objectMapper) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            if (body.length > 0) {
                try {
                    final Map<String, Object> map = objectMapper.readValue(body, MAP_TYPE);
                    if (map.remove("extra_body") != null) {
                        body = objectMapper.writeValueAsBytes(map);
                    }
                } catch (final IOException ignored) {
                }
            }
            return execution.execute(request, body);
        });
    }
}
