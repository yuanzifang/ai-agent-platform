package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentFavorite;
import com.platform.agent.entity.AgentKnowledgeBase;
import com.platform.agent.entity.AgentRating;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentReport;
import com.platform.agent.entity.AgentSkillBinding;
import com.platform.agent.entity.AgentSubAgent;
import com.platform.agent.entity.AgentToolBinding;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.mapper.AgentFavoriteMapper;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentRatingMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentReportMapper;
import com.platform.agent.mapper.AgentSkillBindingMapper;
import com.platform.agent.mapper.AgentSubAgentMapper;
import com.platform.agent.mapper.AgentToolBindingMapper;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.observability.mapper.AgentTraceMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.AgentUserGrant;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.AgentUserGrantMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 广场（需求 3.8 / P1）：租户内已发布 Agent 展示、质量指标、收藏、评分/回复、举报、Fork、上下架。
 * 列表只展示 PUBLISHED + listed + TENANT 可见的 Agent；PRIVATE Agent 不出现在广场。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSquareService {

    private final AgentRegistryMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final ConversationMapper conversationMapper;
    private final AgentTraceMapper traceMapper;
    private final SysUserMapper userMapper;
    private final AgentFavoriteMapper favoriteMapper;
    private final AgentRatingMapper ratingMapper;
    private final AgentReportMapper reportMapper;
    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final AgentSkillBindingMapper agentSkillMapper;
    private final AgentToolBindingMapper agentToolMapper;
    private final AgentSubAgentMapper agentSubAgentMapper;
    private final AgentUserGrantMapper grantMapper;
    private final KnowledgePermissionService permissionService;
    private final AgentService agentService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    // ---------- 列表 / 详情 ----------

    public Map<String, Object> list(JwtPrincipal principal, String keyword, String category, String tag,
                                    String sort, boolean favoritesOnly, long page, long pageSize) {
        List<Long> favoriteIds = favoriteMapper.selectList(new LambdaQueryWrapper<AgentFavorite>()
                        .eq(AgentFavorite::getUserId, principal.userId()))
                .stream().map(AgentFavorite::getAgentId).toList();

        List<AgentRegistry> agents = agentMapper.selectList(new LambdaQueryWrapper<AgentRegistry>()
                .eq(AgentRegistry::getStatus, "PUBLISHED")
                .eq(AgentRegistry::getVisibility, "TENANT")
                .eq(AgentRegistry::getListed, 1));

        List<Map<String, Object>> cards = new ArrayList<>();
        for (AgentRegistry a : agents) {
            if (favoritesOnly && !favoriteIds.contains(a.getId())) continue;
            Map<String, Object> card = card(a, principal);
            List<String> tags = (List<String>) card.get("tags");
            if (keyword != null && !keyword.isBlank()
                    && !contains(a.getName(), keyword) && !contains(a.getDescription(), keyword)) continue;
            if (category != null && !category.isBlank() && !category.equals(a.getCategory())) continue;
            if (tag != null && !tag.isBlank() && (tags == null || tags.stream().noneMatch(tag::equals))) continue;
            cards.add(card);
        }

        Comparator<Map<String, Object>> comparator = switch (sort == null ? "hot" : sort) {
            case "rating" -> Comparator
                    .<Map<String, Object>>comparingDouble(c -> num(((Map<?, ?>) c.get("quality")).get("ratingAvg")))
                    .reversed()
                    .thenComparing(Comparator.<Map<String, Object>>comparingLong(
                            c -> num(((Map<?, ?>) c.get("quality")).get("ratingCount"))).reversed());
            case "new" -> Comparator.<Map<String, Object>, String>comparing(
                    c -> String.valueOf(c.get("publishedAt")), Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.<Map<String, Object>>comparingDouble(c -> hotScore((Map<?, ?>) c.get("quality")))
                    .reversed();
        };
        cards.sort(comparator);

        long size = Math.min(Math.max(pageSize, 1), 100);
        long p = Math.max(page, 1);
        int from = (int) Math.min((p - 1) * size, cards.size());
        int to = (int) Math.min(from + size, cards.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", cards.subList(from, to));
        result.put("total", cards.size());
        result.put("page", p);
        result.put("pageSize", size);
        return result;
    }

    public Map<String, Object> detail(Long id, JwtPrincipal principal) {
        AgentRegistry agent = publishedAgent(id);
        Map<String, Object> card = card(agent, principal);
        AgentVersion version = latestVersion(id);
        Map<String, Object> snapshot = version == null ? Map.of() : fromJsonMap(version.getSnapshot());
        card.put("openingStatement", snapshot.getOrDefault("openingStatement", agent.getOpeningStatement()));
        card.put("variables", snapshot.getOrDefault("variables", fromJsonValue(agent.getVariables())));
        card.put("version", version == null ? null : version.getVersion());
        card.put("listed", agent.getListed() != null && agent.getListed() == 1);
        card.put("canUse", usable(agent, principal));
        card.put("isOwner", agent.getOwnerId().equals(principal.userId())
                || principal.roles().stream().anyMatch(r -> r.equals("ADMIN") || r.equals("OWNER")));
        card.put("ratings", ratings(id, principal));
        card.put("canRate", used(agent.getId(), principal.userId()));
        return card;
    }

    // ---------- 收藏 ----------

    @Transactional
    public Map<String, Object> toggleFavorite(Long id, JwtPrincipal principal) {
        AgentRegistry agent = publishedAgent(id);
        AgentFavorite existing = favoriteMapper.selectOne(new LambdaQueryWrapper<AgentFavorite>()
                .eq(AgentFavorite::getAgentId, id)
                .eq(AgentFavorite::getUserId, principal.userId()));
        boolean favorited;
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            favorited = false;
        } else {
            AgentFavorite f = new AgentFavorite();
            f.setAgentId(id);
            f.setUserId(principal.userId());
            f.setTenantId(agent.getTenantId());
            f.setCreatedAt(LocalDateTime.now());
            favoriteMapper.insert(f);
            favorited = true;
        }
        long count = favoriteMapper.selectCount(new LambdaQueryWrapper<AgentFavorite>()
                .eq(AgentFavorite::getAgentId, id));
        return Map.of("favorited", favorited, "favoriteCount", count);
    }

    public Map<String, Object> favorites(JwtPrincipal principal) {
        List<Long> ids = favoriteMapper.selectList(new LambdaQueryWrapper<AgentFavorite>()
                        .eq(AgentFavorite::getUserId, principal.userId())
                        .orderByDesc(AgentFavorite::getId))
                .stream().map(AgentFavorite::getAgentId).toList();
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Long id : ids) {
            AgentRegistry a = agentMapper.selectById(id);
            if (a != null && "PUBLISHED".equals(a.getStatus()) && a.getListed() != null && a.getListed() == 1) {
                cards.add(card(a, principal));
            }
        }
        return Map.of("list", cards, "total", cards.size());
    }

    // ---------- 评价 ----------

    @Transactional
    public Map<String, Object> rate(Long id, Integer score, String comment, JwtPrincipal principal) {
        AgentRegistry agent = publishedAgent(id);
        if (score == null || score < 1 || score > 5) throw new BusinessException(400, "评分需为 1-5 星");
        if (comment != null && comment.length() > 1000) throw new BusinessException(400, "评论长度不能超过 1000 字");
        if (!used(agent.getId(), principal.userId())) {
            throw new BusinessException(400, "请先使用该 Agent 后再评价");
        }
        AgentRating rating = ratingMapper.selectOne(new LambdaQueryWrapper<AgentRating>()
                .eq(AgentRating::getAgentId, id)
                .eq(AgentRating::getUserId, principal.userId()));
        if (rating == null) {
            rating = new AgentRating();
            rating.setAgentId(id);
            rating.setUserId(principal.userId());
            rating.setTenantId(agent.getTenantId());
            rating.setScore(score);
            rating.setComment(comment);
            rating.setCreatedAt(LocalDateTime.now());
            rating.setUpdatedAt(LocalDateTime.now());
            ratingMapper.insert(rating);
        } else {
            rating.setScore(score);
            rating.setComment(comment);
            rating.setUpdatedAt(LocalDateTime.now());
            ratingMapper.updateById(rating);
        }
        Map<String, Object> metrics = metrics(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rating", rating);
        result.put("ratingAvg", metrics.get("ratingAvg"));
        result.put("ratingCount", metrics.get("ratingCount"));
        return result;
    }

    @Transactional
    public Map<String, Object> reply(Long id, Long ratingId, String reply, JwtPrincipal principal) {
        AgentRegistry agent = publishedAgent(id);
        boolean canReply = agent.getOwnerId().equals(principal.userId())
                || principal.roles().stream().anyMatch(r -> r.equals("ADMIN") || r.equals("OWNER"));
        if (!canReply) throw new BusinessException(403, "仅 Agent 所有者或管理员可回复评价");
        if (reply == null || reply.isBlank()) throw new BusinessException(400, "回复内容不能为空");
        if (reply.length() > 1000) throw new BusinessException(400, "回复长度不能超过 1000 字");
        AgentRating rating = ratingMapper.selectById(ratingId);
        if (rating == null || !rating.getAgentId().equals(id)) throw new BusinessException(404, "评价不存在");
        rating.setReply(reply);
        rating.setReplyBy(principal.userId());
        rating.setReplyAt(LocalDateTime.now());
        rating.setUpdatedAt(LocalDateTime.now());
        ratingMapper.updateById(rating);
        return Map.of("rating", rating);
    }

    @Transactional
    public void report(Long id, String reason, JwtPrincipal principal) {
        AgentRegistry agent = publishedAgent(id);
        if (reason == null || reason.isBlank()) throw new BusinessException(400, "举报理由不能为空");
        if (reason.length() > 1000) throw new BusinessException(400, "举报理由不能超过 1000 字");
        AgentReport report = new AgentReport();
        report.setAgentId(id);
        report.setUserId(principal.userId());
        report.setReason(reason);
        report.setStatus("PENDING");
        report.setTenantId(agent.getTenantId());
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);
    }

    // ---------- Fork / 上下架 ----------

    /** Fork：复制已发布配置与绑定；知识库按当前用户可见性重新校验（需求 3.8(4)）。 */
    @Transactional
    public Map<String, Object> fork(Long id, JwtPrincipal principal) {
        AgentRegistry source = publishedAgent(id);
        AgentVersion version = latestVersion(id);
        Map<String, Object> snap = version == null ? Map.of() : fromJsonMap(version.getSnapshot());
        Long tenantId = source.getTenantId();
        KnowledgePermissionService.Viewer viewer = KnowledgePermissionService.Viewer.of(
                principal.userId(), principal.roles());

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", source.getName() + "（副本）");
        req.put("description", source.getDescription());
        req.put("category", source.getCategory());
        req.put("tags", snap.getOrDefault("tags", fromJsonValue(source.getTags())));
        req.put("systemPrompt", snap.getOrDefault("systemPrompt", source.getSystemPrompt()));
        req.put("openingStatement", snap.getOrDefault("openingStatement", source.getOpeningStatement()));
        req.put("agentCapabilities", snap.getOrDefault("agentCapabilities",
                fromJsonMap(source.getAgentCapabilities())));
        req.put("memoryConfig", snap.getOrDefault("memoryConfig", fromJsonMap(source.getMemoryConfig())));
        req.put("modelConfig", snap.getOrDefault("modelConfig", fromJsonMap(source.getModelConfig())));
        req.put("retrievalConfig", snap.getOrDefault("retrievalConfig", fromJsonMap(source.getRetrievalConfig())));
        req.put("variables", snap.getOrDefault("variables", fromJsonValue(source.getVariables())));
        req.put("visibility", "PRIVATE");

        List<Map<String, Object>> kbs = new ArrayList<>();
        for (AgentKnowledgeBase b : agentKbMapper.selectList(new LambdaQueryWrapper<AgentKnowledgeBase>()
                .eq(AgentKnowledgeBase::getAgentId, id))) {
            if (permissionService.findVisible(b.getKnowledgeBaseId(), viewer) == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("knowledgeBaseId", b.getKnowledgeBaseId());
            m.put("enabled", b.getEnabled() != null && b.getEnabled() == 1);
            m.put("retrievalConfig", fromJsonMap(b.getRetrievalConfig()));
            kbs.add(m);
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        for (AgentToolBinding b : agentToolMapper.selectList(new LambdaQueryWrapper<AgentToolBinding>()
                .eq(AgentToolBinding::getAgentId, id))) {
            tools.add(Map.of("toolId", b.getToolId(), "enabled", b.getEnabled() != null && b.getEnabled() == 1,
                    "params", fromJsonMap(b.getParams())));
        }
        List<Map<String, Object>> skills = new ArrayList<>();
        for (AgentSkillBinding b : agentSkillMapper.selectList(new LambdaQueryWrapper<AgentSkillBinding>()
                .eq(AgentSkillBinding::getAgentId, id))) {
            skills.add(Map.of("skillId", b.getSkillId(), "enabled", b.getEnabled() != null && b.getEnabled() == 1));
        }
        List<Map<String, Object>> subs = new ArrayList<>();
        for (AgentSubAgent b : agentSubAgentMapper.selectList(new LambdaQueryWrapper<AgentSubAgent>()
                .eq(AgentSubAgent::getAgentId, id))) {
            subs.add(Map.of("subAgentId", b.getSubAgentId(), "enabled", b.getEnabled() != null && b.getEnabled() == 1));
        }
        req.put("knowledgeBases", kbs);
        req.put("tools", tools);
        req.put("skills", skills);
        req.put("subAgents", subs);

        Map<String, Object> created = agentService.create(req, principal);
        auditService.record(principal.userId(), "agent.fork", "agent", String.valueOf(created.get("id")),
                "SUCCESS", Map.of("sourceAgentId", id));
        return created;
    }

    @Transactional
    public Map<String, Object> setListed(Long id, boolean listed, JwtPrincipal principal) {
        AgentRegistry agent = agentMapper.selectById(id);
        if (agent == null || "DELETED".equals(agent.getStatus())) throw new BusinessException(404, "Agent 不存在");
        if (listed && !"PUBLISHED".equals(agent.getStatus())) {
            throw new BusinessException(400, "请先发布 Agent 后再上架广场");
        }
        AgentRegistry patch = new AgentRegistry();
        patch.setId(id);
        patch.setListed(listed ? 1 : 0);
        agentMapper.updateById(patch);
        auditService.record(principal.userId(), listed ? "agent.square.list" : "agent.square.unlist",
                "agent", String.valueOf(id), "SUCCESS", Map.of("name", agent.getName()));
        return Map.of("listed", listed);
    }

    // ---------- helpers ----------

    private AgentRegistry publishedAgent(Long id) {
        AgentRegistry a = agentMapper.selectById(id);
        if (a == null || "DELETED".equals(a.getStatus())) throw new BusinessException(404, "Agent 不存在");
        if (!"PUBLISHED".equals(a.getStatus())) throw new BusinessException(400, "该 Agent 尚未发布");
        return a;
    }

    private AgentVersion latestVersion(Long agentId) {
        return versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, agentId)
                        .orderByDesc(AgentVersion::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> card(AgentRegistry a, JwtPrincipal principal) {
        AgentVersion v = latestVersion(a.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("description", a.getDescription());
        m.put("avatarUrl", a.getAvatarUrl());
        m.put("category", a.getCategory());
        m.put("tags", fromJsonValue(a.getTags()));
        m.put("publisher", publisherName(a.getOwnerId()));
        m.put("publishedAt", v == null ? null : v.getCreatedAt());
        m.put("quality", metrics(a.getId()));
        long favCount = favoriteMapper.selectCount(new LambdaQueryWrapper<AgentFavorite>()
                .eq(AgentFavorite::getAgentId, a.getId()));
        m.put("favoriteCount", favCount);
        m.put("favorite", favoriteMapper.selectCount(new LambdaQueryWrapper<AgentFavorite>()
                .eq(AgentFavorite::getAgentId, a.getId())
                .eq(AgentFavorite::getUserId, principal.userId())) > 0);
        m.put("listed", a.getListed() != null && a.getListed() == 1);
        return m;
    }

    private Map<String, Object> metrics(Long agentId) {
        long calls = traceMapper.selectCount(new LambdaQueryWrapper<com.platform.observability.entity.AgentTrace>()
                .eq(com.platform.observability.entity.AgentTrace::getAgentId, agentId));
        long success = traceMapper.selectCount(new LambdaQueryWrapper<com.platform.observability.entity.AgentTrace>()
                .eq(com.platform.observability.entity.AgentTrace::getAgentId, agentId)
                .eq(com.platform.observability.entity.AgentTrace::getStatus, "SUCCESS"));
        long usage = conversationMapper.selectCount(new LambdaQueryWrapper<com.platform.conversation.entity.Conversation>()
                .eq(com.platform.conversation.entity.Conversation::getAgentId, agentId));
        List<AgentRating> ratings = ratingMapper.selectList(new LambdaQueryWrapper<AgentRating>()
                .eq(AgentRating::getAgentId, agentId));
        double avg = ratings.isEmpty() ? 0
                : ratings.stream().mapToInt(r -> r.getScore() == null ? 0 : r.getScore()).average().orElse(0);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("calls", calls);
        q.put("usageCount", usage);
        q.put("successRate", calls == 0 ? null : Math.round(success * 1000.0 / calls) / 10.0);
        q.put("ratingAvg", ratings.isEmpty() ? null : Math.round(avg * 10) / 10.0);
        q.put("ratingCount", ratings.size());
        return q;
    }

    private List<Map<String, Object>> ratings(Long agentId, JwtPrincipal principal) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentRating r : ratingMapper.selectList(new LambdaQueryWrapper<AgentRating>()
                .eq(AgentRating::getAgentId, agentId)
                .orderByDesc(AgentRating::getUpdatedAt)
                .last("LIMIT 20"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("userId", r.getUserId());
            m.put("userName", publisherName(r.getUserId()));
            m.put("score", r.getScore());
            m.put("comment", r.getComment());
            m.put("reply", r.getReply());
            m.put("replyAt", r.getReplyAt());
            m.put("createdAt", r.getCreatedAt());
            m.put("mine", r.getUserId().equals(principal.userId()));
            result.add(m);
        }
        return result;
    }

    private boolean used(Long agentId, Long userId) {
        long conversations = conversationMapper.selectCount(new LambdaQueryWrapper<com.platform.conversation.entity.Conversation>()
                .eq(com.platform.conversation.entity.Conversation::getAgentId, agentId)
                .eq(com.platform.conversation.entity.Conversation::getUserId, userId));
        if (conversations > 0) return true;
        return traceMapper.selectCount(new LambdaQueryWrapper<com.platform.observability.entity.AgentTrace>()
                .eq(com.platform.observability.entity.AgentTrace::getAgentId, agentId)
                .eq(com.platform.observability.entity.AgentTrace::getUserId, userId)) > 0;
    }

    private boolean usable(AgentRegistry agent, JwtPrincipal principal) {
        if (!"PRIVATE".equals(agent.getVisibility())) return true;
        if (agent.getOwnerId().equals(principal.userId())) return true;
        if (principal.roles().stream().anyMatch(r -> r.equals("ADMIN") || r.equals("OWNER"))) return true;
        return grantMapper.selectCount(new LambdaQueryWrapper<AgentUserGrant>()
                .eq(AgentUserGrant::getAgentId, agent.getId())
                .eq(AgentUserGrant::getUserId, principal.userId())) > 0;
    }

    private String publisherName(Long userId) {
        if (userId == null) return null;
        SysUser user = userMapper.selectById(userId);
        if (user == null) return null;
        return user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? user.getUsername() : user.getDisplayName();
    }

    private double hotScore(Map<?, ?> quality) {
        if (quality == null) return 0;
        return num(quality.get("usageCount")) * 2 + num(quality.get("calls"))
                + num(quality.get("ratingCount")) * 5;
    }

    private long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase().contains(keyword.toLowerCase());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Object fromJsonValue(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
