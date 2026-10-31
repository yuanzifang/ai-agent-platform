package com.platform.common.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后端高频业务消息翻译。语言优先级：请求 Accept-Language → LocaleContextHolder → 中文。
 *
 * <p>错误文案的资源在 classpath:i18n/messages*.properties；未知的业务消息保持原文，
 * 避免把项目/租户自定义名称机翻成错误内容。动态模式（不能为空/不存在等）使用
 * MessageFormat 占位符，名词词典覆盖高频领域词。</p>
 */
@Component
@RequiredArgsConstructor
public class BackendMessageTranslator {

    private static final Map<String, String> EXACT_KEYS = new LinkedHashMap<>();
    private static final Map<String, String> NOUNS = new LinkedHashMap<>();
    private static final Pattern REQUIRED = Pattern.compile("^(.+?)不能为空.*$");
    private static final Pattern NOT_FOUND = Pattern.compile("^(.+?)不存在(?:：(.+))?$");
    private static final Pattern EXISTS = Pattern.compile("^(.+?)已存在(?:：(.+))?$");
    private static final Pattern FORMAT = Pattern.compile("^(.+?)格式错误(?:：(.+))?$");
    private static final Pattern TOO_LONG = Pattern.compile("^(.+?)过长.*$");
    private static final Pattern TOO_MANY = Pattern.compile("^(.+?)不能超过(\\d+).*$");

    static {
        EXACT_KEYS.put("用户名或密码错误", "error.auth.invalidCredentials");
        EXACT_KEYS.put("用户名不能为空", "error.auth.usernameRequired");
        EXACT_KEYS.put("密码不能为空", "error.auth.passwordRequired");
        EXACT_KEYS.put("租户标识不能为空", "error.auth.tenantRequired");
        EXACT_KEYS.put("租户不存在或已停用", "error.auth.tenantDisabled");
        EXACT_KEYS.put("该账号已被禁用", "error.auth.accountDisabled");
        EXACT_KEYS.put("验证码错误", "error.auth.codeInvalid");
        EXACT_KEYS.put("动态码错误", "error.auth.mfaInvalid");
        EXACT_KEYS.put("恢复码无效", "error.auth.recoveryInvalid");
        EXACT_KEYS.put("邀请链接无效或已过期", "error.auth.inviteInvalid");
        EXACT_KEYS.put("账号已锁定，请稍后再试", "error.auth.accountLocked");
        EXACT_KEYS.put("账号不属于该租户", "error.auth.tenantMismatch");
        EXACT_KEYS.put("MFA 未启用", "error.auth.mfaNotEnabled");
        EXACT_KEYS.put("MFA 验证码错误", "error.auth.mfaCodeInvalid");
        EXACT_KEYS.put("重置链接无效", "error.auth.resetInvalid");
        EXACT_KEYS.put("导入内容为空", "error.import.empty");
        EXACT_KEYS.put("CSV 缺少数据行", "error.import.csvMissing");
        EXACT_KEYS.put("无操作权限（权限点校验未通过）", "error.permission.denied");
        EXACT_KEYS.put("接口不存在", "error.api.notFound");
        EXACT_KEYS.put("参数错误", "error.param.invalid");
        EXACT_KEYS.put("系统繁忙，请稍后重试", "error.system.busy");
        EXACT_KEYS.put("系统异常，请稍后重试", "error.system.error");
        EXACT_KEYS.put("请求过于频繁，请稍后再试", "error.rateLimit");
        EXACT_KEYS.put("不能审批自己发起的工作流运行，请由其他有权限成员处理", "error.workflow.selfApproval");
        EXACT_KEYS.put("该审批已处理", "error.workflow.approvalHandled");
        EXACT_KEYS.put("该审批已超时，运行已终止", "error.workflow.approvalExpired");
        EXACT_KEYS.put("该工作流已停用", "error.workflow.disabled");
        EXACT_KEYS.put("工作流已停用", "error.workflow.disabled");
        EXACT_KEYS.put("该 Agent 尚未配置工作流", "error.workflow.noWorkflow");
        EXACT_KEYS.put("工作流至少包含 START/END 节点", "error.workflow.nodesRequired");
        EXACT_KEYS.put("必须包含 START 和 END 节点", "error.workflow.startEndRequired");
        EXACT_KEYS.put("运行输入不能为空", "error.workflow.inputRequired");
        EXACT_KEYS.put("运行输入过长（上限 10000 字）", "error.workflow.inputTooLong");
        EXACT_KEYS.put("审批任务不存在", "error.workflow.approvalNotFound");
        EXACT_KEYS.put("运行记录不存在", "error.workflow.runNotFound");
        EXACT_KEYS.put("运行状态不是待审批", "error.workflow.notWaitingApproval");
        EXACT_KEYS.put("STATE 节点必须配置 stateKey", "error.workflow.stateKeyRequired");
        EXACT_KEYS.put("条件判断来源仅支持 last/state/input", "error.workflow.conditionSource");
        EXACT_KEYS.put("条件判断来源为共享状态时必须配置 conditionStateKey", "error.workflow.conditionStateKey");
        EXACT_KEYS.put("工作流节点不能超过 30 个", "error.workflow.tooManyNodes");
        EXACT_KEYS.put("LOOP 节点未配置 bodyNode", "error.workflow.loopBodyRequired");
        EXACT_KEYS.put("PARALLEL 节点没有分支", "error.workflow.parallelNoBranches");
        EXACT_KEYS.put("并行分支全部失败", "error.workflow.parallelAllFailed");
        EXACT_KEYS.put("TOOL 节点未选择工具", "error.workflow.toolRequired");
        EXACT_KEYS.put("KNOWLEDGE 节点未选择知识库", "error.workflow.knowledgeRequired");
        EXACT_KEYS.put("只有 FAILED 运行可以恢复", "error.workflow.onlyFailedResume");
        EXACT_KEYS.put("运行没有可恢复的节点", "error.workflow.nothingToResume");
        EXACT_KEYS.put("当前节点可能产生外部副作用，请确认后显式允许恢复", "error.workflow.sideEffectsRequired");
        EXACT_KEYS.put("Graph 边不能超过 100 条", "error.workflow.graphTooManyEdges");
        EXACT_KEYS.put("Graph 边格式错误", "error.workflow.graphEdgeFormat");
        EXACT_KEYS.put("END 节点不能有出边", "error.workflow.endNoOutgoing");
        EXACT_KEYS.put("工作流定义不是合法 JSON", "error.workflow.invalidDefinition");
        EXACT_KEYS.put("该知识库被引用，请确认后强制删除", "error.kb.referenced");
        EXACT_KEYS.put("未登录或登录已过期", "error.auth.notLoggedIn");
        EXACT_KEYS.put("Token 无效或已过期", "error.auth.tokenInvalid");
        EXACT_KEYS.put("当前账号无权限访问该租户", "error.auth.tenantForbidden");
        EXACT_KEYS.put("配额不足", "error.quota.insufficient");
        EXACT_KEYS.put("余额不足", "error.balance.insufficient");

        NOUNS.put("用户名", "Username");
        NOUNS.put("密码", "Password");
        NOUNS.put("名称", "Name");
        NOUNS.put("描述", "Description");
        NOUNS.put("角色", "Role");
        NOUNS.put("租户", "Tenant");
        NOUNS.put("用户", "User");
        NOUNS.put("成员", "Member");
        NOUNS.put("模型", "Model");
        NOUNS.put("知识库", "Knowledge base");
        NOUNS.put("文档", "Document");
        NOUNS.put("分段", "Chunk");
        NOUNS.put("会话", "Conversation");
        NOUNS.put("消息", "Message");
        NOUNS.put("用例", "Case");
        NOUNS.put("数据集", "Dataset");
        NOUNS.put("工作流", "Workflow");
        NOUNS.put("节点", "Node");
        NOUNS.put("工具", "Tool");
        NOUNS.put("技能", "Skill");
        NOUNS.put("Agent", "Agent");
        NOUNS.put("审批", "Approval");
        NOUNS.put("标题", "Title");
        NOUNS.put("内容", "Content");
        NOUNS.put("问题", "Question");
        NOUNS.put("答案", "Answer");
        NOUNS.put("文件", "File");
        NOUNS.put("参数", "Parameter");
    }

