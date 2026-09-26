package prefixidx;

import java.util.List;
import java.util.Optional;

/**
 * 最长前缀匹配索引：把一批路由模式编译成可查询的索引，对给定路径返回**胜出**的那条模式。
 *
 * <p>本文件当前是**空壳**：构造器与方法体只抛 {@link UnsupportedOperationException}。
 * 公开的类名、方法签名与返回约定已经是最终形态（见 README「对外契约」11 条），不要改动；
 * 内部表示、私有成员与私有方法可以自由设计（也可以新增公开方法，但不必需）。
 */
public class PrefixIndex {

    /**
     * 用一批模式构造索引。
     *
     * @param patterns   路由模式；构造时按 README 第 1、7 条校验并归一化
     * @param ignoreCase 是否大小写不敏感地比较段
     * @throws PrefixParseException    某个模式不合法（`*` 不独占一段、`*` 不在最后一段、段内含空白）
     * @throws IllegalArgumentException {@code patterns} 为 {@code null}，或其中某个元素为 {@code null}
     */
    public PrefixIndex(List<String> patterns, boolean ignoreCase) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * 返回命中该路径的模式中优先级最高的那一条；没有命中的模式时返回
     * {@link Optional#empty()}（不抛异常）。
     *
     * @throws IllegalArgumentException {@code path} 为 {@code null}
     */
    public Optional<Match> match(String path) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * 返回命中该路径的**全部**模式，按 README 第 3、4、5 条定义的优先级排列
     * （第一个元素就是 {@link #match(String)} 会选中那一条）；没有命中时返回空列表。
     * 顺序完全确定：不依赖插入顺序，也不依赖 {@code HashMap} 的迭代顺序。
     *
     * @throws IllegalArgumentException {@code path} 为 {@code null}
     */
    public List<Match> matchAll(String path) {
        throw new UnsupportedOperationException("not implemented");
    }
}
