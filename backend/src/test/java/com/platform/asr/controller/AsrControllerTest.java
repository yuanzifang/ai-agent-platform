package com.platform.asr.controller;

import com.platform.asr.service.AsrService;
import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P0：ASR 上传类型和 10MB 大小边界。 */
class AsrControllerTest {

    @Test
    void rejectsOversizedAudio() {
        AsrController controller = new AsrController(mock(AsrService.class));
        MockMultipartFile audio = new MockMultipartFile("audio", "a.webm", "audio/webm",
                new byte[10 * 1024 * 1024 + 1]);
        BusinessException error = assertThrows(BusinessException.class, () -> controller.transcribe(audio));
        assertEquals(400, error.getCode());
    }

    @Test
    void rejectsUnsupportedAudioType() {
        AsrController controller = new AsrController(mock(AsrService.class));
        MockMultipartFile audio = new MockMultipartFile("audio", "a.exe", "application/x-msdownload",
                new byte[]{1, 2, 3});
        BusinessException error = assertThrows(BusinessException.class, () -> controller.transcribe(audio));
        assertEquals(400, error.getCode());
    }

    @Test
    void acceptsAllowedAudioAndDelegates() throws Exception {
        AsrService service = mock(AsrService.class);
        when(service.transcribe(any(byte[].class), anyString(), anyString())).thenReturn("hello");
        AsrController controller = new AsrController(service);
        MockMultipartFile audio = new MockMultipartFile("audio", "a.webm", "audio/webm",
                new byte[]{1, 2, 3});

        Map<String, Object> result = controller.transcribe(audio).data();

        assertEquals("hello", result.get("text"));
    }
}