    private final MessageSource messageSource;

    public String translate(String message) {
        if (message == null || message.isBlank() || !english()) return message;
        String normalized = message.trim();

        String key = EXACT_KEYS.get(normalized);
        if (key != null) return resolve(key, message);

        Matcher m = REQUIRED.matcher(normalized);
        if (m.matches()) return resolve("error.required", message, noun(m.group(1)));

        m = NOT_FOUND.matcher(normalized);
        if (m.matches()) {
            String suffix = m.group(2) == null || m.group(2).isBlank() ? "" : ": " + m.group(2);
            return resolve("error.notFound", message, noun(m.group(1)), suffix);
        }

        m = EXISTS.matcher(normalized);
        if (m.matches()) {
            String suffix = m.group(2) == null || m.group(2).isBlank() ? "" : ": " + m.group(2);
            return resolve("error.exists", message, noun(m.group(1)), suffix);
        }

        m = FORMAT.matcher(normalized);
        if (m.matches()) return resolve("error.format", message, noun(m.group(1)));

        m = TOO_LONG.matcher(normalized);
        if (m.matches()) return resolve("error.tooLong", message, noun(m.group(1)));

        m = TOO_MANY.matcher(normalized);
        if (m.matches()) return resolve("error.tooMany", message, noun(m.group(1)), m.group(2));

        if (normalized.startsWith("无操作权限") || normalized.startsWith("没有权限")
                || normalized.startsWith("无权限")) return resolve("error.permission.denied", message);
        if (normalized.startsWith("系统繁忙") || normalized.startsWith("系统异常")) {
            return resolve("error.system.error", message);
        }
        return message;
    }

    private String resolve(String key, String fallback, Object... args) {
        try {
            return messageSource.getMessage(key, args.length == 0 ? null : args, fallback, currentLocale());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String noun(String raw) {
        String key = raw == null ? "" : raw.trim();
        String direct = NOUNS.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, String> e : NOUNS.entrySet()) {
            if (key.endsWith(e.getKey())) {
                String prefix = key.substring(0, key.length() - e.getKey().length()).trim();
                String translatedPrefix = NOUNS.getOrDefault(prefix, prefix);
                String suffix = e.getValue();
                if (!suffix.isEmpty()) suffix = Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
                return (translatedPrefix.isEmpty() ? "" : translatedPrefix + " ") + suffix;
            }
        }
        return key;
    }

    private boolean english() {
        return "en".equals(currentLocale().getLanguage());
    }

    private Locale currentLocale() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                String header = sra.getRequest().getHeader("Accept-Language");
                if (header != null && !header.isBlank()) {
                    String first = header.split(",")[0].trim();
                    return Locale.forLanguageTag(first.replace('_', '-'));
                }
            }
        } catch (Exception ignored) {
        }
        // 无请求上下文（异步/定时任务/单测）：默认中文（与文档优先级一致；
        // 不回落 JVM 默认 Locale，否则 CI/海外部署 en_US 下错误文案变英文且单测不确定）
        return Locale.SIMPLIFIED_CHINESE;
    }
}
