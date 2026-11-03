package com.platform.extension.agri;

import com.platform.agent.entity.AgentRegistry;
import com.platform.extension.agent.AgentExtensionService;
import com.platform.extension.agent.AgentRequestGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 农业场景的可选请求护栏实现。
 *
 * <p>该类属于业务扩展，不进入平台通用服务；只有 Agent 配置
 * {@code agentCapabilities.extensions} 包含 {@link #EXTENSION_ID} 时才生效。</p>
 */
@Service
@RequiredArgsConstructor
public class AgriSafetyGuard implements AgentRequestGuard {

    public static final String EXTENSION_ID = "agri-safety";

    private final AgentExtensionService extensions;

    private static final Pattern RANDOM_PESTICIDE = Pattern.compile(
            "(随便|随意|直接).{0,20}(推荐|来|给).{0,20}(农药|药)|(推荐|来|给).{0,8}农药");

    private static final Pattern YIELD_GUARANTEE = Pattern.compile(
            "(保证|承诺|一定|肯定|可以|能).{0,20}(增产|增收).{0,10}\\d+\\s*%|(增产|增收).{0,10}\\d+\\s*%");

    private static final Pattern CURE_GUARANTEE = Pattern.compile(
            "(保证|承诺|一定|肯定).{0,20}(治好|治愈|根治)|治愈率\\s*\\d+\\s*%");

    private static final Pattern UNSUPPORTED_CROP = Pattern.compile("榴莲|山竹|火龙果|菠萝蜜");

    private static final Pattern VAGUE_CROP_SICK = Pattern.compile(
            "我的作物.{0,8}(生病|病了|黄了|不好)|作物生病了怎么办");

    @Override
    public GuardResult check(AgentRegistry agent, String message) {
        if (!extensions.enabled(agent, EXTENSION_ID) || message == null || message.isBlank()) {
            return null;
        }
        String q = message.trim();

        if (RANDOM_PESTICIDE.matcher(q).find()) {
            return new GuardResult(true,
                    "不能在没有作物、病害和当地登记信息的情况下推荐农药。请补充作物种类、具体症状、发病部位和所在地区；药剂必须按农药标签、登记作物和安全间隔期使用，建议咨询当地农技或植保站。",
                    "random-pesticide");
        }
        if (YIELD_GUARANTEE.matcher(q).find()) {
            return new GuardResult(true,
                    "不能承诺增产百分比。增产需要结合品种、土壤、水肥、病虫害和气候综合管理，不能编造保证增产的结果。请提供作物、当前生育期和主要问题，我可以给出可执行的管理方向。",
                    "yield-guarantee");
        }
        if (CURE_GUARANTEE.matcher(q).find()) {
            return new GuardResult(true,
                    "不能承诺治愈率或保证根治。病害能否控制取决于病原、品种、发病阶段和田间管理；请提供作物、症状照片和发病范围，建议结合当地植保部门指导防治，不要盲目用药。",
                    "cure-guarantee");
        }
        if (UNSUPPORTED_CROP.matcher(q).find()) {
            return new GuardResult(true,
                    "知识库暂未检索到该作物病害的直接依据，不能把其他作物的防治方案套用过来。建议咨询当地农技或植保部门；如能提供作物、症状照片和分布情况，我可以帮助梳理通用排查方向。",
                    "unsupported-crop");
        }
        if (VAGUE_CROP_SICK.matcher(q).find()) {
            return new GuardResult(true,
                    "请先补充作物种类、发病部位、症状照片、分布范围、当前生育期和近期用药/天气情况。先不要盲目施肥或打药；建议联系当地农技站或植保站做现场诊断。",
                    "vague-symptom");
        }
        return null;
    }
}
