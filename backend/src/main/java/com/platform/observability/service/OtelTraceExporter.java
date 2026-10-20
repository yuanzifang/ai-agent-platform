package com.platform.observability.service;

import com.platform.observability.entity.AgentSpan;
import com.platform.observability.entity.AgentTrace;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * OTLP Trace 导出：平台自研 Trace/Span 落库后，同步桥接到标准 OpenTelemetry，
 * 可被 Jaeger/Tempo/Langfuse/Phoenix 等 OTLP 后端直接接收。
 * 默认关闭；OTEL_ENABLED=true 且配置 OTLP HTTP endpoint 时启用。
 */
@Slf4j
@Service
public class OtelTraceExporter {

    @Value("${otel.enabled:false}")
    private boolean enabled;

    @Value("${otel.endpoint:http://localhost:4318}")
    private String endpoint;

    @Value("${otel.service-name:ai-agent-platform}")
    private String serviceName;

    private SdkTracerProvider provider;
    private Tracer tracer;

    @PostConstruct
    void init() {
        if (!enabled) return;
        try {
            String url = endpoint.endsWith("/v1/traces") ? endpoint
                    : endpoint.replaceAll("/+$", "") + "/v1/traces";
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(url)
                    .setTimeout(Duration.ofSeconds(5))
                    .build();
            provider = SdkTracerProvider.builder()
                    .setResource(Resource.getDefault().merge(
                            Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), serviceName))))
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                            .setScheduleDelay(Duration.ofSeconds(1)).build())
                    .build();
            OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            tracer = otel.getTracer("ai-agent-platform");
            log.info("OTLP Trace 导出已启用：endpoint={} service={}", url, serviceName);
        } catch (Exception e) {
            log.warn("OTLP Trace 初始化失败（已关闭导出）：{}", e.getMessage());
            provider = null;
            tracer = null;
        }
    }

    public boolean enabled() {
        return enabled && tracer != null;
    }

    public void export(AgentTrace trace, List<AgentSpan> spans) {
        if (!enabled()) return;
        try {
            java.time.Instant start = instant(trace.getCreatedAt() == null
                    ? LocalDateTime.now() : trace.getCreatedAt().minusNanos(
                    Math.max(trace.getLatencyMs() == null ? 0 : trace.getLatencyMs(), 0) * 1_000_000L));
            java.time.Instant end = instant(trace.getCreatedAt() == null ? LocalDateTime.now() : trace.getCreatedAt());
            Span root = tracer.spanBuilder(TraceService.invokeName(trace.getAgentId(), trace.getAgentName()))
                    .setStartTimestamp(start)
                    .setAttribute("platform.trace_id", nullSafe(trace.getTraceId()))
                    .setAttribute("platform.kind", nullSafe(trace.getKind()))
                    .setAttribute("platform.status", nullSafe(trace.getStatus()))
                    .setAttribute("platform.agent_id", trace.getAgentId() == null ? 0 : trace.getAgentId())
                    .setAttribute("platform.conversation_id",
                            trace.getConversationId() == null ? 0 : trace.getConversationId())
                    .setAttribute("platform.model", nullSafe(trace.getModelName()))
                    .setAttribute("platform.input_tokens",
                            trace.getInputTokens() == null ? 0 : trace.getInputTokens())
                    .setAttribute("platform.output_tokens",
                            trace.getOutputTokens() == null ? 0 : trace.getOutputTokens())
                    .startSpan();
            if ("ERROR".equalsIgnoreCase(trace.getStatus()) || "BLOCKED".equalsIgnoreCase(trace.getStatus())) {
                root.setStatus(StatusCode.ERROR);
                if (trace.getErrorMessage() != null) root.setAttribute("error.message", trace.getErrorMessage());
            }
            for (AgentSpan span : spans) {
                if (span == null) continue;
                Span child = tracer.spanBuilder(span.getName() == null ? "span" : span.getName())
                        .setParent(Context.current().with(root))
                        .setStartTimestamp(instant(span.getStartTime()))
                        .setAttribute("platform.span_type", nullSafe(span.getSpanType()))
                        .setAttribute("platform.span_id", nullSafe(span.getSpanId()))
                        .setAttribute("platform.status", nullSafe(span.getStatus()))
                        .setAttribute("platform.model", nullSafe(span.getModelName()))
                        .startSpan();
                if ("ERROR".equalsIgnoreCase(span.getStatus())) child.setStatus(StatusCode.ERROR);
                child.end(instant(span.getEndTime()));
            }
            root.end(end);
        } catch (Exception e) {
            log.debug("OTLP Trace 导出失败 traceId={}：{}", trace.getTraceId(), e.getMessage());
        }
    }

    private java.time.Instant instant(LocalDateTime time) {
        return time == null ? java.time.Instant.now()
                : time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    @PreDestroy
    void close() {
        if (provider != null) {
            provider.close();
        }
    }
}
