package com.platform.asr.service;

/**
 * 语音转文字（架构 5.10）：本地 ASR 服务，OpenAI 兼容 /v1/audio/transcriptions 协议。
 */
public interface AsrService {

    /**
     * @param audio       音频字节
     * @param filename    原始文件名（用于识别格式）
     * @param contentType MIME 类型（audio/webm、audio/wav、audio/mpeg 等）
     * @return 转写文本
     */
    String transcribe(byte[] audio, String filename, String contentType);
}
