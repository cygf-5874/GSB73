# prefixidx

一个**最长前缀匹配索引**（Java 17，只用 JDK 标准库，无第三方依赖）。
它把一批「模式 → 权重」编译成索引，对给定路径返回**胜出**的那条模式。

```java
Map<String, Integer> patterns = new LinkedHashMap<>();
patterns.put("/api", 10);
patterns.put("/api/v1", -3);
patterns.put("/api/*", 7);

PrefixIndex idx = new PrefixIndex(patterns, false);

idx.match("/api/v1/users").get().pattern();     // "/api/v1"
idx.match("/api/v1/users").get().weight();      // -3
idx.resolve("/api/v1/users").get().pattern();   // "/api"（权重 10 最大）
idx.matchAll("/api/v1/users").size();           // 3（按三档裁决降序）
idx.explain("/api/v1/users").round();           // "SEGMENTS"
idx.match("/api/orders").get().params();        // ["orders"]
```

## 目录

```
src/main/java/prefixidx/PrefixIndex.java          索引（当前为空壳）
src/main/java/prefixidx/Match.java                一次命中：模式 + 权重 + 捕获段（当前为空壳）
src/main/java/prefixidx/Decision.java             一次 explain 的裁决轨迹（当前为空壳）
src/main/java/prefixidx/PrefixParseException.java 模式不合法时抛出的异常
src/test/java/prefixidx/PrefixIndexTest.java      既有用例（13 个，手写 runner，无第三方框架）
scripts/build.sh                                  编译 src/ 到 out/
scripts/check.sh                                  固定验收入口（勿改）
check/Checker.java                                固定验收程序（勿改）
check/ScaleProbe.java                             规模探针（固定件的一部分，勿改）
```

## 语言版本前提

- Java 17（`javac` / `java`）。
- **只用 JDK 标准库**：不引入 Maven / Gradle，不引入任何第三方依赖。
- 构建产物放在 `out/` 与 `out-check/`，已在 `.gitignore` 中忽略。

## 怎么跑

```bash
bash scripts/build.sh                                  # 编译 src/ 到 out/
java -cp out prefixidx.PrefixIndexTest                 # 跑既有用例（13 个）
bash scripts/check.sh                                  # 固定验收，支持 -list 与 --only <组名>
bash scripts/check.sh -list                            # 列出全部 16 个验收场景
bash scripts/check.sh --only match                     # 只跑一组
```

起点状态：`src/main/java` 下的方法都是空壳，调用即抛 `UnsupportedOperationException`；
`java -cp out prefixidx.PrefixIndexTest` 打印 13 个 `FAIL`（**能编译，没有语法错**）；
`bash scripts/check.sh` 打印 `结果：通过 0/16` 并以退出码 1 结束。

> `check/` 下的两个类是**固定验收程序，勿改**；`src/test/java` 里既有用例的断言、
> `PrefixParseException` 的类名与继承关系，同样都是契约的一部分，勿改。
> 公开的**类型名、方法名、签名与异常类型**已经是最终形态（可以新增内部方法与私有成员），
> 不要改动下面 `API` 一节列出的这些。

## 对外契约

下面 14 条是 `prefixidx` 的**对外契约**，它们是本题验收点的唯一出处。
**它们是契约，不是「当前行为」的转述**；实现方式不限，但必须让这 14 条同时成立。

1. **模式语法**。`new PrefixIndex(Map<String, Integer> patterns, boolean ignoreCase)`：
   映射的**值是该模式的权重**（可正可负），**键是模式字符串**。模式由 `/` 分隔成段
   （先按第 3 条归一化），支持两种段：普通**字面段**（`/api/v1`）与**单段通配**（`/api/*`）。
   `*` 必须是**独占一段**的通配段，且只能出现在**最后一段**：`/api/*` 合法；
   `/api/*/x`、`*/x`（`*` 不在最后一段）与 `/a*b`、`/*x`（`*` 没有独占一段）都不合法，
   构造即抛 `PrefixParseException`。只含 `*` 的模式（`*`）合法（它吃掉路径的第 1 段，见第 5 条）。
   段里除 `*` 以外的一切字符都是**字面**字符，没有别的元字符。
2. **大小写**。构造参数 `ignoreCase`：为 `true` 时段的比较按 `String.equalsIgnoreCase` 的语义
   （**与默认 Locale 无关**）；为 `false` 时严格区分大小写。折叠**只用于比较**：
   模式与路径的原文都要原样保留下来（第 4 条、第 6 条都要用到原文）。
