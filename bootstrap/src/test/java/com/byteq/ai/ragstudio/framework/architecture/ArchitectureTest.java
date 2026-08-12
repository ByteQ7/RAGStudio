package com.byteq.ai.ragstudio.framework.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构分层约束测试（ArchUnit）
 *
 * <p>将项目分层规范固化为可执行的架构测试，任何违反规则的代码在 CI 构建时直接失败。
 * 分层约定：</p>
 * <ul>
 *   <li><b>Web 层</b>（{@code ..controller..}）：只做参数校验与结果组装，禁止直接访问数据库 Mapper</li>
 *   <li><b>引擎层</b>（{@code rag.core..} / {@code ingestion.node..} / {@code ingestion.engine..} /
 *       {@code core.parser..} / {@code core.chunk..}）：核心算法与处理逻辑，禁止反向依赖 Web 层</li>
 *   <li><b>数据层</b>（{@code ..dao.mapper..}）：纯数据访问，禁止依赖 service / controller / 核心引擎</li>
 *   <li><b>服务层</b>（{@code ..service..}）：只允许引用 controller 的 vo/request 数据对象，
 *       禁止依赖 controller 类本身</li>
 *   <li><b>引擎内部</b>：rag.core 与 ingestion 的子包之间不允许循环依赖</li>
 * </ul>
 *
 * <p>注意：跨 feature 的已知依赖（如 core ↔ knowledge ↔ rag 的协作关系）属于
 * 垂直切片架构的正常协作，不在本测试约束范围内。</p>
 */
@AnalyzeClasses(packages = "com.byteq.ai.ragstudio", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    /**
     * R1: Controller 禁止直接访问数据库 Mapper
     * <p>数据访问必须通过 Service 层，保证权限、缓存、事务等逻辑在服务层统一收口。</p>
     */
    @ArchTest
    static final ArchRule controllersMustNotAccessMapper = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..dao.mapper..")
            .as("Controller 层禁止直接访问数据库 Mapper，必须通过 Service 层");

    /**
     * R2: 引擎层禁止依赖 Web 层
     * <p>rag.core 引擎、ingestion 节点引擎、文档解析/分块核心逻辑
     * 不得依赖 Controller 或 framework.web，保持引擎可独立测试与复用。</p>
     */
    @ArchTest
    static final ArchRule engineMustNotDependOnWebLayer = noClasses()
            .that().resideInAnyPackage(
                    "..rag.core..",
                    "..ingestion.node..",
                    "..ingestion.engine..",
                    "..core.parser..",
                    "..core.chunk..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controller..",
                    "com.byteq.ai.ragstudio.framework.web..")
            .as("引擎层禁止依赖 Web 层（controller / framework.web）");

    /**
     * R3: Mapper 层是数据访问叶子节点
     * <p>Mapper 只允许依赖实体与框架基础设施，禁止反向依赖 service / controller / 核心引擎，
     * 防止数据访问层向上渗透业务逻辑。</p>
     */
    @ArchTest
    static final ArchRule mappersMustBeLeaf = noClasses()
            .that().resideInAPackage("..dao.mapper..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.byteq.ai.ragstudio..service..",
                    "com.byteq.ai.ragstudio..controller..",
                    "com.byteq.ai.ragstudio..core..",
                    "com.byteq.ai.ragstudio..mq..")
            .as("Mapper 层禁止依赖 service / controller / 核心引擎");

    /**
     * R4: rag.core 引擎子包禁止循环依赖
     * <p>agent / skill / mcp / retrieve / prompt / memory / tool 等子包之间必须单向依赖，
     * 出现循环说明抽象放错了位置（如 Tool 抽象曾位于 agent 包导致 agent ↔ skill 循环）。</p>
     */
    @ArchTest
    static final ArchRule ragCoreSlicesFreeOfCycles = SlicesRuleDefinition.slices()
            .matching("..rag.core.(*)..")
            .should().beFreeOfCycles()
            .as("rag.core 引擎子包之间不允许循环依赖");

    /**
     * R5: ingestion 子包禁止循环依赖
     * <p>service 引用 controller.vo / controller.request 属于刻意设计的 DTO 模式，
     * 该类跨层引用不参与环检测。</p>
     */
    @ArchTest
    static final ArchRule ingestionSlicesFreeOfCycles = SlicesRuleDefinition.slices()
            .matching("..ingestion.(*)..")
            .should().beFreeOfCycles()
            .ignoreDependency(
                    JavaClass.Predicates.resideInAPackage("..ingestion.service.."),
                    JavaClass.Predicates.resideInAPackage("..ingestion.controller.vo.."))
            .ignoreDependency(
                    JavaClass.Predicates.resideInAPackage("..ingestion.service.."),
                    JavaClass.Predicates.resideInAPackage("..ingestion.controller.request.."))
            .as("ingestion 子包之间不允许循环依赖（service → controller 的 DTO 引用除外）");

    /**
     * R6: Service 层禁止依赖 Controller 类
     * <p>允许引用 controller.vo / controller.request 数据对象（DTO 模式），
     * 但禁止依赖控制器类本身，保证服务层可脱离 Web 容器测试。</p>
     */
    @ArchTest
    static final ArchRule servicesMustNotDependOnControllerClasses = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat(
                    JavaClass.Predicates.resideInAPackage("..controller..")
                            .and(DescribedPredicate.not(JavaClass.Predicates.resideInAPackage("..controller.vo..")))
                            .and(DescribedPredicate.not(JavaClass.Predicates.resideInAPackage("..controller.request.."))))
            .as("Service 层禁止依赖 Controller 类（controller.vo / controller.request 除外）");
}
