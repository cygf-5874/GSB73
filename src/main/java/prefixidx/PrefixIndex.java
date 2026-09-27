package prefixidx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 最长前缀匹配索引：把一批路由模式编译成可查询的索引，对给定路径返回**胜出**的那条模式。
 *
 * <p>优先级（README 第 3、4、5 条）：段数多者优先；并列时字面段字符总数多者优先；
 * 再并列时模式字符串（原文）按 {@link String#compareTo} 字典序最小者优先。
 */
public class PrefixIndex {

    /** 一条编译后的模式：原文 + 归一化后的段 + 优先级裁决所需的预计算量。 */
    private static final class Entry {
        final String original;
        final List<String> segments;
        final int literalChars;
        final int wildcardIndex; // -1 表示没有通配段

        Entry(String original, List<String> segments, int literalChars, int wildcardIndex) {
            this.original = original;
            this.segments = segments;
            this.literalChars = literalChars;
            this.wildcardIndex = wildcardIndex;
        }
    }

    /** 优先级比较器：段数降序 → 字面字符数降序 → 模式原文升序。 */
    private static final Comparator<Entry> PRIORITY = Comparator
            .comparingInt((Entry e) -> e.segments.size()).reversed()
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
                throw new IllegalArgumentException("模式列表里含 null 元素");
            }
            compiled.add(compile(pattern));
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
        List<String> pathSegments = normalizeChecked(path);
        for (Entry entry : entries) {
            Match hit = tryMatch(entry, pathSegments);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }

    /**
     * 返回命中该路径的**全部**模式，按 README 第 3、4、5 条定义的优先级排列
     * （第一个元素就是 {@link #match(String)} 会选中那一条）；没有命中时返回空列表。
     *
     * @throws IllegalArgumentException {@code path} 为 {@code null}
     */
    public List<Match> matchAll(String path) {
        List<String> pathSegments = normalizeChecked(path);
        List<Match> out = new ArrayList<>();
        for (Entry entry : entries) {
            Match hit = tryMatch(entry, pathSegments);
            if (hit != null) {
                out.add(hit);
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------- 内部实现

    /** 校验并编译一条模式。 */
    private static Entry compile(String pattern) {
        List<String> segments = normalize(pattern);
        int literalChars = 0;
        int wildcardIndex = -1;
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            for (int j = 0; j < segment.length(); j++) {
                if (Character.isWhitespace(segment.charAt(j))) {
                    throw new PrefixParseException("模式段内含空白字符：" + pattern);
                }
            }
            int star = segment.indexOf('*');
            if (star >= 0) {
                if (!segment.equals("*")) {
                    throw new PrefixParseException("`*` 必须独占一段：" + pattern);
                }
                if (i != segments.size() - 1) {
                    throw new PrefixParseException("`*` 只能出现在最后一段：" + pattern);
                }
                wildcardIndex = i;
            } else {
                literalChars += segment.length();
            }
        }
        return new Entry(pattern, segments, literalChars, wildcardIndex);
    }

    /** 按 README 第 7 条归一化：按 `/` 切段、丢弃空段。 */
    private static List<String> normalize(String s) {
        List<String> segments = new ArrayList<>();
        for (String part : s.split("/", -1)) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return segments;
    }

    private static List<String> normalizeChecked(String path) {
        if (path == null) {
            throw new IllegalArgumentException("path 不能为 null");
        }
        return normalize(path);
    }

    /** 逐段前缀匹配；命中返回 Match，否则返回 null。 */
    private Match tryMatch(Entry entry, List<String> pathSegments) {
        List<String> patternSegments = entry.segments;
        if (patternSegments.isEmpty()) {
            // 0 段的模式（"/"、"//"）只匹配归一化后 0 段的路径（README 第 10 条）
            return pathSegments.isEmpty() ? new Match(entry.original, List.of()) : null;
        }
        if (patternSegments.size() > pathSegments.size()) {
            return null;
        }
        String captured = null;
        for (int i = 0; i < patternSegments.size(); i++) {
            if (i == entry.wildcardIndex) {
                captured = pathSegments.get(i);
                continue;
            }
            String expected = patternSegments.get(i);
            String actual = pathSegments.get(i);
            boolean equal = ignoreCase ? expected.equalsIgnoreCase(actual) : expected.equals(actual);
            if (!equal) {
                return null;
            }
        }
        List<String> params = (captured == null) ? List.of() : List.of(captured);
        return new Match(entry.original, params);
    }
}
