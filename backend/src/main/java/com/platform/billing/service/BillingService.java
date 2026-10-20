package com.platform.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.entity.BillingAccount;
import com.platform.billing.entity.BillingBill;
import com.platform.billing.entity.BillingRule;
import com.platform.billing.entity.BillingTransaction;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingAccountMapper;
import com.platform.billing.mapper.BillingBillMapper;
import com.platform.billing.mapper.BillingRuleMapper;
import com.platform.billing.mapper.BillingTransactionMapper;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.conversation.entity.ChatAttachment;
import com.platform.conversation.mapper.ChatAttachmentMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysTenantQuota;
import com.platform.system.mapper.SysTenantQuotaMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计费管理（需求 3.11 / P1）：计费规则、账户与充值、月账单（生成/查询/明细/CSV）、配额查看与调整。
 * 余额口径：账户累计充值/调整 - billing_usage 全部成本；充值流水 append-only。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillingRuleMapper ruleMapper;
    private final BillingAccountMapper accountMapper;
    private final BillingTransactionMapper transactionMapper;
    private final BillingBillMapper billMapper;
    private final BillingUsageMapper usageMapper;
    private final AgentRegistryMapper agentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChatAttachmentMapper attachmentMapper;
    private final SysTenantQuotaMapper quotaMapper;
    private final AuditService auditService;

    @Value("${billing.low-balance-threshold:10}")
    private BigDecimal lowBalanceThreshold;

    // ---------- 计费规则 ----------

    public Map<String, Object> rules() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BillingRule r : ruleMapper.selectList(new LambdaQueryWrapper<BillingRule>()
                .orderByDesc(BillingRule::getId))) {
            list.add(ruleView(r));
        }
        return Map.of("list", list, "total", list.size());
    }

    @Transactional
    public Map<String, Object> createRule(Map<String, Object> req, JwtPrincipal principal) {
        Long tenantId = TenantContext.getTenantId();
        BillingRule r = new BillingRule();
        applyRule(r, req);
        ensureRuleUnique(tenantId, r.getModelName(), null);
        r.setTenantId(tenantId);
        r.setCreatedBy(principal.userId());
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        ruleMapper.insert(r);
        auditService.record(principal.userId(), "billing_rule.create", "billing_rule",
                String.valueOf(r.getId()), "SUCCESS", Map.of("model", String.valueOf(r.getModelName())));
        return Map.of("id", r.getId());
    }

    @Transactional
    public void updateRule(Long id, Map<String, Object> req, JwtPrincipal principal) {
        BillingRule r = ruleMapper.selectById(id);
        if (r == null) throw new BusinessException(404, "计费规则不存在");
        applyRule(r, req);
        ensureRuleUnique(r.getTenantId(), r.getModelName(), id);
        r.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(r);
        auditService.record(principal.userId(), "billing_rule.update", "billing_rule",
                String.valueOf(id), "SUCCESS", Map.of("model", String.valueOf(r.getModelName())));
    }

    @Transactional
    public void deleteRule(Long id, JwtPrincipal principal) {
        BillingRule r = ruleMapper.selectById(id);
        if (r == null) throw new BusinessException(404, "计费规则不存在");
        ruleMapper.deleteById(id);
        auditService.record(principal.userId(), "billing_rule.delete", "billing_rule",
                String.valueOf(id), "SUCCESS", Map.of("model", String.valueOf(r.getModelName())));
    }

    private void applyRule(BillingRule r, Map<String, Object> req) {
        if (req.containsKey("modelName")) {
            String model = str(req.get("modelName"));
            r.setModelName(model == null || model.isBlank() ? null : model.trim());
        }
        String mode = req.get("billingMode") == null ? "TOKEN"
                : String.valueOf(req.get("billingMode")).toUpperCase();
        if (!List.of("TOKEN", "COUNT").contains(mode)) throw new BusinessException(400, "计费方式仅支持 TOKEN/COUNT");
        r.setBillingMode(mode);
        r.setInputPrice(nonNegative(req.get("inputPrice"), "输入单价"));
        r.setOutputPrice(nonNegative(req.get("outputPrice"), "输出单价"));
        r.setCacheReadPrice(nonNegative(req.get("cacheReadPrice"), "缓存命中单价"));
        r.setCacheWritePrice(nonNegative(req.get("cacheWritePrice"), "缓存写入单价"));
        r.setPricePerCall(nonNegative(req.get("pricePerCall"), "单次价格"));
        String currency = req.get("currency") == null ? "CNY" : String.valueOf(req.get("currency")).toUpperCase();
        if (!currency.matches("[A-Z]{3}")) throw new BusinessException(400, "币种需为 3 位字母（如 CNY）");
        r.setCurrency(currency);
        String status = req.get("status") == null ? "ENABLED" : String.valueOf(req.get("status")).toUpperCase();
        if (!List.of("ENABLED", "DISABLED").contains(status)) throw new BusinessException(400, "非法状态");
        r.setStatus(status);
        if (r.getBillingMode().equals("TOKEN") && r.getInputPrice() == null) r.setInputPrice(BigDecimal.ZERO);
        if (r.getOutputPrice() == null) r.setOutputPrice(BigDecimal.ZERO);
        if (r.getPricePerCall() == null) r.setPricePerCall(BigDecimal.ZERO);
    }

    private BigDecimal nonNegative(Object v, String label) {
        if (v == null || String.valueOf(v).isBlank()) return BigDecimal.ZERO;
        try {
            BigDecimal d = new BigDecimal(String.valueOf(v));
            if (d.signum() < 0) throw new BusinessException(400, label + "不能为负数");
            return d;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, label + "格式错误");
        }
    }

    private void ensureRuleUnique(Long tenantId, String modelName, Long excludeId) {
        LambdaQueryWrapper<BillingRule> qw = new LambdaQueryWrapper<BillingRule>()
                .eq(BillingRule::getTenantId, tenantId)
                .ne(excludeId != null, BillingRule::getId, excludeId);
        if (modelName == null) qw.isNull(BillingRule::getModelName);
        else qw.eq(BillingRule::getModelName, modelName);
        if (ruleMapper.selectCount(qw) > 0) {
            throw new BusinessException(400, "该模型已配置计费规则（默认规则只能有一条）");
        }
    }

    private Map<String, Object> ruleView(BillingRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("modelName", r.getModelName());
        m.put("billingMode", r.getBillingMode());
        m.put("inputPrice", r.getInputPrice());
        m.put("outputPrice", r.getOutputPrice());
        m.put("cacheReadPrice", r.getCacheReadPrice());
        m.put("cacheWritePrice", r.getCacheWritePrice());
        m.put("pricePerCall", r.getPricePerCall());
        m.put("currency", r.getCurrency());
        m.put("status", r.getStatus());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    // ---------- 账户 / 充值 ----------

    public Map<String, Object> account() {
        Long tenantId = TenantContext.getTenantId();
        BillingAccount account = accountMapper.selectOne(new LambdaQueryWrapper<BillingAccount>()
                .eq(BillingAccount::getTenantId, tenantId));
        BigDecimal recharged = account == null || account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
        BigDecimal cost = totalCost(tenantId);
        BigDecimal balance = recharged.subtract(cost).setScale(4, RoundingMode.HALF_UP);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRecharged", recharged);
        m.put("totalCost", cost);
        m.put("balance", balance);
        m.put("currency", account == null ? "CNY" : account.getCurrency());
        m.put("threshold", lowBalanceThreshold);
        m.put("lowBalance", balance.compareTo(lowBalanceThreshold) < 0);
        return m;
    }

    public Map<String, Object> transactions(long page, long pageSize) {
        Long tenantId = TenantContext.getTenantId();
        long size = Math.min(Math.max(pageSize, 1), 100);
        long p = Math.max(page, 1);
        var result = transactionMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        p, size), new LambdaQueryWrapper<BillingTransaction>()
                        .eq(BillingTransaction::getTenantId, tenantId)
                        .orderByDesc(BillingTransaction::getId));
        return Map.of("list", result.getRecords(), "total", result.getTotal(), "page", p, "pageSize", size);
    }

    @Transactional
    public Map<String, Object> recharge(BigDecimal amount, String note, JwtPrincipal principal) {
        if (amount == null || amount.signum() <= 0) throw new BusinessException(400, "充值金额需大于 0");
        if (amount.compareTo(BigDecimal.valueOf(1_000_000)) > 0) throw new BusinessException(400, "单次充值金额过大");
        Long tenantId = TenantContext.getTenantId();
        BillingAccount account = accountMapper.selectOne(new LambdaQueryWrapper<BillingAccount>()
                .eq(BillingAccount::getTenantId, tenantId));
        if (account == null) {
            account = new BillingAccount();
            account.setTenantId(tenantId);
            account.setBalance(amount.setScale(4, RoundingMode.HALF_UP));
            account.setCurrency("CNY");
            account.setUpdatedAt(LocalDateTime.now());
            accountMapper.insert(account);
        } else {
            BigDecimal balance = (account.getBalance() == null ? BigDecimal.ZERO : account.getBalance())
                    .add(amount).setScale(4, RoundingMode.HALF_UP);
            account.setBalance(balance);
            account.setUpdatedAt(LocalDateTime.now());
            accountMapper.updateById(account);
        }
        BillingTransaction tx = new BillingTransaction();
        tx.setTenantId(tenantId);
        tx.setType("RECHARGE");
        tx.setAmount(amount.setScale(4, RoundingMode.HALF_UP));
        tx.setBalanceAfter(account.getBalance());
        tx.setOperatorId(principal.userId());
        tx.setNote(note == null || note.isBlank() ? "账户充值" : note.trim());
        tx.setCreatedAt(LocalDateTime.now());
        transactionMapper.insert(tx);
        auditService.record(principal.userId(), "billing.recharge", "billing_account",
                String.valueOf(account.getId()), "SUCCESS", Map.of("amount", amount.toPlainString()));
        return account();
    }

    // ---------- 账单 ----------

    public Map<String, Object> bills() {
        Long tenantId = TenantContext.getTenantId();
        List<BillingBill> rows = billMapper.selectList(new LambdaQueryWrapper<BillingBill>()
                .eq(BillingBill::getTenantId, tenantId)
                .orderByDesc(BillingBill::getPeriod));
        return Map.of("list", rows, "total", rows.size());
    }

    @Transactional
    public Map<String, Object> generateBill(String period, JwtPrincipal principal) {
        Long tenantId = TenantContext.getTenantId();
        String p = period == null || period.isBlank()
                ? YearMonth.now().toString() : period.trim();
        LocalDate start;
        LocalDate end;
        try {
            if (p.matches("\\d{4}-W\\d{2}")) {
                start = LocalDate.parse(p + "-1", java.time.format.DateTimeFormatter.ISO_WEEK_DATE);
                end = start.plusDays(6);
            } else {
                YearMonth month = YearMonth.parse(p);
                start = month.atDay(1);
                end = month.atEndOfMonth();
            }
        } catch (Exception e) {
            throw new BusinessException(400, "账期格式应为 yyyy-MM 或 yyyy-Www");
        }
        List<BillingUsage> rows = usageMapper.selectList(new LambdaQueryWrapper<BillingUsage>()
                .eq(BillingUsage::getTenantId, tenantId)
                .ge(BillingUsage::getStatDate, start)
                .le(BillingUsage::getStatDate, end));
        long in = 0, out = 0, calls = rows.size();
        BigDecimal cost = BigDecimal.ZERO;
        for (BillingUsage u : rows) {
            in += u.getInputTokens() == null ? 0 : u.getInputTokens();
            out += u.getOutputTokens() == null ? 0 : u.getOutputTokens();
            cost = cost.add(u.getCost() == null ? BigDecimal.ZERO : u.getCost());
        }
        BillingBill bill = billMapper.selectOne(new LambdaQueryWrapper<BillingBill>()
                .eq(BillingBill::getTenantId, tenantId)
                .eq(BillingBill::getPeriod, p));
        boolean created = bill == null;
        if (created) {
            bill = new BillingBill();
            bill.setTenantId(tenantId);
            bill.setPeriod(p);
    bill.setGeneratedAt(LocalDateTime.now());
        }
        bill.setInputTokens(in);
        bill.setOutputTokens(out);
        bill.setTotalTokens(in + out);
        bill.setCalls(calls);
        bill.setCost(cost.setScale(4, RoundingMode.HALF_UP));
        bill.setStatus("GENERATED");
        bill.setGeneratedBy(principal.userId());
        bill.setGeneratedAt(LocalDateTime.now());
        if (created) billMapper.insert(bill);
        else billMapper.updateById(bill);
        auditService.record(principal.userId(), "billing.bill.generate", "billing_bill",
                String.valueOf(bill.getId()), "SUCCESS", Map.of("period", p, "cost", bill.getCost().toPlainString()));
        return Map.of("id", bill.getId(), "period", p, "cost", bill.getCost(), "calls", calls);
    }

    public Map<String, Object> billDetail(Long id, long page, long pageSize) {
        BillingBill bill = billMapper.selectById(id);
        if (bill == null) throw new BusinessException(404, "账单不存在");
        LocalDate[] range = billRange(bill.getPeriod());
        long size = Math.min(Math.max(pageSize, 1), 100);
        long p = Math.max(page, 1);
        var detail = usageMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        p, size), new LambdaQueryWrapper<BillingUsage>()
                        .eq(BillingUsage::getTenantId, bill.getTenantId())
                        .ge(BillingUsage::getStatDate, range[0])
                        .le(BillingUsage::getStatDate, range[1])
                        .orderByDesc(BillingUsage::getId));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bill", bill);
        m.put("list", detail.getRecords());
        m.put("total", detail.getTotal());
        m.put("page", p);
        m.put("pageSize", size);
        return m;
    }

    public CsvFile exportCsv(Long id) {
        BillingBill bill = billMapper.selectById(id);
        if (bill == null) throw new BusinessException(404, "账单不存在");
        LocalDate[] range = billRange(bill.getPeriod());
        List<BillingUsage> rows = usageMapper.selectList(new LambdaQueryWrapper<BillingUsage>()
                .eq(BillingUsage::getTenantId, bill.getTenantId())
                .ge(BillingUsage::getStatDate, range[0])
                .le(BillingUsage::getStatDate, range[1])
                .orderByAsc(BillingUsage::getId));
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("时间,来源,Agent,知识库,模型,输入Token,输出Token,成本(元)\n");
        for (BillingUsage u : rows) {
            sb.append(csv(u.getCreatedAt() == null ? "" : u.getCreatedAt().toString())).append(',')
                    .append(csv(u.getSource())).append(',')
                    .append(u.getAgentId() == null ? "" : u.getAgentId()).append(',')
                    .append(u.getKnowledgeBaseId() == null ? "" : u.getKnowledgeBaseId()).append(',')
                    .append(csv(u.getModelName())).append(',')
                    .append(u.getInputTokens() == null ? 0 : u.getInputTokens()).append(',')
                    .append(u.getOutputTokens() == null ? 0 : u.getOutputTokens()).append(',')
                    .append(u.getCost() == null ? "0" : u.getCost().toPlainString()).append('\n');
        }
        return new CsvFile("bill-" + bill.getPeriod() + ".csv",
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public record CsvFile(String filename, byte[] content) {
    }

    // ---------- 配额 ----------

    public Map<String, Object> quota() {
        Long tenantId = TenantContext.getTenantId();
        SysTenantQuota q = quotaMapper.selectOne(new LambdaQueryWrapper<SysTenantQuota>()
                .eq(SysTenantQuota::getTenantId, tenantId));
        long agents = agentMapper.selectCount(new LambdaQueryWrapper<AgentRegistry>()
                .ne(AgentRegistry::getStatus, "DELETED"));
        long kbs = kbMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>());
        LocalDate today = LocalDate.now();
        long tokensToday = 0;
        for (BillingUsage u : usageMapper.selectList(new LambdaQueryWrapper<BillingUsage>()
                .eq(BillingUsage::getStatDate, today))) {
            tokensToday += (u.getInputTokens() == null ? 0 : u.getInputTokens())
                    + (u.getOutputTokens() == null ? 0 : u.getOutputTokens());
        }
        long storage = 0;
        for (ChatAttachment a : attachmentMapper.selectList(new LambdaQueryWrapper<>())) {
            storage += a.getSizeBytes() == null ? 0 : a.getSizeBytes();
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("agents", agents);
        usage.put("knowledgeBases", kbs);
        usage.put("tokensToday", tokensToday);
        usage.put("storageBytes", storage);

        Map<String, Object> quota = new LinkedHashMap<>();
        if (q != null) {
            quota.put("maxAgents", q.getMaxAgents());
            quota.put("maxKnowledgeBases", q.getMaxKnowledgeBases());
            quota.put("maxTokensPerDay", q.getMaxTokensPerDay());
            quota.put("maxStorageBytes", q.getMaxStorageBytes());
            quota.put("maxApiCallsPerMin", q.getMaxApiCallsPerMin());
        }
        List<String> warnings = new ArrayList<>();
        if (q != null) {
            addWarning(warnings, "Agent", agents, q.getMaxAgents());
            addWarning(warnings, "知识库", kbs, q.getMaxKnowledgeBases());
            addWarning(warnings, "今日 Token", tokensToday, q.getMaxTokensPerDay());
            addWarning(warnings, "存储", storage, q.getMaxStorageBytes());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quota", quota);
        m.put("usage", usage);
        m.put("warnings", warnings);
        return m;
    }

    @Transactional
    public Map<String, Object> updateQuota(Map<String, Object> req, JwtPrincipal principal) {
        Long tenantId = TenantContext.getTenantId();
        SysTenantQuota q = quotaMapper.selectOne(new LambdaQueryWrapper<SysTenantQuota>()
                .eq(SysTenantQuota::getTenantId, tenantId));
        boolean created = q == null;
        if (created) {
            q = new SysTenantQuota();
            q.setTenantId(tenantId);
        }
        q.setMaxAgents(intValue(req.get("maxAgents"), q.getMaxAgents() == null ? 10 : q.getMaxAgents(), "maxAgents"));
        q.setMaxKnowledgeBases(intValue(req.get("maxKnowledgeBases"),
                q.getMaxKnowledgeBases() == null ? 5 : q.getMaxKnowledgeBases(), "maxKnowledgeBases"));
        q.setMaxTokensPerDay(longValue(req.get("maxTokensPerDay"),
                q.getMaxTokensPerDay() == null ? 1_000_000 : q.getMaxTokensPerDay(), "maxTokensPerDay"));
        q.setMaxStorageBytes(longValue(req.get("maxStorageBytes"),
                q.getMaxStorageBytes() == null ? 10L * 1024 * 1024 * 1024 : q.getMaxStorageBytes(), "maxStorageBytes"));
        q.setMaxApiCallsPerMin(intValue(req.get("maxApiCallsPerMin"),
                q.getMaxApiCallsPerMin() == null ? 60 : q.getMaxApiCallsPerMin(), "maxApiCallsPerMin"));
        if (created) quotaMapper.insert(q);
        else quotaMapper.updateById(q);
        auditService.record(principal.userId(), "billing.quota.update", "sys_tenant_quota",
                String.valueOf(q.getId()), "SUCCESS", Map.of());
        return quota();
    }

    private void addWarning(List<String> warnings, String label, long used, long max) {
        if (max <= 0) return;
        double ratio = (double) used / max;
        if (ratio >= 0.8) {
            warnings.add(String.format("%s 使用率 %.0f%%（%d/%d）%s", label, ratio * 100, used, max,
                    ratio >= 1 ? "，已超限" : "，接近上限"));
        }
    }

    private int intValue(Object v, int def, String label) {
        if (v == null || String.valueOf(v).isBlank()) return def;
        try {
            int i = Integer.parseInt(String.valueOf(v));
            if (i < 0) throw new BusinessException(400, label + "不能为负数（0=不限制）");
            return i;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, label + "格式错误");
        }
    }

    private long longValue(Object v, long def, String label) {
        if (v == null || String.valueOf(v).isBlank()) return def;
        try {
            long i = Long.parseLong(String.valueOf(v));
            if (i < 0) throw new BusinessException(400, label + "不能为负数（0=不限制）");
            return i;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, label + "格式错误");
        }
    }

    // ---------- helpers ----------

    private LocalDate[] billRange(String period) {
        if (period != null && period.matches("\\d{4}-W\\d{2}")) {
            LocalDate start = LocalDate.parse(period + "-1", java.time.format.DateTimeFormatter.ISO_WEEK_DATE);
            return new LocalDate[]{start, start.plusDays(6)};
        }
        YearMonth month = YearMonth.parse(period);
        return new LocalDate[]{month.atDay(1), month.atEndOfMonth()};
    }

    private BigDecimal totalCost(Long tenantId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BillingUsage u : usageMapper.selectList(new LambdaQueryWrapper<BillingUsage>()
                .eq(BillingUsage::getTenantId, tenantId))) {
            sum = sum.add(u.getCost() == null ? BigDecimal.ZERO : u.getCost());
        }
        return sum.setScale(4, RoundingMode.HALF_UP);
    }

    private String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v + "\"" : v;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
