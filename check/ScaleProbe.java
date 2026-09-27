import prefixidx.Decision;
import prefixidx.Match;
import prefixidx.PrefixIndex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 规模探针（固定件的组成部分，勿改）。由 {@code Checker} 的 {@code scale} 场景在
 * <b>{@code -Xmx96m} 的独立子 JVM</b> 里拉起：
 *
 * <pre>java -Xmx96m -cp &lt;classpath&gt; ScaleProbe</pre>
 *
 * <p>它自己确定性地生成 {@value #PATTERN_COUNT} 条模式（段数 2..6，混合字面段与尾部通配段）
 * 与 {@value #QUERY_COUNT} 条查询（一半命中、一半不命中），然后
 * <ol>
 *   <li>用**被测实现**跑完所有查询，得到一份结果指纹；</li>
 *   <li>用**本文件自带的参照实现**（逐条枚举全部模式，逐段比对）跑完同一批查询，
 *       得到同一套格式的结果指纹；</li>
 *   <li>两份 SHA-256 指纹必须完全相同；前 {@value #SPOT_CHECK} 条查询还要逐行完整比对；</li>
 *   <li>另在 4 个线程上并发重跑前 {@value #CONCURRENT_QUERIES} 条查询，指纹必须与单线程一致。</li>
 * </ol>
 *
 * <p>总耗时上限 {@value #BUDGET_MS} 毫秒（按本机标定，跑题机慢按比例放宽）。
 * 判据全部确定性：随机源是写死的 splitmix64，不依赖墙钟、Locale、时区或 HashMap 迭代顺序。
 *
 * <p>只打印一行 {@code PASS ...} 或 {@code FAIL ...}（格式与固定件统一）。
 */
public final class ScaleProbe {

    /** 模式条数。 */
    static final int PATTERN_COUNT = 100_000;
    /** 查询次数。 */
    static final int QUERY_COUNT = 150_000;
    /** 逐条完整比对的查询条数（其余靠指纹兜住）。 */
    static final int SPOT_CHECK = 500;
    /** 并发重跑的查询条数。 */
    static final int CONCURRENT_QUERIES = 2_000;
    /** 并发线程数。 */
    static final int CONCURRENT_THREADS = 4;
    /** 总耗时上限（毫秒）。 */
    static final long BUDGET_MS = 90_000L;
    /** 字面段词汇表大小。 */
    static final int VOCAB = 512;
    /** 随机种子（写死，保证确定性）。 */
    static final long SEED = 0x243F6A8885A308D3L;

    /** 通配段的内部编号。 */
    private static final int WILDCARD = -1;

    // ------------------------------------------------------------ 生成的数据

    private static String[] pRaw;
    private static int[] pSeg;
    private static int[] pStart;
    private static int[] pLen;
    private static int[] pLit;
    private static int[] pWeight;
    private static boolean[] pWild;

    private static int[] qSeg;
    private static int[] qStart;
    private static String[] qPath;

    private static String[] idToken;
    private static int[] hitBuf = new int[4096];

    public static void main(String[] args) {
        long t0 = System.nanoTime();
        String verdict;
        try {
            verdict = run();
        } catch (Throwable t) {
            verdict = "期望=规模场景正常跑完 实际=抛了 " + t;
            t.printStackTrace(System.out);
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("规模场景：模式=" + PATTERN_COUNT + " 查询=" + QUERY_COUNT
                + " 堆上限=96m 本次总耗时=" + totalMs + " ms（上限 " + BUDGET_MS + " ms）");
        if (verdict == null && totalMs > BUDGET_MS) {
            verdict = "期望=规模场景在 " + BUDGET_MS + " 毫秒内跑完 实际=耗时 " + totalMs + " 毫秒（超时）";
        }
        if (verdict == null) {
            System.out.println("PASS scale/index-at-scale");
            System.out.flush();
            System.exit(0);
        }
        System.out.println("FAIL scale/index-at-scale  " + verdict);
        System.out.flush();
        System.exit(1);
    }

    // ---------------------------------------------------------------- 主流程

    private static String run() throws Exception {
        long t = System.nanoTime();
        generate();
        long genMs = millisSince(t);

        // 1) 被测实现
        t = System.nanoTime();
        PrefixIndex index = new PrefixIndex(patternMap(), false);
        long buildMs = millisSince(t);
        t = System.nanoTime();
        MessageDigest solDigest = MessageDigest.getInstance("SHA-256");
        String[] spot = new String[SPOT_CHECK];
        for (int i = 0; i < QUERY_COUNT; i++) {
            String line = solutionLine(index, i);
            if (i < SPOT_CHECK) {
                spot[i] = line;
            }
            solDigest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        long solMs = millisSince(t);

        // 2) 参照实现
        t = System.nanoTime();
        MessageDigest refDigest = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < QUERY_COUNT; i++) {
            String line = referenceLine(i);
            if (i < SPOT_CHECK && !line.equals(spot[i])) {
                return "期望=第 " + i + " 条查询（" + qPath[i] + "）与参照实现一致"
                        + " 实际=被测实现给出「" + spot[i] + "」、参照实现给出「" + line + "」";
            }
            refDigest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        long refMs = millisSince(t);

        String solHex = hex(solDigest.digest());
        String refHex = hex(refDigest.digest());
        if (!solHex.equals(refHex)) {
            return "期望=" + QUERY_COUNT + " 条查询的结果指纹与参照实现一致"
                    + " 实际=被测=" + solHex + " 参照=" + refHex;
        }

        // 3) 只读并发（对外契约第 14 条）
        t = System.nanoTime();
        String conc = concurrentCheck(index, solHexForFirst(index));
        long concMs = millisSince(t);
        if (conc != null) {
            return conc;
        }

        System.out.println("  生成=" + genMs + " ms 建索引=" + buildMs + " ms 被测查询=" + solMs
                + " ms 参照查询=" + refMs + " ms 并发=" + concMs + " ms 指纹=" + solHex.substring(0, 16));
        return null;
    }

    /** 单线程跑前 {@value #CONCURRENT_QUERIES} 条查询的指纹，供并发阶段比对。 */
    private static String solHexForFirst(PrefixIndex index) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < CONCURRENT_QUERIES; i++) {
            md.update(solutionLine(index, i).getBytes(StandardCharsets.UTF_8));
        }
        return hex(md.digest());
    }

    private static String concurrentCheck(PrefixIndex index, String expected) throws Exception {
        String[] got = new String[CONCURRENT_THREADS];
        String[] err = new String[CONCURRENT_THREADS];
        Thread[] threads = new Thread[CONCURRENT_THREADS];
        for (int k = 0; k < CONCURRENT_THREADS; k++) {
            final int slot = k;
            threads[k] = new Thread(() -> {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    for (int i = 0; i < CONCURRENT_QUERIES; i++) {
                        md.update(solutionLine(index, i).getBytes(StandardCharsets.UTF_8));
                    }
                    got[slot] = hex(md.digest());
                } catch (Throwable t) {
                    err[slot] = String.valueOf(t);
                }
            }, "scale-concurrent-" + k);
            threads[k].start();
        }
        for (Thread th : threads) {
            th.join();
        }
        for (int k = 0; k < CONCURRENT_THREADS; k++) {
            if (err[k] != null) {
                return "期望=读方法可被多线程并发调用 实际=第 " + k + " 个线程抛了 " + err[k];
            }
            if (!expected.equals(got[k])) {
                return "期望=并发读与单线程读给出同一指纹 实际=单线程=" + expected
                        + " 第 " + k + " 个线程=" + got[k];
            }
        }
        return null;
    }

    // ------------------------------------------------------ 被测实现的结果行

    private static String solutionLine(PrefixIndex index, int i) {
        String path = qPath[i];
        switch (i & 3) {
            case 0: {
                Optional<Match> r = index.match(path);
                return "M|" + show1(r);
            }
            case 1: {
                List<Match> r = index.matchAll(path);
                return "A|" + showAll(r);
            }
            case 2: {
                Decision d = index.explain(path);
                return "E|" + showDecision(d);
            }
            default: {
                Optional<Match> r = index.resolve(path);
                return "R|" + show1(r);
            }
        }
    }

    private static String show1(Optional<Match> r) {
        if (r == null) {
            return "<null-optional>";
        }
        if (r.isEmpty()) {
            return "empty";
        }
        Match m = r.get();
        if (m == null) {
            return "<null-match>";
        }
        return m.pattern() + "#" + m.weight() + "#" + joinParams(m.params());
    }

    private static String showAll(List<Match> r) {
        if (r == null) {
            return "<null-list>";
        }
        StringBuilder sb = new StringBuilder();
        for (Match m : r) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(m == null ? "<null-match>" : m.pattern() + "#" + m.weight());
        }
        return sb.toString();
    }

    private static String showDecision(Decision d) {
        if (d == null) {
            return "<null-decision>";
        }
        return d.round() + "#" + d.pattern() + "#" + d.candidates();
    }

    private static String joinParams(List<String> params) {
        if (params == null) {
            return "<null-params>";
        }
        StringBuilder sb = new StringBuilder();
        for (String p : params) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 参照实现

    /**
     * 参照实现：对每条查询**逐条枚举全部模式**、逐段比对，命中集合按
     * （段数 → 字面字符数 → 模式原文字典序）三档裁决；`resolve` 先比权重。
     * 语义与 README「对外契约」一致，写法上完全不建索引。
     */
    private static String referenceLine(int i) {
        int qOff = qStart[i];
        int qLen = qStart[i + 1] - qOff;
        switch (i & 3) {
            case 0: {
                int best = refBestMatch(qOff, qLen);
                if (best < 0) {
                    return "M|empty";
                }
                return "M|" + pRaw[best] + "#" + pWeight[best] + "#" + refCaptured(best, qOff);
            }
            case 1: {
                int n = collectHits(qOff, qLen);
                sortByTier(hitBuf, n);
                StringBuilder sb = new StringBuilder("A|");
                for (int k = 0; k < n; k++) {
                    if (k > 0) {
                        sb.append(';');
                    }
                    sb.append(pRaw[hitBuf[k]]).append('#').append(pWeight[hitBuf[k]]);
                }
                return sb.toString();
            }
            case 2: {
                int n = collectHits(qOff, qLen);
                if (n == 0) {
                    return "E|MISS#null#0";
                }
                return "E|" + refRoundAndWinner(n) + "#" + n;
            }
            default: {
                int best = refBestResolve(qOff, qLen);
                if (best < 0) {
                    return "R|empty";
                }
                return "R|" + pRaw[best] + "#" + pWeight[best] + "#" + refCaptured(best, qOff);
            }
        }
    }

    private static int refBestMatch(int qOff, int qLen) {
        int best = -1;
        for (int p = 0; p < PATTERN_COUNT; p++) {
            int pl = pLen[p];
            if (pl > qLen) {
                continue;
            }
            int off = pStart[p];
            int j = 0;
            for (; j < pl; j++) {
                int s = pSeg[off + j];
                if (s != WILDCARD && s != qSeg[qOff + j]) {
                    break;
                }
            }
            if (j < pl) {
                continue;
            }
            if (best < 0 || tierCompare(p, best) < 0) {
                best = p;
            }
        }
        return best;
    }

    private static int refBestResolve(int qOff, int qLen) {
        int best = -1;
        for (int p = 0; p < PATTERN_COUNT; p++) {
            int pl = pLen[p];
            if (pl > qLen) {
                continue;
            }
            int off = pStart[p];
            int j = 0;
            for (; j < pl; j++) {
                int s = pSeg[off + j];
                if (s != WILDCARD && s != qSeg[qOff + j]) {
                    break;
                }
            }
            if (j < pl) {
                continue;
            }
            if (best < 0) {
                best = p;
            } else if (pWeight[p] != pWeight[best]) {
                if (pWeight[p] > pWeight[best]) {
                    best = p;
                }
            } else if (tierCompare(p, best) < 0) {
                best = p;
            }
        }
        return best;
    }

    private static int collectHits(int qOff, int qLen) {
        int n = 0;
        for (int p = 0; p < PATTERN_COUNT; p++) {
            int pl = pLen[p];
            if (pl > qLen) {
                continue;
            }
            int off = pStart[p];
            int j = 0;
            for (; j < pl; j++) {
                int s = pSeg[off + j];
                if (s != WILDCARD && s != qSeg[qOff + j]) {
                    break;
                }
            }
            if (j < pl) {
                continue;
            }
            if (n == hitBuf.length) {
                hitBuf = Arrays.copyOf(hitBuf, hitBuf.length * 2);
            }
            hitBuf[n++] = p;
        }
        return n;
    }

    /** 三档裁决：返回负数表示 a 比 b 更优先。 */
    private static int tierCompare(int a, int b) {
        if (pLen[a] != pLen[b]) {
            return pLen[b] - pLen[a];
        }
        if (pLit[a] != pLit[b]) {
            return pLit[b] - pLit[a];
        }
        return pRaw[a].compareTo(pRaw[b]);
    }

    private static void sortByTier(int[] a, int n) {
        for (int k = 1; k < n; k++) {
            int v = a[k];
            int j = k - 1;
            while (j >= 0 && tierCompare(v, a[j]) < 0) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
    }

    /** 真实决胜的那一轮 + 胜出模式（返回 "轮次#模式"）。 */
    private static String refRoundAndWinner(int n) {
        int maxLen = 0;
        for (int k = 0; k < n; k++) {
            if (pLen[hitBuf[k]] > maxLen) {
                maxLen = pLen[hitBuf[k]];
            }
        }
        int cnt1 = 0;
        int only1 = -1;
        for (int k = 0; k < n; k++) {
            if (pLen[hitBuf[k]] == maxLen) {
                cnt1++;
                only1 = hitBuf[k];
            }
        }
        if (cnt1 == 1) {
            return "SEGMENTS#" + pRaw[only1];
        }
        int maxLit = -1;
        for (int k = 0; k < n; k++) {
            int p = hitBuf[k];
            if (pLen[p] == maxLen && pLit[p] > maxLit) {
                maxLit = pLit[p];
            }
        }
        int cnt2 = 0;
        int only2 = -1;
        for (int k = 0; k < n; k++) {
            int p = hitBuf[k];
            if (pLen[p] == maxLen && pLit[p] == maxLit) {
                cnt2++;
                only2 = p;
            }
        }
        if (cnt2 == 1) {
            return "LITERAL#" + pRaw[only2];
        }
        int win = -1;
        for (int k = 0; k < n; k++) {
            int p = hitBuf[k];
            if (pLen[p] == maxLen && pLit[p] == maxLit && (win < 0 || pRaw[p].compareTo(pRaw[win]) < 0)) {
                win = p;
            }
        }
        return "LEXICO#" + pRaw[win];
    }

    private static String refCaptured(int p, int qOff) {
        if (!pWild[p]) {
            return "";
        }
        return idToken[qSeg[qOff + pLen[p] - 1]];
    }

    // ---------------------------------------------------------------- 生成

    private static Map<String, Integer> patternMap() {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < PATTERN_COUNT; i++) {
            m.put(pRaw[i], pWeight[i]);
        }
        return m;
    }

    private static void generate() {
        HashMap<String, Integer> idOf = new HashMap<>();
        ArrayList<String> tokens = new ArrayList<>();

        Rng rng = new Rng(SEED);
        Set<String> seen = new HashSet<>();

        pRaw = new String[PATTERN_COUNT];
        pWeight = new int[PATTERN_COUNT];
        int[] segFlat = new int[PATTERN_COUNT * 6];
        pStart = new int[PATTERN_COUNT + 1];
        pLen = new int[PATTERN_COUNT];
        pLit = new int[PATTERN_COUNT];
        pWild = new boolean[PATTERN_COUNT];

        int cursor = 0;
        for (int i = 0; i < PATTERN_COUNT; i++) {
            String raw;
            int segCount;
            while (true) {
                int r = rng.nextInt(100);
                segCount = (r < 10) ? 2 : (r < 30) ? 3 : (r < 55) ? 4 : (r < 80) ? 5 : 6;
                boolean wild = segCount >= 3 && rng.nextInt(2) == 0;
                StringBuilder sb = new StringBuilder(segCount * 14);
                for (int j = 0; j < segCount; j++) {
                    sb.append('/');
                    if (wild && j == segCount - 1) {
                        sb.append('*');
                    } else {
                        sb.append(litToken(rng.nextInt(VOCAB)));
                    }
                }
                raw = sb.toString();
                if (seen.add(raw)) {
                    break;
                }
            }
            pRaw[i] = raw;
            pWeight[i] = rng.nextInt(2001) - 1000;
            pStart[i] = cursor;
            String[] parts = raw.substring(1).split("/");
            pLen[i] = parts.length;
            int lit = 0;
            for (int j = 0; j < parts.length; j++) {
                if ("*".equals(parts[j])) {
                    segFlat[cursor++] = WILDCARD;
                } else {
                    lit += parts[j].length();
                    int id = idOf.computeIfAbsent(parts[j], key -> {
                        tokens.add(key);
                        return tokens.size() - 1;
                    });
                    segFlat[cursor++] = id;
                }
            }
            pLit[i] = lit;
            pWild[i] = "*".equals(parts[parts.length - 1]);
        }
        pStart[PATTERN_COUNT] = cursor;
        pSeg = Arrays.copyOf(segFlat, cursor);

        // 查询：偶数号一定命中、奇数号一定不命中。
        qPath = new String[QUERY_COUNT];
        int[] qFlat = new int[QUERY_COUNT * 10];
        qStart = new int[QUERY_COUNT + 1];
        int qc = 0;
        for (int i = 0; i < QUERY_COUNT; i++) {
            String path;
            if ((i & 1) == 0) {
                String base = pRaw[rng.nextInt(PATTERN_COUNT)];
                String[] parts = base.substring(1).split("/");
                StringBuilder sb = new StringBuilder(base.length() + 32);
                for (int j = 0; j < parts.length; j++) {
                    sb.append('/');
                    if (j == parts.length - 1 && "*".equals(parts[j])) {
                        sb.append(litToken(rng.nextInt(VOCAB)));
                    } else {
                        sb.append(parts[j]);
                    }
                }
                int extra = rng.nextInt(3);
                for (int k = 0; k < extra; k++) {
                    sb.append('/').append(litToken(rng.nextInt(VOCAB)));
                }
                path = sb.toString();
            } else {
                StringBuilder sb = new StringBuilder(32);
                sb.append('/').append(missToken(rng.nextInt(8)));
                int extra = rng.nextInt(3);
                for (int k = 0; k < extra; k++) {
                    sb.append('/').append(litToken(rng.nextInt(VOCAB)));
                }
                path = sb.toString();
            }
            qPath[i] = path;
            qStart[i] = qc;
            String[] parts = path.substring(1).split("/");
            for (String part : parts) {
                qFlat[qc++] = idOf.computeIfAbsent(part, key -> {
                    tokens.add(key);
                    return tokens.size() - 1;
                });
            }
        }
        qStart[QUERY_COUNT] = qc;
        qSeg = Arrays.copyOf(qFlat, qc);
        idToken = tokens.toArray(new String[0]);
    }

    private static String litToken(int i) {
        StringBuilder sb = new StringBuilder(16);
        sb.append("seg").append(i);
        int extra = i % 9;
        for (int k = 0; k < extra; k++) {
            sb.append((char) ('a' + (k % 26)));
        }
        return sb.toString();
    }

    private static String missToken(int i) {
        StringBuilder sb = new StringBuilder(24);
        sb.append("nomatch").append(i);
        int extra = (i * 7) % 13;
        for (int k = 0; k < extra; k++) {
            sb.append((char) ('z' - (k % 26)));
        }
        return sb.toString();
    }

    /** 写死的 splitmix64：与 JDK 内部实现无关，跨 JVM 逐位一致。 */
    private static final class Rng {
        private long s;

        Rng(long seed) {
            this.s = seed;
        }

        long next() {
            long z = (s += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        int nextInt(int bound) {
            return (int) ((next() >>> 1) % bound);
        }
    }

    // ---------------------------------------------------------------- 杂项

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private ScaleProbe() {
    }
}
