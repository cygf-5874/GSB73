package prefixidx;

import java.util.List;

/**
 * 一次命中：命中的模式 + 该模式的权重 + 被 `*` 捕获的段。
 *
 * <p>本文件当前是**空壳**：构造器与方法体只抛 {@link UnsupportedOperationException}。
 * 类名、方法签名与返回约定已经是最终形态（见 README「对外契约」第 4 条），不要改动；
 * 内部表示与私有成员可以自由设计。
 */
public class Match {

    /**
     * @param pattern 构造索引时传入的**原始模式字符串**（不做归一化改写）
     * @param weight  该模式的权重（与模式一起传入的那个整数）
     * @param params  被 `*` 捕获的段，取**路径原文**（不做 URL 解码、不折叠大小写）；
     *                没有 `*` 时为空列表
     */
    public Match(String pattern, int weight, List<String> params) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 命中的模式字符串，即构造索引时传入的那一条原文。 */
    public String pattern() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 该模式的权重。 */
    public int weight() {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * 被 `*` 捕获的段。`*` 至多出现一次、且独占一段，因此这里至多一个元素；
     * 模式里没有 `*` 时为空列表。列表不可修改。
     */
    public List<String> params() {
        throw new UnsupportedOperationException("not implemented");
    }
}
