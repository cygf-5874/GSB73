import prefixidx.Match;
import prefixidx.PrefixIndex;
import prefixidx.PrefixParseException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * prefixidx 的固定验收程序（勿改）。
 *
 * <p>11 个场景覆盖 README「对外契约」的 11 条：parse 3 / match 4 / params 2 / all 1 / robust 1。
 * 每个场景都在**独立 JVM 子进程**里跑（{@link ProcessBuilder}），带 30 秒看门狗，超时判不通过；
 * 某个场景失败不会遮蔽其余场景（失败不早退）。判据全部确定性：没有墙钟、没有随机源，
 * 顺序相关的断言只依赖 (段数 → 字面字符数 → 字符串字典序) 这条写死的规则。
 *
 * <p>用法：java -cp out Checker [-list] [--only &lt;组名&gt;[,&lt;组名&gt;...]]
 */
public final class Checker {

    /** 单场景看门狗：子进程超过这个时间还没给出结论就判不通过。 */
    private static final long WATCHDOG_SECONDS = 30L;

    private interface Body {
        /** 返回 null 表示 PASS，否则返回可判定的失败原因（内含 期望=… 实际=…）。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String name;
        final String group;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
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
                    if (!g.isEmpty()) onlyGroups.add(g);
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
            String line = runInChildProcess(s.name);
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
    private static String runInChildProcess(String name) {
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
            finished = proc.waitFor(WATCHDOG_SECONDS, TimeUnit.SECONDS);
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
            return "FAIL " + name + "  期望=场景在 " + WATCHDOG_SECONDS + " 秒内给出 PASS/FAIL 实际="
                    + WATCHDOG_SECONDS + " 秒看门狗超时（子进程已强杀）";
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

        // ---------------- parse ----------------

        out.add(new Scenario("parse/legal-patterns-accepted", () -> {
            String e;
            PrefixIndex idx = idx("/", "/api", "/api/v1", "/API", "/healthz");
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
            e = expectEmpty("match(\"/other/x\")", idx.match("/other/x"));
            if (e != null) return e;
            e = expectEmpty("match(\"/apix\")", idx.match("/apix"));
            if (e != null) return e;

            e = expectPattern("{\"//api//v1//\"} 的 match(\"/api/v1/users\")",
                    idx("//api//v1//").match("/api/v1/users"), "//api//v1//");
            if (e != null) return e;
            e = expectPattern("{\"/api/\"} 的 match(\"/api/x\")", idx("/api/").match("/api/x"), "/api/");
            if (e != null) return e;

            PrefixIndex trailing = idx("/api/*/");
            e = expectPattern("{\"/api/*/\"} 的 match(\"/api/users\")", trailing.match("/api/users"), "/api/*/");
            if (e != null) return e;
            e = expectParams("{\"/api/*/\"} 的 match(\"/api/users\")", trailing.match("/api/users"), List.of("users"));
            if (e != null) return e;

