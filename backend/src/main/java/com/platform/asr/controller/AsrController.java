package com.platform.asr.controller;

import com.platform.asr.service.AsrService;
import com.platform.common.exception.BusinessException;
import com.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

/**
 * 语音转写接口（需求文档 7.2 / 7.3）：multipart audio（webm/wav/mp3，≤10MB），返回 text。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/asr")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "asr.enabled", havingValue = "true")
public class AsrController {

    private static final long MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "audio/webm", "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a");

    private final AsrService asrService;

    @PostMapping("/transcribe")
    public ApiResponse<Map<String, Object>> transcribe(@RequestPart("audio") MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new BusinessException(400, "音频文件不能为空");
        }
        if (audio.getSize() > MAX_BYTES) {
            throw new BusinessException(400, "音频超过 10MB 限制");
        }
        String contentType = audio.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException(400, "不支持的音频格式：" + contentType);
        }
        try {
            String text = asrService.transcribe(audio.getBytes(),
                    audio.getOriginalFilename() == null ? "audio.webm" : audio.getOriginalFilename(),
                    contentType);
            return ApiResponse.ok(Map.of("text", text));
        } catch (java.io.IOException e) {
            throw new BusinessException(400, "音频读取失败");
        }
    }
}