3. **归一化**（模式与路径都适用）。把串按 `/` 切段并**丢弃所有空段**，再按 `/` 前缀重新拼接。
   由此：多重斜杠折叠（`//api//v1//` ≡ `/api/v1`）、首尾斜杠无意义（`/api/` ≡ `/api`、`api` ≡ `/api`）、
   空串与 `/`、`//` 都归一化为 **0 段**。**段内不得含空白**（空格、制表符等，按
   `Character.isWhitespace` 判定）：模式里出现空白 → 构造即抛 `PrefixParseException`；
   路径里出现空白 → 抛 `IllegalArgumentException`。
4. **`Match`**。有三个访问器：`pattern()` 返回构造索引时传入的**模式原文**（不做归一化改写）；
   `weight()` 返回该模式的权重；`params()` 返回被 `*` 捕获的那一段。
   `params()` 取的是**路径原文**：不做 URL 解码、`ignoreCase` 为 `true` 时也**不折叠大小写**；
   模式里没有 `*` 时返回**空列表**。返回的列表不可修改。
5. **`match` 三档裁决**。`match(path)` 在**全部命中**的模式里按下面三档取唯一的一条，
   先后不能乱：① **段数多**的优先（段数指模式归一化后的段数，含通配段）；
   ② 段数并列时，**匹配到的字面段总字符数**多的优先（**按模式原文计**，`ignoreCase` 下也不折叠）；
   ③ 仍并列时，取**模式原文**按 `String.compareTo` 字典序**最小**者（按 UTF-16 code unit
   比较，不用 `Collator`）。匹配是**按段的前缀匹配**：模式与路径逐段对上即可，路径还可以有更深的段；
   `*` **恰好吃掉一个段**，不匹配 0 段 —— `/api/*` 不匹配 `/api`（只有 1 段），
   吃掉之后路径仍然可以有更深的段 —— `/api/*` 匹配 `/api/x/y` 并捕获 `x`，`*` 匹配 `/a/b` 并捕获 `a`。
6. **`explain`**。`explain(path)` 返回 `Decision`：`round()` ∈
   `{"SEGMENTS","LITERAL","LEXICO","MISS"}`、`pattern()` 是最终胜出的模式原文
   （`MISS` 时为 `null`，**不是空串**）、`candidates()` 是**命中总数**。
   `round` 必须是「**真实决胜的那一轮**」—— 即只保留到该轮结束时仍并列的模式：
   若第一轮（段数）结束时只剩一条则 `round = "SEGMENTS"`；若第二轮（字面字符数）结束时
   只剩一条则 `round = "LITERAL"`；若要到第三轮（字典序）才决出则 `round = "LEXICO"`；
   压根没有命中则 `round = "MISS"`。
7. **一致性**。`match(path)` 与 `explain(path)` 必须一致：`match(path).pattern()` 恒等于
   `explain(path).pattern()`（没有命中时前者是 `Optional.empty()`、后者的 `pattern()` 是 `null`，
   `round()` 是 `"MISS"`）；且 `explain(path).candidates()` 恒等于 `matchAll(path).size()`。
8. **`matchAll`**。返回命中该路径的**全部**模式，按第 5 条定义的裁决顺序**降序**排列
   （胜出者排第一，第一个元素就是 `match` 会选中的那一条）。顺序必须**确定** ——
   与模式的插入顺序无关，跨多次调用一致，且不得依赖 `HashMap` 的迭代顺序。
   没有命中时返回**空列表**，不是 `null`。
9. **`resolve` 按权重**。`resolve(path)` 在与 `match` **完全相同**的命中集合上，先按**权重降序**取；
   权重并列时才回落到第 5 条的三档裁决。`resolve` 与 `match` 必须共用同一份索引与同一份命中集合
   （两者的结果只可能在**排序键**上不同；`matchAll` 的顺序不受权重影响）。
10. **`resolve` 不命中**。没有命中的模式时返回 `Optional.empty()`，不抛异常。
11. **边界与非法入参**。
    - 空模式集：`match` 恒为 `Optional.empty()`，`matchAll` 恒为空列表，
       `resolve` 恒为 `Optional.empty()`，`explain` 恒为 `MISS` / `null` / `0`。
    - 空路径、只含 `/` 的路径都不得抛异常；归一化后 0 段的模式（`/`、`//`）只匹配归一化后
      0 段的路径（`/`、`//`、空串），不匹配任何更深的路径。
    - `null` 模式集合、集合里的 `null` 键、`null` 权重、`null` 路径一律抛 `IllegalArgumentException`，
      **不许**抛 `NullPointerException`。
