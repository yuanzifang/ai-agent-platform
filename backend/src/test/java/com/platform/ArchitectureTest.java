package com.platform;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构边界守卫（差距清单第九章）：钉住"可摘除性"，让将来的拆分是搬走干净模块而非解耦手术。
 * - 网关（com.platform.gateway）：不被任何模块依赖（入口层），且只依赖 common/security/system(审计)，
 *   不依赖业务模块——满足时可独立部署；
 * - 部署运行时（com.platform.deploy）：不被业务模块反向依赖（依赖 agent 领域是其职责本身）。
 * 新增模块或引入新依赖时若破坏上述约束，本测试失败即评审点。
 */
class ArchitectureTest {

    private static final com.tngtech.archunit.core.domain.JavaClasses CLASSES =
            new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("com.platform");

    /** 业务模块清单（system=审计/配置横切、security=认证基建、common=地基，不在此列）。 */
    private static final String[] BUSINESS = {
            "com.platform.agent..", "com.platform.knowledge..", "com.platform.conversation..",
            "com.platform.billing..", "com.platform.eval..", "com.platform.workflow..",
            "com.platform.capability..", "com.platform.model.."
    };

    @Test
    void gateway不被任何模块依赖() {
        ArchRule rule = noClasses().that().resideOutsideOfPackage("com.platform.gateway..")
                .should().dependOnClassesThat().resideInAnyPackage("com.platform.gateway..");
        rule.allowEmptyShould(true).check(CLASSES);
    }

    @Test
    void gateway保持薄层不依赖业务模块() {
        ArchRule rule = noClasses().that().resideInAnyPackage("com.platform.gateway..")
                .should().dependOnClassesThat().resideInAnyPackage(BUSINESS);
        rule.allowEmptyShould(true).check(CLASSES);
    }

    @Test
    void deploy运行时仅允许agent生命周期依赖() {
        // AgentService 级联下线部署属合法业务依赖；deploy 摘出独立部署前，
        // 需先将该级联改为事件/接口解耦，并把 agent 移出豁免清单
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.platform.deploy..")
                .and().resideOutsideOfPackage("com.platform.agent..")
                .should().dependOnClassesThat().resideInAnyPackage("com.platform.deploy..");
        rule.allowEmptyShould(true).check(CLASSES);
    }
}
