package prefixidx;

import java.util.List;

/**
 * 一次命中：命中的模式 + 被 `*` 捕获的段。
 */
public class Match {

    private final String pattern;
    private final List<String> params;

    /**
     * @param pattern 构造时传入的**原始模式字符串**（不做归一化改写）
     * @param params  被 `*` 捕获的段，原样（不做 URL 解码）；没有 `*` 时为空列表
     */
    public Match(String pattern, List<String> params) {
        this.pattern = pattern;
        this.params = List.copyOf(params);
    }

    /** 命中的模式字符串，即构造时传入的那一条原文。 */
    public String pattern() {
        return pattern;
    }

    /**
     * 被 `*` 捕获的段。`*` 至多出现一次、且独占一段，因此这里至多一个元素；
     * 模式里没有 `*` 时为空列表。列表不可修改。
     */
    public List<String> params() {
        return params;
    }
}
