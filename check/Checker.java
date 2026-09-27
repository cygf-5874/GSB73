import prefixidx.Decision;
import prefixidx.Match;
import prefixidx.PrefixIndex;
import prefixidx.PrefixParseException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * prefixidx 的固定验收程序（勿改）。
 *
 * <p>16 个场景覆盖 README「对外契约」的 14 条与「规模要求」一节：
 * parse 3 / match 4 / params 1 / all 1 / explain 2 / resolve 3 / robust 1 / scale 1。
 * 每个场景都在**独立 JVM 子进程**里跑（{@link ProcessBuilder}），带看门狗，超时判不通过；
 * 某个场景失败不会遮蔽其余场景（失败不早退）。判据全部确定性：没有随机源、没有墙钟
 * （唯一例外是 {@code scale}，它的口径写在 README「规模要求」一节：受限堆 + 90 秒上限，
 * 按本机标定）。
 *
 * <p>用法：java -cp out;out-check Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    /** 单场景看门狗默认值（秒）。 */
    private static final long WATCHDOG_SECONDS = 30L;
    /** scale 场景的看门狗（秒）：它自己要在 90 秒预算内跑完，这里留足外层余量。 */
    private static final long SCALE_WATCHDOG_SECONDS = 150L;
    /** scale 场景里等待 ScaleProbe 子进程的上限（秒）。 */
    private static final long SCALE_CHILD_SECONDS = 120L;
    /** scale 场景给 ScaleProbe 的堆上限。 */
    private static final String SCALE_HEAP = "-Xmx96m";

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因（内含 期望=… 实际=…）。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String name;
        final String group;
        final long watchdogSeconds;
        final Body body;

        Scenario(String name, Body body) {
            this(name, WATCHDOG_SECONDS, body);
        }

        Scenario(String name, long watchdogSeconds, Body body) {
            this.name = name;
            this.body = body;
            this.watchdogSeconds = watchdogSeconds;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    public static void main(String[] args) throws Exception {
        String scenarioOnly = null;
        List<String> onlyGroups = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) {
                        onlyGroups.add(g);
                    }
                }
            } else if ("--scenario".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--scenario 需要一个场景名");
                    System.exit(2);
                }
                scenarioOnly = args[++i];
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        List<Scenario> all = scenarios();

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        if (scenarioOnly != null) {
            System.exit(runOne(all, scenarioOnly));
        }

        List<Scenario> selected = new ArrayList<>();
        for (Scenario s : all) {
            if (onlyGroups.isEmpty() || onlyGroups.contains(s.group)) {
                selected.add(s);
            }
        }
        if (selected.isEmpty()) {
            System.out.println("没有匹配的场景（--only " + onlyGroups + "）");
            System.exit(2);
        }

        int pass = 0;
        for (Scenario s : selected) {
            String line = runInChildProcess(s);
            System.out.println(line);
            if (line.startsWith("PASS ")) {
                pass++;
            }
        }
        System.out.println("结果：通过 " + pass + "/" + selected.size());
        if (pass != selected.size()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------- 子进程调度（一场景一 JVM）

    /** 在独立子进程里跑单个场景，把子进程给出的 PASS/FAIL 行原样返回。 */
    private static String runInChildProcess(Scenario s) {
        String name = s.name;
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                "-cp", classpath, "Checker", "--scenario", name);
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (Exception ex) {
            return "FAIL " + name + "  期望=能在独立 JVM 子进程里跑该场景 实际=子进程启动失败：" + ex;
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (sink) {
                        sink.append(chunk);
                    }
                }
            } catch (Exception ignored) {
                // 子进程被强杀时读流会失败，忽略即可
            }
        }, "checker-child-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = proc.waitFor(s.watchdogSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.destroyForcibly();
            try {
                reader.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "FAIL " + name + "  期望=场景在 " + s.watchdogSeconds + " 秒内给出 PASS/FAIL 实际="
                    + s.watchdogSeconds + " 秒看门狗超时（子进程已强杀）";
        }
        try {
            reader.join(5000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String out;
        synchronized (sink) {
            out = sink.toString().trim();
        }
        String line = null;
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith("PASS ") || t.startsWith("FAIL ")) {
                line = t;
            }
        }
        if (line == null) {
            String shown = out.isEmpty() ? "(无输出)" : out.replace('\n', ' ');
            return "FAIL " + name + "  期望=子进程打印 PASS/FAIL 行 实际=退出码 "
                    + (proc.isAlive() ? "未知（仍存活）" : String.valueOf(proc.exitValue())) + "，输出=" + shown;
        }
        return line;
    }

    /** 子进程模式：就地跑一个场景，打印一行 PASS/FAIL，返回退出码。 */
    private static int runOne(List<Scenario> all, String name) {
        for (Scenario s : all) {
            if (!s.name.equals(name)) {
                continue;
            }
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "检查过程抛异常：" + t;
            }
            if (err == null) {
                System.out.println("PASS " + s.name);
                return 0;
            }
            if (!err.contains("期望=")) {
                err = "期望=场景 " + s.name + " 通过 实际=" + err;
            }
            System.out.println("FAIL " + s.name + "  " + err);
            return 1;
        }
        System.out.println("FAIL " + name + "  期望=场景名存在于固定件 实际=未找到该场景");
        return 1;
    }

    // ------------------------------------------------------------- 场景表

    private static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();

        // ------------------------------------------------ parse（3）

        out.add(new Scenario("parse/legal-patterns-accepted", () -> {
            String e;
            PrefixIndex idx = b("/", 0).add("/api", 0).add("/api/v1", 0)
                    .add("/API", 0).add("/healthz", 0).add("*", 0).cs();
            e = expectPattern("match(\"/api/v1/users\")", idx.match("/api/v1/users"), "/api/v1");
            if (e != null) return e;
            e = expectPattern("match(\"/api/x\")", idx.match("/api/x"), "/api");
            if (e != null) return e;
            e = expectPattern("match(\"/API/x\")", idx.match("/API/x"), "/API");
            if (e != null) return e;
            e = expectPattern("match(\"/healthz\")", idx.match("/healthz"), "/healthz");
            if (e != null) return e;
            e = expectPattern("match(\"/\")", idx.match("/"), "/");
            if (e != null) return e;
            e = expectPattern("match(\"/other/x\")（单段通配 * 命中首段）", idx.match("/other/x"), "*");
            if (e != null) return e;

            PrefixIndex lit = idx("/api", "/api/v1");
            e = expectEmpty("match(\"/apix\")（段边界不同）", lit.match("/apix"));
            if (e != null) return e;
            e = expectEmpty("match(\"/v1\")（字面段不同）", lit.match("/v1"));
            if (e != null) return e;

            e = expectPattern("{\"//api//v1//\"}.match(\"/api/v1/users\")",
                    idx("//api//v1//").match("/api/v1/users"), "//api//v1//");
            if (e != null) return e;
            e = expectPattern("{\"/api/\"}.match(\"/api/x\")", idx("/api/").match("/api/x"), "/api/");
            if (e != null) return e;

            PrefixIndex trailing = idx("/api/*/");
            e = expectPattern("{\"/api/*/\"}.match(\"/api/users\")", trailing.match("/api/users"), "/api/*/");
            if (e != null) return e;
            e = expectParams("{\"/api/*/\"}.match(\"/api/users\")", trailing.match("/api/users"), List.of("users"));
            if (e != null) return e;

            PrefixIndex star = idx("*");
            e = expectPattern("{\"*\"}.match(\"/anything\")", star.match("/anything"), "*");
            if (e != null) return e;
            e = expectParams("{\"*\"}.match(\"/a/b\")", star.match("/a/b"), List.of("a"));
            if (e != null) return e;
            e = expectEmpty("{\"*\"}.match(\"/\")（0 段）", star.match("/"));
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("parse/illegal-pattern-rejected", () -> {
            String e;
            e = expectParseError(() -> idx("/api/*/x"), "中间通配 /api/*/x");
            if (e != null) return e;
            e = expectParseError(() -> idx("*/x"), "中间通配 */x");
            if (e != null) return e;
            e = expectParseError(() -> idx("/api/*/x/y"), "中间通配 /api/*/x/y");
            if (e != null) return e;
            e = expectParseError(() -> idx("/a*b"), "`*` 未独占一段：/a*b");
            if (e != null) return e;
            e = expectParseError(() -> idx("/*x"), "`*` 未独占一段：/*x");
            if (e != null) return e;
            e = expectParseError(() -> idx("/x/*", "/api/*/y"), "同一批里混入中间通配");
            if (e != null) return e;

            Map<String, Integer> dup = new IdentityHashMap<>();
            dup.put(new String("/api"), 1);
            dup.put(new String("/api"), 2);
            e = expectParseError(() -> new PrefixIndex(dup, false), "同一模式字符串出现两次");
            if (e != null) return e;

            Map<String, Integer> dup3 = new IdentityHashMap<>();
            dup3.put(new String("/a/b"), 1);
            dup3.put(new String("/a/b"), 9);
            dup3.put(new String("/a/b"), -9);
            e = expectParseError(() -> new PrefixIndex(dup3, false), "同一模式字符串出现三次");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("parse/normalization-and-whitespace", () -> {
            String e;
            e = expectParseError(() -> idx("/ap i"), "模式段内含空格：/ap i");
            if (e != null) return e;
            e = expectParseError(() -> idx("/tab\there"), "模式段内含制表符");
            if (e != null) return e;
            e = expectParseError(() -> idx("/ok", "/ap i"), "同一批里混入含空白的模式");
            if (e != null) return e;

            PrefixIndex any = idx("/api");
            e = expectIae(() -> any.match("/ap i"), "路径含空格：match(\"/ap i\")");
            if (e != null) return e;
            e = expectIae(() -> any.matchAll("/a\tb"), "路径含制表符：matchAll");
            if (e != null) return e;

            PrefixIndex a = idx("//api//v1//");
            e = expectPattern("{\"//api//v1//\"}.match(\"/api/v1/users\")", a.match("/api/v1/users"), "//api//v1//");
            if (e != null) return e;
            e = expectPattern("{\"//api//v1//\"}.match(\"api/v1\")（缺首斜杠）", a.match("api/v1"), "//api//v1//");
            if (e != null) return e;
            e = expectPattern("{\"//api//v1//\"}.match(\"/api//v1//x\")", a.match("/api//v1//x"), "//api//v1//");
            if (e != null) return e;
            e = expectEmpty("{\"//api//v1//\"}.match(\"/api\")", a.match("/api"));
            if (e != null) return e;

            PrefixIndex bare = idx("/api");
            PrefixIndex trail = idx("/api/");
            for (String p : List.of("/api", "/api/", "//api", "/api/x", "/api//x", "api")) {
                boolean x = bare.match(p).isPresent();
                boolean y = trail.match(p).isPresent();
                if (x != y) {
                    return "期望=「/api」与「/api/」在路径 " + p + " 上结论一致 实际=不一致（/api=" + x + " /api/=" + y + "）";
                }
            }

            PrefixIndex root = idx("/");
            e = expectPattern("{\"/\"}.match(\"/\")", root.match("/"), "/");
            if (e != null) return e;
            e = expectPattern("{\"/\"}.match(\"\")", root.match(""), "/");
            if (e != null) return e;
            e = expectPattern("{\"/\"}.match(\"//\")", root.match("//"), "/");
            if (e != null) return e;
            e = expectEmpty("{\"/\"}.match(\"/api\")", root.match("/api"));
            if (e != null) return e;

            PrefixIndex ci = b("/API/V1", 0).ci();
            e = expectPattern("ignoreCase: match(\"/api/v1/users\")", ci.match("/api/v1/users"), "/API/V1");
            if (e != null) return e;
            e = expectPattern("ignoreCase: match(\"/Api/V1\")", ci.match("/Api/V1"), "/API/V1");
            if (e != null) return e;
            e = expectEmpty("区分大小写: match(\"/api/v1\")", idx("/API/V1").match("/api/v1"));
            if (e != null) return e;
            e = expectEmpty("区分大小写: match(\"/API\")（段数不足）", idx("/API/V1").match("/API"));
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ match（4）

        out.add(new Scenario("match/segments-first", () -> {
            String e;
            PrefixIndex a = idx("/api", "/api/v1", "/api/v1/admin");
            e = expectPattern("match(\"/api/v1/admin/42\")", a.match("/api/v1/admin/42"), "/api/v1/admin");
            if (e != null) return e;
            e = expectPattern("match(\"/api/v1/other\")", a.match("/api/v1/other"), "/api/v1");
            if (e != null) return e;
            e = expectPattern("match(\"/api/v1\")", a.match("/api/v1"), "/api/v1");
            if (e != null) return e;
            e = expectPattern("match(\"/api/orders\")", a.match("/api/orders"), "/api");
            if (e != null) return e;
            e = expectEmpty("match(\"/apix\")", a.match("/apix"));
            if (e != null) return e;
            e = expectEmpty("match(\"/v1/admin\")", a.match("/v1/admin"));
            if (e != null) return e;

            PrefixIndex w = idx("/api", "/api/*");
            e = expectPattern("{\"/api\",\"/api/*\"}.match(\"/api/users\")", w.match("/api/users"), "/api/*");
            if (e != null) return e;
            e = expectPattern("{\"/api\",\"/api/*\"}.match(\"/api/a/b\")", w.match("/api/a/b"), "/api/*");
            if (e != null) return e;
            e = expectPattern("{\"/api\",\"/api/*\"}.match(\"/api\")", w.match("/api"), "/api");
            if (e != null) return e;
            e = expectEmpty("{\"/api/*\"}.match(\"/api\")（只有 1 段）", idx("/api/*").match("/api"));
            if (e != null) return e;
            e = expectEmpty("{\"/api/*\"}.match(\"/api/\")（归一化后仍 1 段）", idx("/api/*").match("/api/"));
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("match/tie-by-literal-chars", () -> {
            String e;
            PrefixIndex a = idx("/api/*", "/api/v1");
            e = expectPattern("{\"/api/*\",\"/api/v1\"}.match(\"/api/v1/x\")", a.match("/api/v1/x"), "/api/v1");
            if (e != null) return e;
            e = expectPattern("{\"/api/*\",\"/api/v1\"}.match(\"/api/orders/7\")", a.match("/api/orders/7"), "/api/*");
            if (e != null) return e;

            PrefixIndex c = idx("/api/v1/*", "/api/v1/v2");
            e = expectPattern("{\"/api/v1/*\",\"/api/v1/v2\"}.match(\"/api/v1/v2/x\")",
                    c.match("/api/v1/v2/x"), "/api/v1/v2");
            if (e != null) return e;

            PrefixIndex d = idx("/a/*", "/a/b");
            e = expectPattern("{\"/a/*\",\"/a/b\"}.match(\"/a/b\")", d.match("/a/b"), "/a/b");
            if (e != null) return e;

            PrefixIndex f = idx("/x/y/*", "/x/y/z");
            e = expectPattern("{\"/x/y/*\",\"/x/y/z\"}.match(\"/x/y/z\")", f.match("/x/y/z"), "/x/y/z");
            if (e != null) return e;

            PrefixIndex ci = idxI("/Api/*", "/api/V1");
            e = expectPattern("ignoreCase 下字面字符数按**原文**计：5 个对 3 个",
                    ci.match("/API/v1/x"), "/api/V1");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("match/tie-by-lexico", () -> {
            String e;
            PrefixIndex a = idxI("/Api", "/api");
            e = expectPattern("ignoreCase {\"/Api\",\"/api\"}.match(\"/api/x\")", a.match("/api/x"), "/Api");
            if (e != null) return e;

            PrefixIndex c = idxI("/APi", "/Api");
            e = expectPattern("ignoreCase {\"/APi\",\"/Api\"}.match(\"/API/x\")", c.match("/API/x"), "/APi");
            if (e != null) return e;

            PrefixIndex d = idx("/api", "//api");
            e = expectPattern("{\"/api\",\"//api\"}.match(\"/api/users\")", d.match("/api/users"), "//api");
            if (e != null) return e;
            PrefixIndex d2 = idx("//api", "/api");
            e = expectPattern("{\"//api\",\"/api\"}.match(\"/api/users\")（构造顺序相反）",
                    d2.match("/api/users"), "//api");
            if (e != null) return e;

            PrefixIndex f = idx("/api/", "/api");
            e = expectPattern("{\"/api/\",\"/api\"}.match(\"/api/users\")", f.match("/api/users"), "/api");
            if (e != null) return e;

            PrefixIndex g = idx("/*", "*");
            e = expectPattern("{\"/*\",\"*\"}.match(\"/x\")", g.match("/x"), "*");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("match/no-match-empty", () -> {
            String e;
            PrefixIndex idx = idx("/api/*", "/api/v1");
            e = expectEmpty("match(\"/healthz\")", idx.match("/healthz"));
            if (e != null) return e;
            e = expectEmpty("match(\"/api\")", idx.match("/api"));
            if (e != null) return e;
            e = expectEmpty("match(\"/api/\")", idx.match("/api/"));
            if (e != null) return e;
            e = expectEmpty("match(\"/apix\")", idx.match("/apix"));
            if (e != null) return e;
            e = expectEmpty("match(\"/other/x/y\")", idx.match("/other/x/y"));
            if (e != null) return e;

            PrefixIndex empty = new PrefixIndex(new LinkedHashMap<>(), false);
            e = expectEmpty("空模式集的 match(\"/api/v1\")", empty.match("/api/v1"));
            if (e != null) return e;
            e = expectPatterns("空模式集的 matchAll(\"/api/v1\")", empty.matchAll("/api/v1"), List.of());
            if (e != null) return e;
            e = expectPatterns("无命中时的 matchAll(\"/healthz\")", idx.matchAll("/healthz"), List.of());
            if (e != null) return e;
            e = expectEmpty("空模式集的 match(\"/\")", empty.match("/"));
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ params（1）

        out.add(new Scenario("params/wildcard-capture-raw", () -> {
            String e;
            PrefixIndex idx = idx("/api", "/api/*");
            Optional<Match> hit = idx.match("/api/users");
            e = expectPattern("{\"/api\",\"/api/*\"}.match(\"/api/users\")", hit, "/api/*");
            if (e != null) return e;
            e = expectParams("{\"/api\",\"/api/*\"}.match(\"/api/users\")", hit, List.of("users"));
            if (e != null) return e;
            e = expectParams("match(\"/api/v1/x\")", idx.match("/api/v1/x"), List.of("v1"));
            if (e != null) return e;
            e = expectParams("match(\"/api/a%20b\")（原样，不做 URL 解码）", idx.match("/api/a%20b"), List.of("a%20b"));
            if (e != null) return e;
            e = expectParams("match(\"/api/Users%2FAdmin\")", idx.match("/api/Users%2FAdmin"), List.of("Users%2FAdmin"));
            if (e != null) return e;
            e = expectParams("match(\"/api/one/two/three\")（捕获通配位那一段）",
                    idx.match("/api/one/two/three"), List.of("one"));
            if (e != null) return e;

            e = expectPattern("{\"/api\",\"/api/*\"}.match(\"/api\")", idx.match("/api"), "/api");
            if (e != null) return e;
            e = expectParams("{\"/api\"} 命中时 params 是空列表", idx.match("/api"), List.of());
            if (e != null) return e;

            PrefixIndex ci = idxI("/api/*");
            e = expectPattern("ignoreCase: match(\"/API/V1/x\")", ci.match("/API/V1/x"), "/api/*");
            if (e != null) return e;
            e = expectParams("ignoreCase 下捕获仍是路径原文（不折叠）", ci.match("/API/V1/x"), List.of("V1"));
            if (e != null) return e;
            e = expectParams("ignoreCase 下捕获不做 URL 解码", ci.match("/API/a%20B"), List.of("a%20B"));
            if (e != null) return e;
            e = expectParams("路径多重斜杠归一化后捕获的仍是原始段",
                    idx("/api/*").match("//api//one%2Ftwo"), List.of("one%2Ftwo"));
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ all（1）

        out.add(new Scenario("all/matchall-descending-stable", () -> {
            String e;
            List<String> base = List.of("/api", "/api/v1", "/api/*", "/api/v1/admin", "/healthz");
            List<String> reversed = new ArrayList<>(base);
            Collections.reverse(reversed);
            List<String> other = List.of("/healthz", "/api/v1/admin", "/api/*", "/api/v1", "/api");

            PrefixIndex asc = fromList(base, false);
            PrefixIndex desc = fromList(reversed, false);
            PrefixIndex shuf = fromList(other, false);
            String path = "/api/v1/admin/42";
            List<String> expected = List.of("/api/v1/admin", "/api/v1", "/api/*", "/api");

            e = expectPatterns("matchAll(\"" + path + "\")", asc.matchAll(path), expected);
            if (e != null) return e;
            e = expectPatterns("倒序构造的 matchAll(\"" + path + "\")", desc.matchAll(path), expected);
            if (e != null) return e;
            e = expectPatterns("另一种构造顺序的 matchAll(\"" + path + "\")", shuf.matchAll(path), expected);
            if (e != null) return e;
            e = expectPatterns("同一索引重复调用 matchAll(\"" + path + "\")", asc.matchAll(path), expected);
            if (e != null) return e;
            e = expectPattern("match(\"" + path + "\") 应等于 matchAll 的首个", asc.match(path), expected.get(0));
            if (e != null) return e;

            PrefixIndex weighted = b("/api", 1000).add("/api/v1", -1000).cs();
            e = expectPatterns("matchAll 的顺序与权重无关", weighted.matchAll("/api/v1/x"),
                    List.of("/api/v1", "/api"));
            if (e != null) return e;
            e = expectPattern("match 的胜者与权重无关", weighted.match("/api/v1/x"), "/api/v1");
            if (e != null) return e;

            e = expectPatterns("{\"/api\",\"//api\"} 的 matchAll(\"/api/x\")",
                    idx("/api", "//api").matchAll("/api/x"), List.of("//api", "/api"));
            if (e != null) return e;
            e = expectPatterns("{\"/api\",\"//api\"}（构造顺序相反）",
                    idx("//api", "/api").matchAll("/api/x"), List.of("//api", "/api"));
            if (e != null) return e;
            e = expectPatterns("ignoreCase 下大小写等价的两条", idxI("/Api", "/api").matchAll("/API/x"),
                    List.of("/Api", "/api"));
            if (e != null) return e;
            e = expectPatterns("matchAll(\"/nope\")", asc.matchAll("/nope"), List.of());
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ explain（2）

        out.add(new Scenario("explain/true-round", () -> {
            String e;
            PrefixIndex a = idx("/api", "/api/v1", "/api/v1/admin");
            e = expectDecision("第一轮就剩一条 → SEGMENTS", a.explain("/api/v1/admin/42"),
                    "SEGMENTS", "/api/v1/admin", 3);
            if (e != null) return e;

            PrefixIndex c = idx("/api/v1", "/api/*", "/api/v1/*");
            e = expectDecision("段数最多者先胜出 → SEGMENTS", c.explain("/api/v1/x"),
                    "SEGMENTS", "/api/v1/*", 3);
            if (e != null) return e;

            PrefixIndex d = idx("/api/*", "/api/v1");
            e = expectDecision("第二轮才分开 → LITERAL", d.explain("/api/v1/x"),
                    "LITERAL", "/api/v1", 2);
            if (e != null) return e;

            PrefixIndex f = idx("/api", "//api");
            e = expectDecision("第三轮才分开 → LEXICO", f.explain("/api/users"),
                    "LEXICO", "//api", 2);
            if (e != null) return e;

            PrefixIndex g = idx("/api/", "/api");
            e = expectDecision("归一化等价的两条 → LEXICO", g.explain("/api/users"),
                    "LEXICO", "/api", 2);
            if (e != null) return e;

            PrefixIndex h = idx("/a/*", "/a/b");
            e = expectDecision("LITERAL 的另一处", h.explain("/a/b/x"),
                    "LITERAL", "/a/b", 2);
            if (e != null) return e;

            PrefixIndex i = idx("/api/*");
            e = expectDecision("压根没有命中 → MISS", i.explain("/nope"), "MISS", null, 0);
            if (e != null) return e;
            e = expectDecision("空模式集 → MISS",
                    new PrefixIndex(new LinkedHashMap<>(), false).explain("/"), "MISS", null, 0);
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("explain/consistent-with-match", () -> {
            List<Object[]> cases = new ArrayList<>();
            cases.add(new Object[]{idx("/api", "/api/v1", "/api/v1/admin", "/api/*"), "/api/v1/admin/42"});
            cases.add(new Object[]{idx("/api", "/api/v1", "/api/v1/admin", "/api/*"), "/api/v1/other"});
            cases.add(new Object[]{idx("/api", "/api/v1", "/api/v1/admin", "/api/*"), "/api"});
            cases.add(new Object[]{idx("/api", "/api/v1", "/api/v1/admin", "/api/*"), "/nope"});
            cases.add(new Object[]{idx("/api", "//api", "/api/"), "/api/x"});
            cases.add(new Object[]{idx("/Api", "/api", "/API/V1"), "/api/v1"});
            cases.add(new Object[]{idxI("/Api", "/api", "/API/V1"), "/api/v1"});
            cases.add(new Object[]{idxI("/Api", "/api", "/API/V1"), "/api/x"});
            cases.add(new Object[]{b("/api", 5).add("/api/v1", -3).add("/api/*", 7).cs(), "/api/v1/x"});
            cases.add(new Object[]{idx("/"), "/"});
            cases.add(new Object[]{idx("/"), "/api"});
            cases.add(new Object[]{idx("*", "/a/b"), "/a/b/c"});
            for (Object[] c : cases) {
                PrefixIndex index = (PrefixIndex) c[0];
                String path = (String) c[1];
                Decision d = index.explain(path);
                Optional<Match> m = index.match(path);
                if (d == null) {
                    return "期望=explain(\"" + path + "\") 不返回 null 实际=null";
                }
                if (m == null) {
                    return "期望=match(\"" + path + "\") 不返回 null 实际=null";
                }
                String mp = m.isPresent() ? m.get().pattern() : null;
                if (!Objects.equals(d.pattern(), mp)) {
                    return "期望=match 与 explain 的胜者一致 实际=match=" + mp + " explain=" + d.pattern();
                }
                boolean miss = "MISS".equals(d.round());
                if (miss == m.isPresent()) {
                    return "期望=MISS 与「无命中」等价 实际=round=" + d.round()
                            + " match=" + (m.isPresent() ? m.get().pattern() : "empty");
                }
                if (miss && d.pattern() != null) {
                    return "期望=MISS 时 pattern 为 null 实际=" + d.pattern();
                }
                List<Match> all = index.matchAll(path);
                if (d.candidates() != all.size()) {
                    return "期望=candidates 等于命中总数 实际=candidates=" + d.candidates()
                            + " matchAll=" + all.size();
                }
                if (!miss) {
                    Optional<Match> r = index.resolve(path);
                    if (r == null || r.isEmpty()) {
                        return "期望=有命中时 resolve 也应有结果 实际="
                                + (r == null ? "null" : "empty");
                    }
                    int w = m.get().weight();
                    boolean seen = false;
                    for (Match x : all) {
                        if (x.pattern().equals(mp) && x.weight() == w) {
                            seen = true;
                            break;
                        }
                    }
                    if (!seen) {
                        return "期望=match 给出的 (模式,权重) 出现在 matchAll 里 实际=权重=" + w
                                + " 不在 " + patternsOf(all) + " 里";
                    }
                }
            }
            return null;
        }));

        // ------------------------------------------------ resolve（3）

        out.add(new Scenario("resolve/weight-first", () -> {
            String e;
            PrefixIndex a = b("/api", 100).add("/api/v1", -5).cs();
            e = expectPattern("resolve 先比权重", a.resolve("/api/v1/x"), "/api");
            if (e != null) return e;
            e = expectPattern("同一索引上 match 仍是段数优先", a.match("/api/v1/x"), "/api/v1");
            if (e != null) return e;
            e = expectWeight("resolve 结果带权重", a.resolve("/api/v1/x"), 100);
            if (e != null) return e;

            PrefixIndex c = b("/a", 1).add("/a/b", 9).add("/a/b/c", 3).cs();
            e = expectPattern("resolve 取权重最大者", c.resolve("/a/b/c"), "/a/b");
            if (e != null) return e;
            e = expectPattern("match 取段数最多者", c.match("/a/b/c"), "/a/b/c");
            if (e != null) return e;
            e = expectPatterns("matchAll 仍是三档降序（与权重无关）", c.matchAll("/a/b/c"),
                    List.of("/a/b/c", "/a/b", "/a"));
            if (e != null) return e;

            PrefixIndex d = b("/x", -1).add("/x/y", -7).cs();
            e = expectPattern("负权重之间也按降序", d.resolve("/x/y"), "/x");
            if (e != null) return e;
            e = expectWeight("负权重的 weight()", d.resolve("/x/y"), -1);
            if (e != null) return e;

            PrefixIndex f = b("/api", 3).add("/api/*", 3).add("/api/v1", 3).cs();
            e = expectPattern("三项同权重时仍回落到三档", f.resolve("/api/v1/x"), "/api/v1");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("resolve/tie-falls-back", () -> {
            String e;
            PrefixIndex a = b("/api/v1", 7).add("/api/*", 7).cs();
            e = expectPattern("权重并列 → 字面字符数多者胜", a.resolve("/api/v1/x"), "/api/v1");
            if (e != null) return e;

            PrefixIndex c = b("/api", 7).add("//api", 7).cs();
            e = expectPattern("权重并列 + 字面并列 → 字典序最小者胜", c.resolve("/api/x"), "//api");
            if (e != null) return e;

            PrefixIndex d = b("/api", 7).add("/api/v1", 7).cs();
            e = expectPattern("权重并列 → 段数多者胜", d.resolve("/api/v1/x"), "/api/v1");
            if (e != null) return e;

            PrefixIndex f = b("/api/v1/admin", 3).add("/api/v1", 3).add("/api", 3).cs();
            e = expectPattern("三项全并列 → 段数最多者胜", f.resolve("/api/v1/admin/9"), "/api/v1/admin");
            if (e != null) return e;

            PrefixIndex g = b("/api", -2).add("/api/", -2).cs();
            e = expectPattern("负权重并列 + 归一化等价 → 字典序", g.resolve("/api/x"), "/api");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("resolve/no-match-empty", () -> {
            String e;
            PrefixIndex a = b("/api", 5).add("/api/v1", 9).cs();
            e = expectEmpty("resolve(\"/healthz\")", a.resolve("/healthz"));
            if (e != null) return e;
            e = expectEmpty("resolve(\"/other/x/y\")", a.resolve("/other/x/y"));
            if (e != null) return e;

            PrefixIndex empty = new PrefixIndex(new LinkedHashMap<>(), false);
            e = expectEmpty("空模式集的 resolve(\"/api/v1\")", empty.resolve("/api/v1"));
            if (e != null) return e;
            e = expectEmpty("空模式集的 resolve(\"/\")", empty.resolve("/"));
            if (e != null) return e;

            PrefixIndex w = b("/api/*", 1).cs();
            e = expectEmpty("{\"/api/*\"}.resolve(\"/api\")（1 段不够）", w.resolve("/api"));
            if (e != null) return e;
            e = expectPattern("{\"/api/*\"}.resolve(\"/api/x\")", w.resolve("/api/x"), "/api/*");
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ robust（1）

        out.add(new Scenario("robust/edges-and-null", () -> {
            String e;
            PrefixIndex empty = new PrefixIndex(new LinkedHashMap<>(), false);
            e = expectEmpty("空模式集的 match(\"/api\")", empty.match("/api"));
            if (e != null) return e;
            e = expectPatterns("空模式集的 matchAll(\"/api\")", empty.matchAll("/api"), List.of());
            if (e != null) return e;
            e = expectEmpty("空模式集的 resolve(\"/api\")", empty.resolve("/api"));
            if (e != null) return e;
            e = expectDecision("空模式集的 explain(\"/api\")", empty.explain("/api"), "MISS", null, 0);
            if (e != null) return e;

            PrefixIndex withApi = idx("/", "/api");
            try {
                Optional<Match> r = withApi.match("");
                if (r == null) {
                    return "期望=空路径 match(\"\") 返回非 null 的 Optional 实际=null";
                }
            } catch (RuntimeException ex) {
                return "期望=空路径 match(\"\") 不抛异常 实际=抛了 " + ex.getClass().getName();
            }
            try {
                List<Match> r = withApi.matchAll("");
                if (r == null) {
                    return "期望=空路径 matchAll(\"\") 返回非 null 的列表 实际=null";
                }
            } catch (RuntimeException ex) {
                return "期望=空路径 matchAll(\"\") 不抛异常 实际=抛了 " + ex.getClass().getName();
            }
            try {
                Optional<Match> r = withApi.resolve("");
                if (r == null) {
                    return "期望=空路径 resolve(\"\") 返回非 null 的 Optional 实际=null";
                }
            } catch (RuntimeException ex) {
                return "期望=空路径 resolve(\"\") 不抛异常 实际=抛了 " + ex.getClass().getName();
            }
            try {
                Decision d = withApi.explain("");
                if (d == null) {
                    return "期望=空路径 explain(\"\") 返回非 null 的 Decision 实际=null";
                }
            } catch (RuntimeException ex) {
                return "期望=空路径 explain(\"\") 不抛异常 实际=抛了 " + ex.getClass().getName();
            }

            e = expectPattern("{\"/\",\"/api\"}.match(\"/\")", withApi.match("/"), "/");
            if (e != null) return e;
            e = expectPattern("{\"/\",\"/api\"}.match(\"/api\")", withApi.match("/api"), "/api");
            if (e != null) return e;
            e = expectPattern("{\"/\"}.match(\"//\")", idx("/").match("//"), "/");
            if (e != null) return e;
            e = expectEmpty("{\"/\"}.match(\"/api\")", idx("/").match("/api"));
            if (e != null) return e;
            e = expectEmpty("{\"/\"}.match(\"/api/v1\")", idx("/").match("/api/v1"));
            if (e != null) return e;

            e = expectIae(() -> new PrefixIndex(null, false), "new PrefixIndex(null, false)");
            if (e != null) return e;
            Map<String, Integer> nullKey = new LinkedHashMap<>();
            nullKey.put(null, 1);
            e = expectIae(() -> new PrefixIndex(nullKey, false), "模式集合里含 null 键");
            if (e != null) return e;
            Map<String, Integer> nullValue = new LinkedHashMap<>();
            nullValue.put("/api", null);
            e = expectIae(() -> new PrefixIndex(nullValue, false), "模式集合里含 null 权重");
            if (e != null) return e;
            e = expectIae(() -> withApi.match(null), "match(null)");
            if (e != null) return e;
            e = expectIae(() -> withApi.matchAll(null), "matchAll(null)");
            if (e != null) return e;
            e = expectIae(() -> withApi.resolve(null), "resolve(null)");
            if (e != null) return e;
            e = expectIae(() -> withApi.explain(null), "explain(null)");
            if (e != null) return e;

            e = expectIae(() -> withApi.match("/a b"), "路径含空格：match(\"/a b\")");
            if (e != null) return e;
            e = expectIae(() -> withApi.matchAll("/a\tb"), "路径含制表符：matchAll");
            if (e != null) return e;
            e = expectIae(() -> withApi.resolve("/a b"), "路径含空格：resolve");
            if (e != null) return e;
            e = expectIae(() -> withApi.explain("/a b"), "路径含空格：explain");
            if (e != null) return e;
            return null;
        }));

        // ------------------------------------------------ scale（1）

        out.add(new Scenario("scale/index-at-scale", SCALE_WATCHDOG_SECONDS, () -> {
            String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("java.class.path");
            ProcessBuilder pb = new ProcessBuilder(javaBin, SCALE_HEAP,
                    "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8",
                    "-cp", classpath, "ScaleProbe");
            pb.redirectErrorStream(true);

            Process proc;
            try {
                proc = pb.start();
            } catch (Exception ex) {
                return "期望=规模场景子进程能启动 实际=启动失败：" + ex;
            }

            StringBuilder sink = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                        synchronized (sink) {
                            sink.append(chunk);
                        }
                    }
                } catch (Exception ignored) {
                    // 子进程被强杀时读流会失败，忽略即可
                }
            }, "scale-probe-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished;
            try {
                finished = proc.waitFor(SCALE_CHILD_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            if (!finished) {
                proc.destroyForcibly();
                try {
                    reader.join(2000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return "期望=规模场景在 " + SCALE_CHILD_SECONDS + " 秒内给出 PASS/FAIL 实际="
                        + SCALE_CHILD_SECONDS + " 秒仍未结束（ScaleProbe 子进程已强杀）";
            }
            try {
                reader.join(5000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            String outText;
            synchronized (sink) {
                outText = sink.toString().trim();
            }
            String line = null;
            for (String l : outText.split("\n")) {
                String t = l.trim();
                if (t.startsWith("PASS ") || t.startsWith("FAIL ")) {
                    line = t;
                }
            }
            if (line == null) {
                String shown = outText.isEmpty() ? "(无输出)" : outText.replace('\n', ' ');
                return "期望=ScaleProbe 打印 PASS/FAIL 行 实际=退出码 "
                        + (proc.isAlive() ? "未知（仍存活）" : String.valueOf(proc.exitValue())) + "，输出=" + shown;
            }
            if (line.startsWith("PASS ")) {
                return null;
            }
            int bar = line.indexOf(' ', "FAIL ".length());
            return line.substring(bar < 0 ? "FAIL ".length() : bar).trim();
        }));

        return out;
    }

    // ------------------------------------------------------------- 辅助

    /** 带权重地拼一个模式集合。 */
    private static final class B {
        private final Map<String, Integer> map = new LinkedHashMap<>();

        B(String pattern, int weight) {
            map.put(pattern, weight);
        }

        B add(String pattern, int weight) {
            map.put(pattern, weight);
            return this;
        }

        /** ignoreCase = false 的索引。 */
        PrefixIndex cs() {
            return new PrefixIndex(map, false);
        }

        /** ignoreCase = true 的索引。 */
        PrefixIndex ci() {
            return new PrefixIndex(map, true);
        }
    }

    private static B b(String pattern, int weight) {
        return new B(pattern, weight);
    }

    private static PrefixIndex idx(String... patterns) {
        return fromList(Arrays.asList(patterns), false);
    }

    private static PrefixIndex idxI(String... patterns) {
        return fromList(Arrays.asList(patterns), true);
    }

    private static PrefixIndex fromList(List<String> patterns, boolean ignoreCase) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String p : patterns) {
            map.put(p, 0);
        }
        return new PrefixIndex(map, ignoreCase);
    }

    private static List<String> patternsOf(List<Match> matches) {
        List<String> out = new ArrayList<>();
        if (matches == null) {
            return out;
        }
        for (Match m : matches) {
            out.add(m == null ? null : m.pattern());
        }
        return out;
    }

    private static String expectPattern(String what, Optional<Match> result, String expected) {
        if (result == null) {
            return "期望=" + what + " 返回非 null 的 Optional 实际=null";
        }
        if (result.isEmpty()) {
            return "期望=" + what + " 命中 " + expected + " 实际=Optional.empty()";
        }
        String got = result.get().pattern();
        if (!expected.equals(got)) {
            return "期望=" + what + " 命中 " + expected + " 实际=" + got;
        }
        return null;
    }

    private static String expectWeight(String what, Optional<Match> result, int expected) {
        if (result == null) {
            return "期望=" + what + " 返回非 null 的 Optional 实际=null";
        }
        if (result.isEmpty()) {
            return "期望=" + what + " 有命中 实际=Optional.empty()";
        }
        int got = result.get().weight();
        if (expected != got) {
            return "期望=" + what + " 的 weight()==" + expected + " 实际=" + got;
        }
        return null;
    }

    private static String expectParams(String what, Optional<Match> result, List<String> expected) {
        if (result == null) {
            return "期望=" + what + " 返回非 null 的 Optional 实际=null";
        }
        if (result.isEmpty()) {
            return "期望=" + what + " 命中并捕获 " + expected + " 实际=Optional.empty()";
        }
        List<String> got = result.get().params();
        if (got == null) {
            return "期望=" + what + " 的 params()==" + expected + " 实际=null";
        }
        if (!expected.equals(got)) {
            return "期望=" + what + " 的 params()==" + expected + " 实际=" + got;
        }
        return null;
    }

    private static String expectPatterns(String what, List<Match> result, List<String> expected) {
        if (result == null) {
            return "期望=" + what + " 返回非 null 的列表 实际=null";
        }
        List<String> got = new ArrayList<>();
        for (Match m : result) {
            if (m == null) {
                return "期望=" + what + " 的元素非 null 实际=含 null 元素";
            }
            got.add(m.pattern());
        }
        if (!expected.equals(got)) {
            return "期望=" + what + " → " + expected + " 实际=" + got;
        }
        return null;
    }

    private static String expectEmpty(String what, Optional<Match> result) {
        if (result == null) {
            return "期望=" + what + " 返回非 null 的 Optional 实际=null";
        }
        if (result.isPresent()) {
            return "期望=" + what + " 无命中的模式 实际=命中 " + result.get().pattern();
        }
        return null;
    }

    private static String expectDecision(String what, Decision d, String round, String pattern, int candidates) {
        if (d == null) {
            return "期望=" + what + " 返回非 null 的 Decision 实际=null";
        }
        if (!Objects.equals(round, d.round())) {
            return "期望=" + what + " 的 round()==" + round + " 实际=" + d.round();
        }
        if (!Objects.equals(pattern, d.pattern())) {
            return "期望=" + what + " 的 pattern()==" + pattern + " 实际=" + d.pattern();
        }
        if (candidates != d.candidates()) {
            return "期望=" + what + " 的 candidates()==" + candidates + " 实际=" + d.candidates();
        }
        return null;
    }

    private static String expectParseError(Runnable body, String what) {
        try {
            body.run();
        } catch (PrefixParseException expected) {
            return null;
        } catch (Throwable t) {
            return "期望=" + what + " 抛 PrefixParseException 实际=抛了 " + t.getClass().getName();
        }
        return "期望=" + what + " 抛 PrefixParseException 实际=没有抛出";
    }

    private static String expectIae(Runnable body, String what) {
        try {
            body.run();
        } catch (IllegalArgumentException expected) {
            return null;
        } catch (Throwable t) {
            return "期望=" + what + " 抛 IllegalArgumentException 实际=抛了 " + t.getClass().getName();
        }
        return "期望=" + what + " 抛 IllegalArgumentException 实际=没有抛出";
    }

    private Checker() {
    }
}
