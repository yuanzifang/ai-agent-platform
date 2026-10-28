package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 定期读取 ES 快照状态，供“无成功快照/快照过期”告警使用。 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "search.es.enabled", havingValue = "true")
public class SearchSnapshotStatusService {

    private final ElasticsearchClient client;
    private final MeterRegistry meterRegistry;

    @Value("${search.es.snapshot-repo:aap_local}")
    private String repository = "aap_local";

    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final AtomicInteger available = new AtomicInteger();

    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("knowledge_es_snapshot_last_success_epoch_seconds",
                        lastSuccessEpochSeconds, AtomicLong::get)
                .description("最近一个成功 ES 快照的时间戳（epoch 秒）")
                .register(meterRegistry);
        Gauge.builder("knowledge_es_snapshot_available", available, AtomicInteger::get)
                .description("快照仓库是否可查询（1=是，0=否）")
                .register(meterRegistry);
        refresh();
    }

    @Scheduled(fixedDelayString = "${search.es.snapshot-metrics-interval-ms:300000}")
    public void refresh() {
        try {
            JsonNode response = client.snapshots(repository);
            long latest = 0;
            for (JsonNode snapshot : response.path("snapshots")) {
                if (!"SUCCESS".equals(snapshot.path("state").asText())) continue;
                latest = Math.max(latest, snapshot.path("start_time_in_millis").asLong());
            }
            lastSuccessEpochSeconds.set(latest == 0 ? 0 : latest / 1000);
            available.set(1);
        } catch (Exception e) {
            available.set(0);
            log.warn("ES 快照状态查询失败 repo={} err={}", repository, e.getMessage());
        }
    }
}
