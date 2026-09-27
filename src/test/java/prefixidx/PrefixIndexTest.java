package prefixidx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * prefixidx 既有用例。自带极简 runner（不引入任何第三方测试框架）。
 *
 * <p>覆盖范围只包括**三档裁决与单条模式的字面匹配**：前缀命中、段数裁决、字面字符数裁决、
 * 字典序裁决、通配捕获、归一化、非法模式与 null 入参。权重与 `resolve` / `explain`
 * 的语义、空集与 `/` 的匹配范围、`matchAll` 的稳定性、受限堆下的规模等，由固定件判定。
 *
 * <p>本文件当前全部失败（实现是空壳），能编译，没有语法错。
 */
public final class PrefixIndexTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        run("emptyIndexReturnsEmpty", PrefixIndexTest::emptyIndexReturnsEmpty);
        run("nullPatternsRejected", PrefixIndexTest::nullPatternsRejected);
        run("nullPathRejected", PrefixIndexTest::nullPathRejected);
        run("exactPatternMatchesPrefix", PrefixIndexTest::exactPatternMatchesPrefix);
        run("longestSegmentCountWins", PrefixIndexTest::longestSegmentCountWins);
        run("tieBreakByLiteralChars", PrefixIndexTest::tieBreakByLiteralChars);
        run("tieBreakByLexicographicOrder", PrefixIndexTest::tieBreakByLexicographicOrder);
        run("matchAllSortedByPriority", PrefixIndexTest::matchAllSortedByPriority);
        run("matchAllStableAcrossInsertionOrder", PrefixIndexTest::matchAllStableAcrossInsertionOrder);
        run("wildcardCapturesSegment", PrefixIndexTest::wildcardCapturesSegment);
        run("wildcardCaptureIsRaw", PrefixIndexTest::wildcardCaptureIsRaw);
        run("normalizationFoldsSlashes", PrefixIndexTest::normalizationFoldsSlashes);
        run("parseRejectsStarInMiddle", PrefixIndexTest::parseRejectsStarInMiddle);

        System.out.println("通过 " + passed + "/" + (passed + failed));
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- runner

    private interface Case {
        void run() throws Exception;
    }

    private static void run(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + "  " + t);
        }
    }

    private static void check(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        boolean same = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!same) {
            throw new AssertionError(message + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private static void expectIae(Runnable body, String message) {
        try {
            body.run();
        } catch (IllegalArgumentException e) {
            return;
        } catch (Throwable t) {
            throw new AssertionError(message + " 期望=IllegalArgumentException 实际=抛了 " + t.getClass().getName());
        }
        throw new AssertionError(message + " 期望=IllegalArgumentException 实际=没有抛出");
    }

    private static void expectPrefixParse(Runnable body, String message) {
        try {
            body.run();
        } catch (PrefixParseException e) {
            return;
        } catch (Throwable t) {
            throw new AssertionError(message + " 期望=PrefixParseException 实际=抛了 " + t.getClass().getName());
        }
        throw new AssertionError(message + " 期望=PrefixParseException 实际=没有抛出");
    }

    /** 把一批模式绑到同一个权重上，构造索引。 */
    private static PrefixIndex index(int weight, boolean ignoreCase, String... patterns) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String p : patterns) {
            map.put(p, weight);
        }
        return new PrefixIndex(map, ignoreCase);
    }

    private static PrefixIndex index(String... patterns) {
        return index(0, false, patterns);
    }

    private static PrefixIndex indexI(String... patterns) {
        return index(0, true, patterns);
    }

    private static Match matched(Optional<Match> result) {
        return result.orElseThrow(() -> new AssertionError("期望=有命中的模式 实际=Optional.empty()"));
    }

    private static List<String> patternsOf(List<Match> matches) {
        List<String> out = new ArrayList<>();
        for (Match m : matches) {
            out.add(m.pattern());
        }
        return out;
    }

    // ----------------------------------------------------------- 既有用例

    private static void emptyIndexReturnsEmpty() {
        PrefixIndex idx = new PrefixIndex(Map.of(), false);
        check(idx.match("/api").isEmpty(), "空模式集：match 应返回 Optional.empty()");
        check(idx.match("/").isEmpty(), "空模式集：match(\"/\") 应返回 Optional.empty()");
        List<Match> all = idx.matchAll("/api/v1");
        check(all != null, "空模式集：matchAll 不应返回 null");
        check(all.isEmpty(), "空模式集：matchAll 应返回空列表");
    }

    private static void nullPatternsRejected() {
        expectIae(() -> new PrefixIndex(null, false), "new PrefixIndex(null, false)");
        Map<String, Integer> withNullKey = new LinkedHashMap<>();
        withNullKey.put(null, 1);
        expectIae(() -> new PrefixIndex(withNullKey, false), "模式集合里含 null 键");
        Map<String, Integer> withNullValue = new LinkedHashMap<>();
        withNullValue.put("/api", null);
        expectIae(() -> new PrefixIndex(withNullValue, false), "模式集合里含 null 权重");
    }

    private static void nullPathRejected() {
        PrefixIndex idx = index("/api");
        expectIae(() -> idx.match(null), "match(null)");
        expectIae(() -> idx.matchAll(null), "matchAll(null)");
    }

    private static void exactPatternMatchesPrefix() {
        PrefixIndex idx = index("/api/v1");
        eq("/api/v1", matched(idx.match("/api/v1")).pattern(), "路径与模式完全一致应命中");
        eq("/api/v1", matched(idx.match("/api/v1/users")).pattern(), "模式是路径的前缀应命中");
        eq("/api/v1", matched(idx.match("/api/v1/users/42")).pattern(), "更长的路径也应命中");
        check(idx.match("/api").isEmpty(), "路径比模式短：不应命中");
        check(idx.match("/api/v2").isEmpty(), "字面段不同：不应命中");
        check(idx.match("/apix").isEmpty(), "段边界不同：不应命中");
    }

    private static void longestSegmentCountWins() {
        PrefixIndex idx = index("/api", "/api/v1", "/api/v1/admin");
        eq("/api/v1/admin", matched(idx.match("/api/v1/admin/42")).pattern(), "段数最多的应胜出");
        eq("/api/v1", matched(idx.match("/api/v1/other")).pattern(), "2 段应胜过 1 段");
        eq("/api", matched(idx.match("/api/orders")).pattern(), "只有 1 段命中时由 /api 胜出");

        PrefixIndex wild = index("/api", "/api/*");
        eq("/api/*", matched(wild.match("/api/users")).pattern(), "通配段也计入段数：/api/* 应胜过 /api");
        eq("/api", matched(wild.match("/api")).pattern(), "路径没有第 2 段时由 /api 命中");
    }

    private static void tieBreakByLiteralChars() {
        PrefixIndex a = index("/api/*", "/api/v1");
        eq("/api/v1", matched(a.match("/api/v1/x")).pattern(), "段数并列时字面段字符数多的胜出");
        eq("/api/*", matched(a.match("/api/orders/7")).pattern(), "字面段不匹配时由通配模式命中");

        PrefixIndex b = index("/api/v1/*", "/api/v1/v2");
        eq("/api/v1/v2", matched(b.match("/api/v1/v2/x")).pattern(), "3 段并列时字面段字符数多的胜出");

        PrefixIndex c = index("/a/*", "/a/b");
        eq("/a/b", matched(c.match("/a/b")).pattern(), "2 段并列：2 个字面字符胜过 1 个");

        PrefixIndex d = indexI("/Api/*", "/api/V1");
        eq("/api/V1", matched(d.match("/API/v1/x")).pattern(),
                "ignoreCase 下字面字符数仍按**原文**计：5 个对 3 个");
    }

    private static void tieBreakByLexicographicOrder() {
        PrefixIndex a = indexI("/Api", "/api");
        eq("/Api", matched(a.match("/api/x")).pattern(), "大小写不敏感时按模式原文的字典序最小定先后");

        PrefixIndex b = indexI("/APi", "/Api");
        eq("/APi", matched(b.match("/API/x")).pattern(), "同一规则再核一次");

        PrefixIndex c = index("/api", "//api");
        eq("//api", matched(c.match("/api/users")).pattern(), "归一化后等价的模式之间也按字典序定先后");

        PrefixIndex d = index("/api/", "/api");
        eq("/api", matched(d.match("/api/users")).pattern(), "带尾斜杠与不带尾斜杠之间按字典序定先后");
    }

    private static void matchAllSortedByPriority() {
        PrefixIndex idx = index("/api", "/api/v1", "/api/*", "/api/v1/admin", "/healthz");
        List<Match> all = idx.matchAll("/api/v1/admin/42");
        eq(List.of("/api/v1/admin", "/api/v1", "/api/*", "/api"), patternsOf(all),
                "matchAll 应按 段数 → 字面字符数 → 字典序 的优先级降序排列");
        eq(matched(idx.match("/api/v1/admin/42")).pattern(), all.get(0).pattern(),
                "matchAll 的第一个元素应等于 match 的胜者");

        eq(List.of("/api/*", "/api"), patternsOf(idx.matchAll("/api/v2")), "只有两条命中时的顺序");
        eq(List.of("/healthz"), patternsOf(idx.matchAll("/healthz/x")), "只有一条命中时的列表");
        check(idx.matchAll("/nope").isEmpty(), "没有命中时应返回空列表");
    }

    private static void matchAllStableAcrossInsertionOrder() {
        List<String> base = List.of("/api", "/api/v1", "/api/*", "/api/v1/admin", "/healthz");
        List<String> reversed = new ArrayList<>(base);
        java.util.Collections.reverse(reversed);

        PrefixIndex asc = index(0, false, base.toArray(new String[0]));
        PrefixIndex desc = index(0, false, reversed.toArray(new String[0]));
        String path = "/api/v1/admin/42";

        eq(patternsOf(asc.matchAll(path)), patternsOf(desc.matchAll(path)),
                "插入顺序不应影响 matchAll 的结果与顺序");
        eq(patternsOf(asc.matchAll(path)), patternsOf(asc.matchAll(path)),
                "同一个索引重复调用 matchAll 应给出同一结果");
        eq(asc.match(path).map(Match::pattern).orElse(null),
                desc.match(path).map(Match::pattern).orElse(null),
                "插入顺序不应影响 match 的胜者");
    }

    private static void wildcardCapturesSegment() {
        PrefixIndex idx = index("/api", "/api/*");
        Match m = matched(idx.match("/api/users"));
        eq("/api/*", m.pattern(), "2 段通配模式应胜过 1 段字面模式");
        eq(List.of("users"), m.params(), "params 应给出被 * 捕获的段");

        Match plain = matched(idx.match("/api"));
        eq("/api", plain.pattern(), "路径只有 1 段时应由 /api 命中");
        eq(List.of(), plain.params(), "模式里没有 * 时 params 应为空列表");

        PrefixIndex onlyWild = index("/api/*");
        check(onlyWild.match("/api").isEmpty(), "/api/* 不应匹配只有 1 段的路径");
        check(onlyWild.match("/api/").isEmpty(), "尾斜杠归一化后仍是 1 段：/api/* 不应匹配");
    }

    private static void wildcardCaptureIsRaw() {
        PrefixIndex idx = index("/api/*");
        eq(List.of("a%20b"), matched(idx.match("/api/a%20b")).params(), "捕获段应原样返回，不做 URL 解码");
        eq(List.of("Users%2FAdmin"), matched(idx.match("/api/Users%2FAdmin")).params(), "捕获段应原样返回");
        eq(List.of("v1"), matched(idx.match("//api//v1")).params(), "路径归一化后捕获的仍是原始段");

        PrefixIndex folded = indexI("/api/*");
        eq(List.of("Users%2FAdmin"), matched(folded.match("/API/Users%2FAdmin")).params(),
                "ignoreCase 下 params 仍返回路径原文，不折叠大小写");
    }

    private static void normalizationFoldsSlashes() {
        PrefixIndex a = index("//api//v1//");
        eq("//api//v1//", matched(a.match("/api/v1/users")).pattern(), "模式侧的多重斜杠与尾斜杠都要归一化");
        eq("//api//v1//", matched(a.match("api/v1")).pattern(), "路径侧缺首斜杠也应等价");
        check(a.match("/api").isEmpty(), "路径段数不足：不应命中");

        PrefixIndex b = index("/api");
        PrefixIndex c = index("/api/");
        for (String p : List.of("/api", "/api/", "//api", "/api/x", "api")) {
            eq(b.match(p).isPresent(), c.match(p).isPresent(), "「/api」与「/api/」在路径 " + p + " 上结论应一致");
        }
    }

    private static void parseRejectsStarInMiddle() {
        expectPrefixParse(() -> index("/api/*/x"), "中间通配 /api/*/x");
        expectPrefixParse(() -> index("*/x"), "中间通配 */x");
        expectPrefixParse(() -> index("/a*b"), "`*` 未独占一段：/a*b");
        expectPrefixParse(() -> index("/ap i"), "段内含空白：/ap i");

        PrefixIndex trailing = index("/api/*/");
        Match m = matched(trailing.match("/api/users"));
        eq("/api/*/", m.pattern(), "尾斜杠归一化后 /api/*/ 是合法模式");
        eq(List.of("users"), m.params(), "归一化后的通配模式照常捕获");

        PrefixIndex star = index("*");
        eq("*", matched(star.match("/anything")).pattern(), "单个 * 是合法的单段通配");
        eq(List.of("a"), matched(star.match("/a/b")).params(), "* 只吃掉第 1 段，更深的路径仍按前缀命中");
        check(star.match("/").isEmpty(), "* 不匹配 0 段的路径");
    }

    private PrefixIndexTest() {
    }
}
