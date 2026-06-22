package com.byteq.ai.ragstudio.ingestion.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 条件评估器
 * 用于根据给定的 IngestionContext 上下文和 JsonNode 格式的条件配置来评估条件是否满足
 */
@Slf4j
@Component
public class ConditionEvaluator {

    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();

    public ConditionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 评估条件是否满足
     * <p>
     * 支持多种条件格式：
     * <ul>
     *   <li>null 或 null 节点 - 默认返回 true</li>
     *   <li>布尔值 - 直接返回</li>
     *   <li>字符串 - 作为 SpEL 表达式求值</li>
     *   <li>对象 - 支持 all（全部满足）、any（任一满足）、not（取反）、field（字段规则匹配）</li>
     * </ul>
     * </p>
     *
     * @param context   摄入上下文，作为条件评估的数据源
     * @param condition JSON 格式的条件配置
     * @return 条件是否满足
     */
    public boolean evaluate(IngestionContext context, JsonNode condition) {
        if (condition == null || condition.isNull()) {
            return true;
        }
        if (condition.isBoolean()) {
            return condition.asBoolean();
        }
        if (condition.isTextual()) {
            return evalSpel(context, condition.asText());
        }
        if (condition.isObject()) {
            if (condition.has("all")) {
                return evalAll(context, condition.get("all"));
            }
            if (condition.has("any")) {
                return evalAny(context, condition.get("any"));
            }
            if (condition.has("not")) {
                return !evaluate(context, condition.get("not"));
            }
            if (condition.has("field")) {
                return evalRule(context, condition);
            }
        }
        return true;
    }

    // 评估 "all" 条件：所有子条件都必须满足
    private boolean evalAll(IngestionContext context, JsonNode node) {
        if (node == null || !node.isArray()) {
            return true;
        }
        for (JsonNode item : node) {
            if (!evaluate(context, item)) {
                return false;
            }
        }
        return true;
    }

    // 评估 "any" 条件：任一子条件满足即可
    private boolean evalAny(IngestionContext context, JsonNode node) {
        if (node == null || !node.isArray()) {
            return true;
        }
        for (JsonNode item : node) {
            if (evaluate(context, item)) {
                return true;
            }
        }
        return false;
    }

    // 评估字段规则：读取上下文字段值并与目标值进行运算符比较
    private boolean evalRule(IngestionContext context, JsonNode node) {
        String field = node.path("field").asText(null);
        if (!StringUtils.hasText(field)) {
            return true;
        }
        String operator = node.path("operator").asText("eq");
        JsonNode valueNode = node.get("value");
        Object left = readField(context, field);
        Object right = valueNode == null ? null : objectMapper.convertValue(valueNode, Object.class);
        return compare(left, right, operator);
    }

    // 通过 BeanWrapper 从上下文中读取指定路径的属性值
    private Object readField(IngestionContext context, String path) {
        try {
            BeanWrapperImpl wrapper = new BeanWrapperImpl(context);
            return wrapper.getPropertyValue(path);
        } catch (Exception e) {
            log.debug("读取字段失败: path={}, {}", path, e.getMessage());
            return null;
        }
    }

    // 根据运算符类型对左右值进行比较，支持 eq/ne/in/contains/regex/gt/gte/lt/lte/exists/not_exists
    private boolean compare(Object left, Object right, String operator) {
        return switch (operator.toLowerCase()) {
            case "ne" -> !Objects.equals(normalize(left), normalize(right));
            case "in" -> in(left, right);
            case "contains" -> contains(left, right);
            case "regex" -> regex(left, right);
            case "gt" -> compareNumber(left, right) > 0;
            case "gte" -> compareNumber(left, right) >= 0;
            case "lt" -> compareNumber(left, right) < 0;
            case "lte" -> compareNumber(left, right) <= 0;
            case "exists" -> left != null;
            case "not_exists" -> left == null;
            default -> Objects.equals(normalize(left), normalize(right));
        };
    }

    // 判断左值是否存在于右值列表中（或反之）
    private boolean in(Object left, Object right) {
        if (right instanceof List<?> list) {
            return list.contains(left);
        }
        if (left instanceof List<?> list) {
            return list.contains(right);
        }
        return Objects.equals(normalize(left), normalize(right));
    }

    // 判断左值（字符串或列表）是否包含右值
    private boolean contains(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof String ls) {
            return ls.contains(String.valueOf(right));
        }
        if (left instanceof List<?> list) {
            return list.contains(right);
        }
        return false;
    }

    // 使用正则表达式匹配左值是否符合右值的模式
    private boolean regex(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        return String.valueOf(left).matches(String.valueOf(right));
    }

    // 将左右值转为数值进行比较，返回标准的 compareTo 结果
    private int compareNumber(Object left, Object right) {
        if (left == null || right == null) {
            return 0;
        }
        Double l = toDouble(left);
        Double r = toDouble(right);
        if (l == null || r == null) {
            return 0;
        }
        return Double.compare(l, r);
    }

    // 将值安全地转换为 Double 类型，转换失败返回 null
    private Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            log.debug("数值转换失败: value={}, {}", value, e.getMessage());
            return null;
        }
    }

    // 标准化值：去除字符串两端空白，其他类型直接返回
    private Object normalize(Object value) {
        if (value instanceof String s) {
            return s.trim();
        }
        return value;
    }

    // 使用 SpEL 表达式引擎评估条件，采用安全的只读上下文防止注入攻击
    private boolean evalSpel(IngestionContext context, String expression) {
        try {
            // 使用 SimpleEvaluationContext 替代 StandardEvaluationContext，
            // 防止 SpEL 注入导致远程代码执行（RCE）
            SimpleEvaluationContext ctx = SimpleEvaluationContext
                    .forReadOnlyDataBinding()
                    .withRootObject(context)
                    .build();
            ctx.setVariable("ctx", context);
            Boolean result = parser.parseExpression(expression).getValue(ctx, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("SpEL 条件求值失败: expression={}, {}", expression, e.getMessage());
            return false;
        }
    }
}
