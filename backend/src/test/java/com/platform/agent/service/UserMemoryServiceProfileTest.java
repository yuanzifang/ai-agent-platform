package com.platform.agent.service;

import com.platform.agent.entity.UserMemory;
import com.platform.agent.mapper.UserMemoryMapper;
import com.platform.security.JwtPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryServiceProfileTest {

    private UserMemory memory(String content, String category) {
        UserMemory m = new UserMemory();
        m.setContent(content);
        m.setCategory(category);
        m.setSource("AUTO");
        return m;
    }

    @Test
    void profileDeduplicatesAndFiltersNoise() {
        UserMemoryMapper mapper = mock(UserMemoryMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                memory("我的名字是王小明", "PROFILE"),
                memory("我的名字是王小明", "PROFILE"),
                memory("用户职业：无", "PROFILE"),
                memory("用户职业：软件工程师", "PROFILE"),
                memory("用户长期偏好：喜欢阅读", "PREFERENCE"),
                memory("用户消息：退款政策是什么", "FACT")
        ));
        UserMemoryService service = new UserMemoryService(mapper);
        JwtPrincipal principal = new JwtPrincipal(1L, 1L, false, List.of("OWNER"), List.of());

        Map<String, Object> profile = service.profile(principal);
        assertEquals(3, profile.get("total"));
        assertFalse(String.valueOf(profile.get("summary")).contains("用户职业：无"));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> categories = (Map<String, List<String>>) profile.get("categories");
        assertEquals(1, categories.get("PROFILE").stream().filter("我的名字是王小明"::equals).count());
        assertEquals(List.of("我的名字是王小明", "用户职业：软件工程师"), categories.get("PROFILE"));
        assertEquals(List.of("用户长期偏好：喜欢阅读"), categories.get("PREFERENCE"));
    }

    @Test
    void replaceProfileCleansDuplicatesAndNoise() {
        UserMemoryMapper mapper = mock(UserMemoryMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        UserMemoryService service = new UserMemoryService(mapper);
        JwtPrincipal principal = new JwtPrincipal(1L, 1L, false, List.of("OWNER"), List.of());

        service.replaceProfile(Map.of("items", List.of(
                Map.of("content", "我的名字是王小明", "category", "PROFILE"),
                Map.of("content", "我的名字是王小明", "category", "PROFILE"),
                Map.of("content", "用户职业：无", "category", "PROFILE"),
                Map.of("content", "用户职业：软件工程师", "category", "PROFILE")
        )), principal);

        verify(mapper).delete(any());
        verify(mapper, times(2)).insert(any(UserMemory.class));
    }
}
