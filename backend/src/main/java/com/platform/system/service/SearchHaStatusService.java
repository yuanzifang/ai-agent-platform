package com.platform.system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.knowledge.service.ElasticsearchClient;
import com.platform.knowledge.service.QdrantVectorStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** ES/Qdrant 多节点 HA 状态与 Prometheus 指标。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchHaStatusService {

    private final ObjectProvider<ElasticsearchClient> esProvider;
    private final ObjectProvider<QdrantVectorStore> qdrantProvider;
    private final MeterRegistry meterRegistry;

    private final AtomicInteger esAvailable = new AtomicInteger();
    private final AtomicLong esNodes = new AtomicLong();
    private final AtomicInteger esStatus = new AtomicInteger(3);
    private final AtomicInteger qdrantAvailable = new AtomicInteger();
    private final AtomicLong qdrantPeers = new AtomicLong();

    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("knowledge_es_cluster_available", esAvailable, AtomicInteger::get)
                .description("ES 集群健康查询是否可用").register(meterRegistry);
        Gauge.builder("knowledge_es_cluster_nodes", esNodes, AtomicLong::get)
                .description("ES 集群节点数").register(meterRegistry);
        Gauge.builder("knowledge_es_cluster_status", esStatus, AtomicInteger::get)
                .description("ES 集群状态：0 green / 1 yellow / 2 red / 3 unknown").register(meterRegistry);
        Gauge.builder("knowledge_qdrant_cluster_available", qdrantAvailable, AtomicInteger::get)
                .description("Qdrant 集群状态查询是否可用").register(meterRegistry);
        Gauge.builder("knowledge_qdrant_cluster_peers", qdrantPeers, AtomicLong::get)
                .description("Qdrant 集群 peers 数量").register(meterRegistry);
        refresh();
    }

    @Scheduled(fixedDelayString = "${search.ha.status-interval-ms:60000}")
    public void refresh() {
        try {
            ElasticsearchClient es = esProvider.getIfAvailable();
            if (es == null) {
                esAvailable.set(0);
                esNodes.set(0);
                esStatus.set(3);
            } else {
                JsonNode health = es.clusterHealth();
                esAvailable.set(1);
                esNodes.set(health.path("number_of_nodes").asLong(0));
                esStatus.set(status(health.path("status").asText("")));
            }
        } catch (Exception e) {
            esAvailable.set(0);
            esNodes.set(0);
            esStatus.set(3);
            log.warn("ES 集群健康查询失败：{}", e.getMessage());
        }
        try {
            QdrantVectorStore qdrant = qdrantProvider.getIfAvailable();
            if (qdrant == null) {
                qdrantAvailable.set(0);
                qdrantPeers.set(0);
            } else {
                JsonNode cluster = qdrant.clusterStatus();
                qdrantAvailable.set(1);
                qdrantPeers.set(cluster.path("result").path("peers").size());
            }
        } catch (Exception e) {
            qdrantAvailable.set(0);
            qdrantPeers.set(0);
            log.warn("Qdrant 集群健康查询失败：{}", e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> es = new LinkedHashMap<>();
        es.put("available", esAvailable.get() == 1);
        es.put("nodes", esNodes.get());
        es.put("status", esStatus.get());
        out.put("es", es);
        Map<String, Object> qdrant = new LinkedHashMap<>();
        qdrant.put("available", qdrantAvailable.get() == 1);
        qdrant.put("peers", qdrantPeers.get());
        out.put("qdrant", qdrant);
        return out;
    }

    private int status(String value) {
        return switch (value == null ? "" : value.toLowerCase()) {
            case "green" -> 0;
            case "yellow" -> 1;
            case "red" -> 2;
            default -> 3;
        };
    }
}