12. **重复模式**。同一个模式字符串在入参里出现**两次**（比较的是模式字符串本身，不是归一化后的段）→
    构造即抛 `PrefixParseException`。
13. **确定性**。同样的输入（同一批模式 + 同一条路径）必须给出同一个结果：
    不依赖 JVM 默认时区 / 默认 Locale / `HashMap` 迭代顺序 / 任何随机源。
14. **并发只读**。`PrefixIndex` 的读方法（`match` / `matchAll` / `resolve` / `explain`）
    必须可被**多个线程并发调用**，结果与单线程一致；不要求额外的同步 API。

## 规模要求

`check/ScaleProbe.java` 会在 **`-Xmx96m`** 的子 JVM 里跑一遍规模场景：

- 构造 **10 万条**模式（段数 2..6、混合字面段与尾部通配段）与 **15 万次**查询
  （一半命中、一半不命中，混合 `match` / `matchAll` / `explain` / `resolve`）；
- 结果的**指纹**（把每个查询的结果拼成字符串后取 SHA-256）必须与探针自带的**参照实现**
  给出的指纹完全一致；前 500 个查询还要逐条比对完整结果；
- 另在 4 个线程上并发重跑前 2000 条查询，指纹必须与单线程一致（对应第 14 条）；
- **总耗时上限 90 秒**（按本机标定，跑题机慢按比例放宽）。

> 逐条线性扫描会在这个规模下超时；把每条模式的全部子前缀预先展开成对象，
> 又会顶到 96 MB 的堆上限。

## API

```
prefixidx.PrefixIndex
  PrefixIndex(Map<String, Integer> patterns, boolean ignoreCase)
                                   （模式不合法/重复抛 PrefixParseException；
                                     null 入参或 null 权重抛 IllegalArgumentException）
  #match(String path)              -> Optional<Match>   （不命中返回 Optional.empty()）
  #matchAll(String path)           -> List<Match>       （按三档裁决降序；不命中返回空列表）
  #resolve(String path)            -> Optional<Match>   （权重降序，并列时回落到三档）
  #explain(String path)            -> Decision

prefixidx.Match
  #pattern()                       -> String        （构造索引时传入的模式原文）
  #weight()                        -> int           （该模式的权重）
  #params()                        -> List<String>  （被 * 捕获的路径原文段；无 * 时为空列表）

prefixidx.Decision
  #round()                         -> String        （"SEGMENTS" / "LITERAL" / "LEXICO" / "MISS"）
  #pattern()                       -> String        （胜出模式的原文；MISS 时为 null）
  #candidates()                    -> int           （命中总数）

prefixidx.PrefixParseException extends IllegalArgumentException
```

## 验收

`check/Checker.java` 是固定验收程序，**不要修改**。它按 8 组共 16 个场景检查上面的契约。
**每个场景都在独立 JVM 子进程里跑，带看门狗**（默认 30 秒，`scale` 场景 120/150 秒），
超时判不通过；一个场景失败不会遮蔽其余场景（失败不早退）。

| 组 | 场景 | 对应契约 |
| --- | --- | --- |
| `parse` | `legal-patterns-accepted` | 1、2、3 |
| `parse` | `illegal-pattern-rejected` | 1、12 |
| `parse` | `normalization-and-whitespace` | 2、3、11 |
| `match` | `segments-first` | 5 |
| `match` | `tie-by-literal-chars` | 5 |
| `match` | `tie-by-lexico` | 5 |
| `match` | `no-match-empty` | 5、11 |
| `params` | `wildcard-capture-raw` | 4 |
| `all` | `matchall-descending-stable` | 8 |
| `explain` | `true-round` | 6 |
| `explain` | `consistent-with-match` | 6、7、9 |
| `resolve` | `weight-first` | 9 |
| `resolve` | `tie-falls-back` | 9 |
| `resolve` | `no-match-empty` | 10 |
| `robust` | `edges-and-null` | 11 |
| `scale` | `index-at-scale` | 规模要求 |

共 16 个场景。除 `scale` 外，判据全部确定性：没有墙钟、没有随机源，
顺序相关的断言只依赖（段数 → 字面字符数 → 模式原文字典序）这条写死的规则。
`scale` 的口径见上面「规模要求」一节，按本机标定。
