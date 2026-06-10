package com.byteq.ai.ragstudio.framework.idempotent;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.ArrayUtil;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * SpEL 表达式解析工具类
 *
 * <p>用于解析幂等注解中定义的 SpEL 表达式，根据方法参数动态生成唯一的幂等 Key。
 * 使用 Spring 的 {@link SpelExpressionParser} 进行表达式解析，
 * 并通过 {@link DefaultParameterNameDiscoverer} 获取方法参数名称。</p>
 *
 * <p>功能特点：</p>
 * <ul>
 *   <li>自动识别 SpEL 表达式（包含 {@code #} 或 {@code T(} 前缀）</li>
 *   <li>非 SpEL 表达式直接返回原字符串</li>
 *   <li>支持复杂的方法参数引用，如 {@code #order.orderId}</li>
 * </ul>
 */
public final class SpELUtil {

    /**
     * Spring 默认参数名发现器，通过字节码调试信息获取方法参数名称
     */
    private static final DefaultParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /**
     * SpEL 表达式解析器
     */
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    /**
     * 解析 SpEL 表达式，若不是 SpEL 则返回原字符串
     * <p>校验传入的字符串是否为有效的 SpEL 表达式（包含 {@code #} 或 {@code T(} 特征），
     * 如果是则进行解析，否则直接返回原字符串。</p>
     *
     * @param spEl       SpEL 表达式或普通字符串
     * @param method     目标方法，用于获取参数名称
     * @param contextObj 方法参数值数组
     * @return 解析后的值（如果非 SpEL 则返回原字符串）
     */
    public static Object parseKey(String spEl, Method method, Object[] contextObj) {
        // 判断是否为 SpEL 表达式
        List<String> spELFlag = ListUtil.of("#", "T(");
        Optional<String> optional = spELFlag.stream().filter(spEl::contains).findFirst();
        if (optional.isPresent()) {
            return parse(spEl, method, contextObj);
        }
        return spEl;
    }

    /**
     * 解析 SpEL 表达式并返回计算结果
     * <p>将方法参数名与参数值绑定到 SpEL 评估上下文中，然后计算表达式结果。</p>
     *
     * @param spEl       SpEL 表达式字符串
     * @param method     目标方法，用于获取参数名列表
     * @param contextObj 方法参数值数组
     * @return SpEL 表达式的计算结果
     */
    public static Object parse(String spEl, Method method, Object[] contextObj) {
        // 解析 SpEL 表达式
        Expression exp = EXPRESSION_PARSER.parseExpression(spEl);
        // 获取方法参数名称列表
        String[] params = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        // 构建 SpEL 评估上下文
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (ArrayUtil.isNotEmpty(params) && contextObj != null) {
            // 将方法参数绑定到上下文变量中
            int bound = Math.min(params.length, contextObj.length);
            for (int len = 0; len < bound; len++) {
                context.setVariable(params[len], contextObj[len]);
            }
        }
        return exp.getValue(context);
    }
}
