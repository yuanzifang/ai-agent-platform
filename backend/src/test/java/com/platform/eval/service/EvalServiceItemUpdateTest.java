package com.platform.eval.service;

import com.platform.common.exception.BusinessException;
import com.platform.eval.entity.EvalDataset;
import com.platform.eval.entity.EvalDatasetItem;
import com.platform.eval.mapper.EvalDatasetItemMapper;
import com.platform.eval.mapper.EvalDatasetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalServiceItemUpdateTest {

    private EvalDatasetMapper datasetMapper;
    private EvalDatasetItemMapper itemMapper;
    private EvalService service;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(EvalDatasetMapper.class);
        itemMapper = mock(EvalDatasetItemMapper.class);
        service = new EvalService(datasetMapper, itemMapper, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void updateItemNormalizesBlankJsonAndTouchesDataset() {
        EvalDatasetItem item = new EvalDatasetItem();
        item.setId(5L);
        item.setDatasetId(2L);
        item.setQuestion("old");
        item.setExpectedPayload("{\"chunk_ids\":[1]}");
        item.setStepConfig("{\"topK\":5}");
        when(itemMapper.selectById(5L)).thenReturn(item);

        service.updateItem(5L, " new question ", "answer", "criteria", "ANSWER", "", "  ");

        assertEquals(" new question ", item.getQuestion());
        assertNull(item.getExpectedPayload());
        assertNull(item.getStepConfig());
        verify(itemMapper).updateById(item);
        verify(datasetMapper).updateById(any(EvalDataset.class));
    }

    @Test
    void updateItemRejectsMissingItem() {
        when(itemMapper.selectById(9L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateItem(9L, "q", "", "", "ANSWER", null, null));

        assertEquals(404, ex.getCode());
    }
}
