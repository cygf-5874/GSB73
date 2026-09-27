package prefixidx;

/**
 * 一次 {@code explain} 的裁决轨迹：真实决胜的那一轮 + 最终胜出的模式 + 命中总数。
 *
 * <p>本文件当前是**空壳**：构造器与方法体只抛 {@link UnsupportedOperationException}。
 * 类名、方法签名与返回约定已经是最终形态（见 README「对外契约」第 6 条），不要改动；
 * 内部表示与私有成员可以自由设计。
 */
public class Decision {

    /**
     * @param round      真实决胜的那一轮，取值只能是
     *                   {@code "SEGMENTS"} / {@code "LITERAL"} / {@code "LEXICO"} / {@code "MISS"}
     * @param pattern    最终胜出的模式原文；{@code round} 为 {@code "MISS"} 时为 {@code null}
     * @param candidates 命中该路径的模式**总数**（不是留在某轮里的候选数）
     */
    public Decision(String round, String pattern, int candidates) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 真实决胜的那一轮。 */
    public String round() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 最终胜出的模式原文；{@code "MISS"} 时为 {@code null}。 */
    public String pattern() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 命中该路径的模式总数。 */
    public int candidates() {
        throw new UnsupportedOperationException("not implemented");
    }
}
