package com.platform.asr.service;

import com.platform.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 本地 ASR 接入：Qwen3-ASR 以 OpenAI 兼容协议提供服务（/v1/audio/transcriptions）。
 * 独立于 spring.ai.openai 全局连接（chat=DeepSeek、embedding=Ollama、ASR=本地 8000）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "asr.enabled", havingValue = "true")
public class OpenAiCompatAsrProvider implements AsrService {

    private final RestClient client;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatAsrProvider(@Value("${asr.endpoint}") String endpoint,
                                   @Value("${asr.api-key:local}") String apiKey,
                                   @Value("${asr.model}") String model,
                                   @Value("${asr.timeout-ms:60000}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .baseUrl(endpoint)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();
        this.model = model;
        log.info("本地 ASR 已启用：endpoint={} model={}", endpoint, model);
    }

    @Override
    public String transcribe(byte[] audio, String filename, String contentType) {
        try {
            ByteArrayResource filePart = new ByteArrayResource(audio) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", filePart);
            form.add("model", model);

            // 服务端响应头为 application/x-ndjson（body 实为 OpenAI 格式 JSON），按字符串接收后自行解析
            String raw = client.post()
                    .uri("/v1/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            String text = extractText(raw);
            log.info("ASR 转写完成：{} 字节 -> {} 字符", audio.length, text.length());
            return text;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            log.warn("ASR 转写失败：{}", root.getMessage());
            throw new BusinessException(502, "语音转写失败：" + root.getMessage());
        }
    }

    private String extractText(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // ndjson：逐行找含 text 的 JSON 行；否则整体按 JSON 解析
        for (String line : raw.split("\n")) {
            String text = parseText(line.trim());
            if (text != null) return text;
        }
        return parseText(raw.trim());
    }

    private String parseText(String json) {
        if (json.isEmpty() || json.charAt(0) != '{') return null;
        try {
            Object v = objectMapper.readValue(json, Map.class).get("text");
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }
}
