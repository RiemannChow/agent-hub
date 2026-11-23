package com.riemann.agent.infrastructure.toolkit.verify;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 工具结果验证：在结果回填给模型之前检查契约与可疑内容，并抽取可追溯的数字。
 *
 * <p>纯函数组件，不依赖框架事件或 Spring 运行时状态，便于完整单测。 「验证失败」不是抛异常，而是返回一条说明，由调用方决定是否标注进结果。
 */
@Component
public class VerificationGate {

    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)-?\\d[\\d,]*(?:\\.\\d+)?");

    private final VerificationProperties props;

    public VerificationGate(VerificationProperties props) {
        this.props = props;
    }

    /** 一次验证的结论。 */
    public record Result(
            boolean passed, List<String> failures, Set<String> numbers, String source) {

        public static Result ok(Set<String> numbers, String source) {
            return new Result(true, List.of(), numbers, source);
        }

        public static Result failed(List<String> failures, Set<String> numbers, String source) {
            return new Result(false, List.copyOf(failures), numbers, source);
        }
    }

    /**
     * 验证工具结果。
     *
     * @param toolName 工具名
     * @param text 结果文本（多块已拼接）
     * @param state 工具结果状态（SUCCESS / ERROR / DENIED / ...）
     */
    public Result verify(String toolName, String text, String state) {
        if (!props.isEnabled()) {
            return Result.ok(Set.of(), toolName);
        }
        List<String> failures = new ArrayList<>();
        if (text == null || text.isBlank()) {
            if (props.isEmptyResultIsFailure()) {
                failures.add("empty tool result");
            }
        }
        if ("ERROR".equalsIgnoreCase(state) || "DENIED".equalsIgnoreCase(state)) {
            // 失败/拒绝是合法状态，不二次判失败；但没有可核验的数据
            return Result.failed(
                    List.of("tool state=" + state + " has no verifiable data"), Set.of(), toolName);
        }
        String content = text == null ? "" : text;
        for (String pattern : props.getSuspiciousPatterns()) {
            if (pattern != null
                    && !pattern.isBlank()
                    && Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(content).find()) {
                failures.add("suspicious content matched: " + pattern);
            }
        }
        Set<String> numbers = extractNumbers(content);
        if (numbers.contains("__UNREASONABLE__")) {
            numbers.remove("__UNREASONABLE__");
            failures.add("numeric value exceeds reasonable bound");
        }
        return failures.isEmpty()
                ? Result.ok(numbers, toolName)
                : Result.failed(failures, numbers, toolName);
    }

    /** 抽取结果中出现的数字（去千分位），用于答案 grounding。 */
    public Set<String> extractNumbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find() && out.size() < props.getMaxNumbersPerResult()) {
            String raw = m.group().replace(",", "");
            try {
                double v = Double.parseDouble(raw);
                if (Math.abs(v) > props.getMaxReasonableNumber()) {
                    out.add("__UNREASONABLE__");
                    continue;
                }
            } catch (NumberFormatException ignored) {
                continue;
            }
            // 保留整数与原始小数形式；整数去掉前导 + 和 -0
            out.add(raw);
            // 同时登记整数部分，方便答案里 43.0 与工具里 43 对齐
            if (raw.contains(".")) {
                try {
                    long whole = (long) Double.parseDouble(raw);
                    out.add(String.valueOf(whole));
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        }
        return out;
    }

    /** 生成给模型看的验证失败说明。 */
    public static String annotation(Result result) {
        if (result.passed()) {
            return "";
        }
        return "【结果验证】本次 "
                + result.source()
                + " 的结果未通过核验："
                + String.join("；", result.failures())
                + "。请勿直接引用该数据，改用其它工具或向用户说明。";
    }
}
