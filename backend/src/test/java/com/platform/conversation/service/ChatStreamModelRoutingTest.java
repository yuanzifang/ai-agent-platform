package com.platform.conversation.service;

import com.platform.capability.runtime.TraceStep;
import com.platform.capability.runtime.TraceStepCollector;
import com.platform.common.context.GrayTagContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ChatStreamService 的 Agent 级模型路由接线。 */
class ChatStreamModelRoutingTest {

    @AfterEach
    void clearGrayTag() {
        GrayTagContext.clear();
    }

    @Test
    void appliesRoutingDecisionAndWritesTrace() {
        ModelRoutingService routing = mock(ModelRoutingService.class);
        when(routing.resolve(Map.of(), "复杂问题"))
                .thenReturn(new ModelRoutingService.RoutingDecision("complex-model", "complexity:complex"));
        TraceStepCollector steps = new TraceStepCollector();

        String model = ChatStreamService.resolveRoutedModel(routing, Map.of(), "复杂问题",
                "base-model", steps);

        assertEquals("complex-model", model);
        assertEquals(1, steps.steps().size());
        TraceStep step = steps.steps().get(0);
        assertEquals("model", step.spanType());
        assertEquals("smart-routing", step.name());
        assertEquals("complex-model", step.attributes().get("to"));
        assertEquals("base-model", step.attributes().get("from"));
        assertEquals("complexity:complex", step.attributes().get("reason"));
    }

    @Test
    void keepsBaseModelWhenRoutingHasNoDecision() {
        ModelRoutingService routing = mock(ModelRoutingService.class);
        when(routing.resolve(Map.of(), "普通问题")).thenReturn(null);
        TraceStepCollector steps = new TraceStepCollector();

        String model = ChatStreamService.resolveRoutedModel(routing, Map.of(), "普通问题",
                "base-model", steps);

        assertEquals("base-model", model);
        assertEquals(0, steps.steps().size());
    }

    @Test
    void grayTagWinsOverRouting() {
        GrayTagContext.set("canary-v2");
        ModelRoutingService routing = mock(ModelRoutingService.class);
        Map<String, Object> config = Map.of(
                "grayTag", "canary-v2",
                "grayModel", "gray-model");

        String model = ChatStreamService.resolveRoutedModel(routing, config, "问题",
                "base-model", new TraceStepCollector());

        assertEquals("gray-model", model);
        verify(routing, never()).resolve(config, "问题");
    }
}