            PrefixIndex star = idx("*");
            e = expectPattern("{\"*\"} 的 match(\"/anything\")", star.match("/anything"), "*");
            if (e != null) return e;
            e = expectParams("{\"*\"} 的 match(\"/a/b\")", star.match("/a/b"), List.of("a"));
            if (e != null) return e;
            e = expectEmpty("{\"*\"} 的 match(\"/\")（0 段）", star.match("/"));
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
            e = expectParseError(() -> idx("/ap i"), "段内含空白：/ap i");
            if (e != null) return e;
            e = expectParseError(() -> idx("/tab\there"), "段内含空白：/tab\\there");
            if (e != null) return e;
            e = expectParseError(() -> idx("/ok", "/bad/*/x"), "同一批里混入非法模式");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("parse/normalization-effective", () -> {
            String e;

            PrefixIndex a = idx("//api//v1//");
            e = expectPattern("{\"//api//v1//\"} 的 match(\"/api/v1/users\")", a.match("/api/v1/users"), "//api//v1//");
            if (e != null) return e;
            e = expectPattern("{\"//api//v1//\"} 的 match(\"api/v1\")", a.match("api/v1"), "//api//v1//");
            if (e != null) return e;
            e = expectPattern("{\"//api//v1//\"} 的 match(\"/api//v1//x\")", a.match("/api//v1//x"), "//api//v1//");
            if (e != null) return e;
            e = expectEmpty("{\"//api//v1//\"} 的 match(\"/api\")", a.match("/api"));
            if (e != null) return e;

            PrefixIndex bare = idx("/api");
            PrefixIndex trailing = idx("/api/");
            for (String p : List.of("/api", "/api/", "//api", "/api/x", "api", "/apix")) {
                boolean x = bare.match(p).isPresent();
                boolean y = trailing.match(p).isPresent();
                if (x != y) {
                    return "期望=「/api」与「/api/」在路径 " + p + " 上结论一致 实际=不一致（/api=" + x + " /api/=" + y + "）";
                }
            }

            PrefixIndex root = idx("/");
            e = expectPattern("{\"/\"} 的 match(\"/\")", root.match("/"), "/");
            if (e != null) return e;
            e = expectPattern("{\"/\"} 的 match(\"\")", root.match(""), "/");
            if (e != null) return e;
            e = expectPattern("{\"/\"} 的 match(\"//\")", root.match("//"), "/");
            if (e != null) return e;
            e = expectEmpty("{\"/\"} 的 match(\"/api\")", root.match("/api"));
            if (e != null) return e;

            PrefixIndex ci = idxI("/API/V1");
            e = expectPattern("ignoreCase=true: match(\"/api/v1/users\")", ci.match("/api/v1/users"), "/API/V1");
            if (e != null) return e;
            e = expectPattern("ignoreCase=true: match(\"/Api/V1\")", ci.match("/Api/V1"), "/API/V1");
            if (e != null) return e;
            PrefixIndex cs = idx("/API/V1");
            e = expectEmpty("ignoreCase=false: match(\"/api/v1\")", cs.match("/api/v1"));
            if (e != null) return e;
            e = expectPattern("ignoreCase=true: match(\"/API\")", idxI("/api").match("/API"), "/api");
            if (e != null) return e;
            return null;
        }));

        // ---------------- match ----------------

