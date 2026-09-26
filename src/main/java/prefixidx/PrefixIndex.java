package prefixidx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 最长前缀匹配索引：把一批路由模式编译成可查询的索引，对给定路径返回**胜出**的那条模式。
 *
 * <p>胜出规则（README「对外契约」第 3、4、5 条）：段数多者优先；段数并列时字面段字符总数
 * 多者优先；仍并列时取模式字符串字典序最小者。构造时即按此规则把模式排好序，
 * 查询时按序过滤，因此结果与插入顺序、{@code HashMap} 迭代顺序均无关。
 */
public class PrefixIndex {

    /** 一条编译后的模式：原始字符串 + 归一化后的段。 */
    private static final class Entry {
        final String original;
        final String[] segments;
        final boolean wildcard;
        final int literalChars;

        Entry(String original, String[] segments) {
            this.original = original;
            this.segments = segments;
            boolean wild = false;
            int chars = 0;
            for (String seg : segments) {
                if ("*".equals(seg)) {
                    wild = true;
                } else {
                    chars += seg.length();
                }
            }
            this.wildcard = wild;
            this.literalChars = chars;
        }
    }

    /** 优先级：段数多者在前，再比字面字符数，再比模式字符串字典序（小者在前）。 */
    private static final Comparator<Entry> PRIORITY = Comparator
            .comparingInt((Entry e) -> e.segments.length).reversed()
            .thenComparing(Comparator.comparingInt((Entry e) -> e.literalChars).reversed())
            .thenComparing(e -> e.original);

    private final List<Entry> entries;
    private final boolean ignoreCase;

    /**
     * 用一批模式构造索引。
     *
     * @param patterns   路由模式；构造时按 README 第 1、7 条校验并归一化
     * @param ignoreCase 是否大小写不敏感地比较段
     * @throws PrefixParseException    某个模式不合法（`*` 不独占一段、`*` 不在最后一段、段内含空白）
     * @throws IllegalArgumentException {@code patterns} 为 {@code null}，或其中某个元素为 {@code null}
     */
    public PrefixIndex(List<String> patterns, boolean ignoreCase) {
        if (patterns == null) {
            throw new IllegalArgumentException("patterns 不能为 null");
        }
        this.ignoreCase = ignoreCase;
        List<Entry> compiled = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            if (pattern == null) {
                throw new IllegalArgumentException("模式列表里不能含 null 元素");
            }
            compiled.add(new Entry(pattern, parse(pattern)));
        }
        compiled.sort(PRIORITY);
        this.entries = List.copyOf(compiled);
    }

    /**
     * 返回命中该路径的模式中优先级最高的那一条；没有命中的模式时返回
     * {@link Optional#empty()}（不抛异常）。
     *
     * @throws IllegalArgumentException {@code path} 为 {@code null}
     */
    public Optional<Match> match(String path) {
        List<Match> all = matchAll(path);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * 返回命中该路径的**全部**模式，按 README 第 3、4、5 条定义的优先级排列
     * （第一个元素就是 {@link #match(String)} 会选中那一条）；没有命中时返回空列表。
     *
     * @throws IllegalArgumentException {@code path} 为 {@code null}
     */
    public List<Match> matchAll(String path) {
        if (path == null) {
            throw new IllegalArgumentException("path 不能为 null");
        }
        String[] pathSegments = split(path);
        List<Match> out = new ArrayList<>();
        for (Entry entry : entries) {
            Match hit = tryMatch(entry, pathSegments);
            if (hit != null) {
                out.add(hit);
            }
        }
        return List.copyOf(out);
    }

    /** 按第 7 条归一化：按 `/` 切段并丢弃所有空段。 */
    private static String[] split(String s) {
        List<String> segments = new ArrayList<>();
        for (String part : s.split("/", -1)) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return segments.toArray(new String[0]);
    }

    /** 校验并归一化一条模式（第 1、7 条）。 */
    private static String[] parse(String pattern) {
        String[] segments = split(pattern);
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.indexOf('*') >= 0) {
                if (!"*".equals(seg)) {
                    throw new PrefixParseException("`*` 必须独占一段：" + pattern);
                }
                if (i != segments.length - 1) {
                    throw new PrefixParseException("`*` 只能出现在最后一段：" + pattern);
                }
                continue;
            }
            for (int j = 0; j < seg.length(); j++) {
                if (Character.isWhitespace(seg.charAt(j))) {
                    throw new PrefixParseException("段内不能含空白字符：" + pattern);
                }
            }
        }
        return segments;
    }

    /** 逐段前缀匹配；命中时组装 {@link Match}，否则返回 {@code null}。 */
    private Match tryMatch(Entry entry, String[] pathSegments) {
        String[] patternSegments = entry.segments;
        if (patternSegments.length == 0) {
            // 0 段模式只匹配 0 段路径（第 10 条）
            return pathSegments.length == 0 ? new Match(entry.original, List.of()) : null;
        }
        if (pathSegments.length < patternSegments.length) {
            return null;
        }
        List<String> params = new ArrayList<>(entry.wildcard ? 1 : 0);
        for (int i = 0; i < patternSegments.length; i++) {
            String p = patternSegments[i];
            if ("*".equals(p)) {
                params.add(pathSegments[i]);
                continue;
            }
            boolean same = ignoreCase
                    ? p.equalsIgnoreCase(pathSegments[i])
                    : p.equals(pathSegments[i]);
            if (!same) {
                return null;
            }
        }
        return new Match(entry.original, params);
    }
}
