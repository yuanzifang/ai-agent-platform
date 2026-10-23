package com.platform.common.exception;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后端高频错误消息英文翻译。通过 Accept-Language: en* 开启；
 * 未命中词典时保留原文，避免生硬机翻或误伤业务文案。
 */
@Component
public class BackendMessageTranslator {

    private static final Map<String, String> EXACT = new LinkedHashMap<>();
    private static final Map<String, String> NOUNS = new LinkedHashMap<>();
    private static final Pattern REQUIRED = Pattern.compile("^(.+?)不能为空.*$");
    private static final Pattern NOT_FOUND = Pattern.compile("^(.+?)不存在(?:：(.+))?$");
    private static final Pattern EXISTS = Pattern.compile("^(.+?)已存在(?:：(.+))?$");
    private static final Pattern FORMAT = Pattern.compile("^(.+?)格式错误(?:：(.+))?$");
    private static final Pattern TOO_LONG = Pattern.compile("^(.+?)过长.*$");
    private static final Pattern TOO_MANY = Pattern.compile("^(.+?)不能超过(\\d+).*$");

    static {
        EXACT.put("用户名或密码错误", "Invalid username or password");
        EXACT.put("用户名不能为空", "Username is required");
        EXACT.put("密码不能为空", "Password is required");
        EXACT.put("租户标识不能为空", "Tenant key is required");
        EXACT.put("租户不存在或已停用", "Tenant not found or disabled");
        EXACT.put("该账号已被禁用", "This account is disabled");
        EXACT.put("验证码错误", "Invalid verification code");
        EXACT.put("动态码错误", "Invalid authenticator code");
        EXACT.put("恢复码无效", "Invalid recovery code");
        EXACT.put("邀请链接无效或已过期", "Invitation link is invalid or expired");
        EXACT.put("无操作权限（权限点校验未通过）", "Permission denied");
        EXACT.put("接口不存在", "API not found");
        EXACT.put("参数错误", "Invalid parameter");
        EXACT.put("系统繁忙，请稍后重试", "System busy; please try again later");
        EXACT.put("系统异常，请稍后重试", "System error; please try again later");
        EXACT.put("请求过于频繁，请稍后再试", "Too many requests; please try again later");
        EXACT.put("不能审批自己发起的工作流运行，请由其他有权限成员处理",
                "You cannot approve your own workflow run; ask another authorized member");
        EXACT.put("该审批已处理", "This approval has already been processed");
        EXACT.put("该审批已超时，运行已终止", "This approval expired and the run was terminated");
        EXACT.put("该工作流已停用", "This workflow is disabled");
        EXACT.put("该 Agent 尚未配置工作流", "This Agent has no workflow configured");
        EXACT.put("工作流至少包含 START/END 节点", "Workflow must contain START and END nodes");
        EXACT.put("必须包含 START 和 END 节点", "Workflow must contain START and END nodes");
        EXACT.put("运行输入不能为空", "Run input is required");
        EXACT.put("该知识库被引用，请确认后强制删除", "Knowledge base is referenced; confirm force delete");
        EXACT.put("未登录或登录已过期", "Not signed in or session expired");
        EXACT.put("Token 无效或已过期", "Token is invalid or expired");
        EXACT.put("当前账号无权限访问该租户", "No access to this tenant");
        EXACT.put("配额不足", "Quota exceeded");
        EXACT.put("余额不足", "Insufficient balance");

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

    public String translate(String message) {
        if (message == null || message.isBlank() || !english()) return message;
        String exact = EXACT.get(message);
        if (exact != null) return exact;
        String normalized = message.trim();

        Matcher m = REQUIRED.matcher(normalized);
        if (m.matches()) return noun(m.group(1)) + " is required";

        m = NOT_FOUND.matcher(normalized);
        if (m.matches()) {
            String suffix = m.group(2) == null || m.group(2).isBlank() ? "" : ": " + m.group(2);
            return noun(m.group(1)) + " not found" + suffix;
        }

        m = EXISTS.matcher(normalized);
        if (m.matches()) {
            String suffix = m.group(2) == null || m.group(2).isBlank() ? "" : ": " + m.group(2);
            return noun(m.group(1)) + " already exists" + suffix;
        }

        m = FORMAT.matcher(normalized);
        if (m.matches()) return noun(m.group(1)) + " format is invalid";

        m = TOO_LONG.matcher(normalized);
        if (m.matches()) return noun(m.group(1)) + " is too long";

        m = TOO_MANY.matcher(normalized);
        if (m.matches()) return noun(m.group(1)) + " must not exceed " + m.group(2);

        if (normalized.startsWith("无操作权限") || normalized.startsWith("没有权限")
                || normalized.startsWith("无权限")) return "Permission denied";
        if (normalized.startsWith("系统繁忙") || normalized.startsWith("系统异常")) {
            return "System error; please try again later";
        }
        return message;
    }

    private String noun(String raw) {
        String key = raw == null ? "" : raw.trim();
        String direct = NOUNS.get(key);
        if (direct != null) return direct;
        // 常见复合词：知识库名称 / 模型 ID 等。按最长后缀替换一次。
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
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                String lang = sra.getRequest().getHeader("Accept-Language");
                return lang != null && lang.toLowerCase().startsWith("en");
            }
        } catch (Exception ignored) { }
        return false;
    }
}