        out.add(new Scenario("match/longest-segment-count-wins", () -> {
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

            PrefixIndex b = idx("/api", "/api/*");
            e = expectPattern("{\"/api\",\"/api/*\"} 的 match(\"/api/users\")", b.match("/api/users"), "/api/*");
            if (e != null) return e;
            e = expectPattern("{\"/api\",\"/api/*\"} 的 match(\"/api\")", b.match("/api"), "/api");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("match/tie-by-literal-chars", () -> {
            String e;
            PrefixIndex a = idx("/api/*", "/api/v1");
            e = expectPattern("{\"/api/*\",\"/api/v1\"} 的 match(\"/api/v1/x\")", a.match("/api/v1/x"), "/api/v1");
            if (e != null) return e;
            e = expectPattern("{\"/api/*\",\"/api/v1\"} 的 match(\"/api/orders/7\")", a.match("/api/orders/7"), "/api/*");
            if (e != null) return e;

            PrefixIndex b = idx("/api/v1/*", "/api/v1/v2");
            e = expectPattern("{\"/api/v1/*\",\"/api/v1/v2\"} 的 match(\"/api/v1/v2/x\")",
                    b.match("/api/v1/v2/x"), "/api/v1/v2");
            if (e != null) return e;

            PrefixIndex c = idx("/a/*", "/a/b");
            e = expectPattern("{\"/a/*\",\"/a/b\"} 的 match(\"/a/b\")", c.match("/a/b"), "/a/b");
            if (e != null) return e;

            PrefixIndex d = idx("/x/y/*", "/x/y/z");
            e = expectPattern("{\"/x/y/*\",\"/x/y/z\"} 的 match(\"/x/y/z\")", d.match("/x/y/z"), "/x/y/z");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("match/tie-by-lexicographic", () -> {
            String e;
            PrefixIndex a = idxI("/Api", "/api");
            e = expectPattern("ignoreCase=true {\"/Api\",\"/api\"} 的 match(\"/api/x\")", a.match("/api/x"), "/Api");
            if (e != null) return e;

            PrefixIndex b = idxI("/APi", "/Api");
            e = expectPattern("ignoreCase=true {\"/APi\",\"/Api\"} 的 match(\"/API/x\")", b.match("/API/x"), "/APi");
            if (e != null) return e;

            PrefixIndex c = idx("/api", "//api");
            e = expectPattern("{\"/api\",\"//api\"} 的 match(\"/api/users\")", c.match("/api/users"), "//api");
            if (e != null) return e;
            PrefixIndex c2 = idx("//api", "/api");
            e = expectPattern("{\"//api\",\"/api\"} 的 match(\"/api/users\")（构造顺序相反）",
                    c2.match("/api/users"), "//api");
            if (e != null) return e;

            PrefixIndex d = idx("/api/", "/api");
            e = expectPattern("{\"/api/\",\"/api\"} 的 match(\"/api/users\")", d.match("/api/users"), "/api");
            if (e != null) return e;

            PrefixIndex f = idx("/*", "*");
            e = expectPattern("{\"/*\",\"*\"} 的 match(\"/x\")", f.match("/x"), "*");
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("match/no-match-returns-empty", () -> {
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

            PrefixIndex empty = new PrefixIndex(List.of(), false);
            e = expectEmpty("空模式集的 match(\"/api/v1\")", empty.match("/api/v1"));
            if (e != null) return e;
            e = expectPatterns("空模式集的 matchAll(\"/api/v1\")", empty.matchAll("/api/v1"), List.of());
            if (e != null) return e;
            e = expectPatterns("无命中时的 matchAll(\"/healthz\")", idx.matchAll("/healthz"), List.of());
            if (e != null) return e;
            return null;
        }));

        // ---------------- params ----------------

        out.add(new Scenario("params/wildcard-capture", () -> {
            String e;
            PrefixIndex idx = idx("/api", "/api/*");
            Optional<Match> hit = idx.match("/api/users");
            e = expectPattern("{\"/api\",\"/api/*\"} 的 match(\"/api/users\")", hit, "/api/*");
            if (e != null) return e;
            e = expectParams("{\"/api\",\"/api/*\"} 的 match(\"/api/users\")", hit, List.of("users"));
            if (e != null) return e;
            e = expectParams("match(\"/api/v1/x\")", idx.match("/api/v1/x"), List.of("v1"));
            if (e != null) return e;
            e = expectParams("match(\"/api/a%20b\")（原样，不做 URL 解码）", idx.match("/api/a%20b"), List.of("a%20b"));
            if (e != null) return e;
            e = expectParams("match(\"/api/Users%2FAdmin\")", idx.match("/api/Users%2FAdmin"), List.of("Users%2FAdmin"));
            if (e != null) return e;
            e = expectParams("match(\"/api/one/two/three\")（捕获的是通配位那一段）",
                    idx.match("/api/one/two/three"), List.of("one"));
            if (e != null) return e;

            e = expectPattern("{\"/api\",\"/api/*\"} 的 match(\"/api\")", idx.match("/api"), "/api");
            if (e != null) return e;
            e = expectParams("{\"/api\"} 命中时的 params 是空列表", idx.match("/api"), List.of());
            if (e != null) return e;
            e = expectEmpty("{\"/api/*\"} 的 match(\"/api\")（只有 1 段）", idx("/api/*").match("/api"));
            if (e != null) return e;
            e = expectEmpty("{\"/api/*\"} 的 match(\"/api/\")（归一化后只有 1 段）", idx("/api/*").match("/api/"));
            if (e != null) return e;
            return null;
        }));

        out.add(new Scenario("params/capture-in-deeper-path", () -> {
            String e;
            PrefixIndex idx = idx("/api/*", "/api/v1/*");
            e = expectPattern("match(\"/api/v1/users/42\")", idx.match("/api/v1/users/42"), "/api/v1/*");
            if (e != null) return e;
            e = expectParams("match(\"/api/v1/users/42\")", idx.match("/api/v1/users/42"), List.of("users"));
            if (e != null) return e;
            e = expectPattern("match(\"/api/v1/v2/v3\")", idx.match("/api/v1/v2/v3"), "/api/v1/*");
            if (e != null) return e;
            e = expectParams("match(\"/api/v1/v2/v3\")", idx.match("/api/v1/v2/v3"), List.of("v2"));
            if (e != null) return e;
            e = expectPattern("match(\"/api/v1\")", idx.match("/api/v1"), "/api/*");
            if (e != null) return e;
            e = expectParams("match(\"/api/v1\")", idx.match("/api/v1"), List.of("v1"));
            if (e != null) return e;
            e = expectParams("match(\"/api/orders/7\")", idx.match("/api/orders/7"), List.of("orders"));
            if (e != null) return e;
            e = expectEmpty("match(\"/api\")（两个模式都至少要 2 段）", idx.match("/api"));
            if (e != null) return e;
            return null;
        }));

        // ---------------- all ----------------

        out.add(new Scenario("all/matchall-sorted-and-stable", () -> {
            String e;
            List<String> base = List.of("/api", "/api/v1", "/api/*", "/api/v1/admin", "/healthz");
            List<String> reversed = new ArrayList<>(base);
            Collections.reverse(reversed);
            List<String> other = List.of("/healthz", "/api/v1/admin", "/api/*", "/api/v1", "/api");

            PrefixIndex asc = new PrefixIndex(base, false);
            PrefixIndex desc = new PrefixIndex(reversed, false);
            PrefixIndex shuf = new PrefixIndex(other, false);
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

            e = expectPatterns("{\"/api\",\"//api\"} 的 matchAll(\"/api/x\")",
                    idx("/api", "//api").matchAll("/api/x"), List.of("//api", "/api"));
            if (e != null) return e;
            e = expectPatterns("{\"/api\",\"//api\"} 的 matchAll(\"/api/x\")（构造顺序相反）",
                    idx("//api", "/api").matchAll("/api/x"), List.of("//api", "/api"));
            if (e != null) return e;
            e = expectPatterns("ignoreCase 下大小写等价的两条", idxI("/Api", "/api").matchAll("/API/x"),
                    List.of("/Api", "/api"));
            if (e != null) return e;
            e = expectPatterns("matchAll(\"/nope\")", asc.matchAll("/nope"), List.of());
            if (e != null) return e;
            return null;
        }));

        // ---------------- robust ----------------

        out.add(new Scenario("robust/edges-and-null", () -> {
            String e;
            PrefixIndex root = idx("/");
            e = expectPattern("{\"/\"} 的 match(\"/\")", root.match("/"), "/");
            if (e != null) return e;
            e = expectEmpty("{\"/\"} 的 match(\"/api\")", root.match("/api"));
            if (e != null) return e;
            e = expectEmpty("{\"/\"} 的 match(\"/api/v1\")", root.match("/api/v1"));
            if (e != null) return e;

            PrefixIndex withApi = idx("/", "/api");
            e = expectPattern("{\"/\",\"/api\"} 的 match(\"/\")", withApi.match("/"), "/");
            if (e != null) return e;
            e = expectPattern("{\"/\",\"/api\"} 的 match(\"/api\")", withApi.match("/api"), "/api");
            if (e != null) return e;

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

            e = expectIae(() -> new PrefixIndex(null, false), "new PrefixIndex(null, false)");
            if (e != null) return e;
            e = expectIae(() -> new PrefixIndex(Arrays.asList("/api", null), false), "模式列表里含 null 元素");
            if (e != null) return e;
            e = expectIae(() -> withApi.match(null), "match(null)");
            if (e != null) return e;
            e = expectIae(() -> withApi.matchAll(null), "matchAll(null)");
            if (e != null) return e;

            PrefixIndex empty = new PrefixIndex(List.of(), false);
            e = expectEmpty("空模式集的 match(\"/\")", empty.match("/"));
            if (e != null) return e;
            e = expectPatterns("空模式集的 matchAll(\"/\")", empty.matchAll("/"), List.of());
            if (e != null) return e;
            return null;
        }));

        return out;
    }

    // ------------------------------------------------------------- 辅助

    private static PrefixIndex idx(String... patterns) {
        return new PrefixIndex(Arrays.asList(patterns), false);
    }

    private static PrefixIndex idxI(String... patterns) {
        return new PrefixIndex(Arrays.asList(patterns), true);
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
