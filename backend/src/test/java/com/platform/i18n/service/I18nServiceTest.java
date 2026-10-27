package com.platform.i18n.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.i18n.entity.I18nMessage;
import com.platform.i18n.mapper.I18nMessageMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 国际化字段映射：lang/key 清洗、upsert 幂等、JSON/CSV 导入、CSV RFC4180 解析、导出格式。 */
class I18nServiceTest {

    private I18nMessageMapper mapper;
    private I18nService service;

    @BeforeEach
    void setUp() {
        mapper = mock(I18nMessageMapper.class);
        service = new I18nService(mapper, new ObjectMapper());
    }

    @Test
    void langFormatValidated() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.messages("zh;drop"));
        assertTrue(e.getMessage().contains("lang 格式非法"));
        assertThrows(BusinessException.class, () -> service.messages(""));
    }

    @Test
    void invalidKeyRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upsert("zh-CN", "bad key<script>", "值", null, 1L));
        assertTrue(e.getMessage().contains("key 仅限"));
        verify(mapper, never()).insert(any(I18nMessage.class));
    }

    @Test
    void upsertInsertsWhenAbsent() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        service.upsert("zh-CN", "menu.agents", "代理列表", "菜单：Agent 列表", 7L);
        ArgumentCaptor<I18nMessage> captor = ArgumentCaptor.forClass(I18nMessage.class);
        verify(mapper).insert(captor.capture());
        assertEquals("menu.agents", captor.getValue().getMessageKey());
        assertEquals("代理列表", captor.getValue().getContent());
        assertEquals(7L, captor.getValue().getUpdatedBy());
    }

    @Test
    void upsertUpdatesWhenExists() {
        I18nMessage existing = new I18nMessage();
        existing.setId(9L);
        existing.setLang("zh-CN");
        existing.setMessageKey("menu.agents");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        service.upsert("zh-CN", "menu.agents", "新译文", null, 7L);
        verify(mapper).updateById(existing);
        assertEquals("新译文", existing.getContent());
        verify(mapper, never()).insert(any(I18nMessage.class));
    }

    @Test
    void importJsonFlatMapCounts() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        Map<String, Object> result = service.importMessages("ja-JP", "json",
                "{\"menu.agents\":\"エージェント\",\"app.name\":\"プラットフォーム\"}", 1L);
        assertEquals(2, ((Number) result.get("inserted")).intValue());
        assertEquals(0, ((Number) result.get("updated")).intValue());
    }

    @Test
    void importCsvWithQuotedCommas() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        String csv = "key,value,description\nmenu.gateway,开放平台,\"含,逗号说明\"\napp.name,平台,";
        Map<String, Object> result = service.importMessages("zh-CN", "csv", csv, 1L);
        assertEquals(2, ((Number) result.get("inserted")).intValue());
        ArgumentCaptor<I18nMessage> captor = ArgumentCaptor.forClass(I18nMessage.class);
        verify(mapper, Mockito.times(2)).insert(captor.capture());
        List<I18nMessage> inserted = captor.getAllValues();
        assertEquals("含,逗号说明", inserted.get(0).getDescription(), "引号内逗号保留");
        assertNull(inserted.get(1).getDescription(), "空描述归 null");
    }

    @Test
    void csvParserHandlesQuotingAndNewlines() {
        List<String[]> rows = I18nService.parseCsvRecords("a,b\n\"x,y\",z\n\"line1\nline2\",w");
        assertEquals(3, rows.size());
        assertEquals("x,y", rows.get(1)[0]);
        assertEquals("z", rows.get(1)[1]);
        assertEquals("line1\nline2", rows.get(2)[0], "引号内换行保留");
    }

    @Test
    void emptyImportRejected() {
        assertThrows(BusinessException.class, () -> service.importMessages("zh-CN", "json", "{}", 1L));
        assertThrows(BusinessException.class, () -> service.importMessages("zh-CN", "csv", "key,value\n", 1L));
    }
}
