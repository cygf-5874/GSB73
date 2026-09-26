# prefixidx

一个**最长前缀匹配索引**（Java 17，只用 JDK 标准库，无第三方依赖）。
它把一批「路由模式」编译成索引，对给定路径返回**胜出**的那条模式。胜出规则写死在下面：

1. **段数多的赢**（`/api/v1` 赢 `/api`）；
2. 段数一样，比**字面段**的字符总数（`/api/v1` 赢 `/api/*`）；
3. 还是平局，取模式字符串**字典序最小**者。

模式支持单段通配 `/api/*`。

```java
PrefixIndex idx = new PrefixIndex(List.of("/api", "/api/v1", "/api/*"), false);

idx.match("/api/v1/users").get().pattern();   // "/api/v1"
idx.match("/api/orders").get().pattern();     // "/api/*"
idx.match("/api/orders").get().params();      // ["orders"]
idx.match("/healthz").isPresent();            // false
idx.matchAll("/api/v1/users").size();         // 3（按上面的优先级从高到低排列）
```

## 目录

```
src/main/java/prefixidx/PrefixIndex.java          索引（当前为空壳）
src/main/java/prefixidx/Match.java                一次命中：模式 + 捕获段（当前为空壳）
src/main/java/prefixidx/PrefixParseException.java 模式不合法时抛出的异常
src/test/java/prefixidx/PrefixIndexTest.java      既有用例（13 个，手写 runner，无第三方框架）
scripts/build.sh                                  编译 src/ 到 out/
scripts/test.sh                                   跑既有用例
scripts/check.sh                                  固定验收入口（勿改）
check/Checker.java                                固定验收程序（勿改）
```

## 语言版本前提

- Java 17（`javac` / `java`）。
- **只用 JDK 标准库**：不引入 Maven / Gradle，不引入任何第三方依赖。
- 构建产物放在 `out/`，已在 `.gitignore` 中忽略。

## 怎么跑

```bash
bash scripts/build.sh      # 编译到 out/
bash scripts/test.sh       # 既有用例
bash scripts/check.sh      # 固定验收；支持 -list 与 --only <组名>
bash scripts/check.sh -list              # 列出全部验收场景
bash scripts/check.sh --only match       # 只跑一组
```

起点状态：`src/main/java` 下的方法是空壳，调用即抛 `UnsupportedOperationException`；
`bash scripts/test.sh` 打印 13 个 `FAIL`（**能编译，没有语法错**）；
`bash scripts/check.sh` 打印 `结果：通过 0/11` 并以退出码 1 结束。

> `check/` 下的 `Checker.java` 是**固定验收程序，勿改**；`src/test/java` 里既有用例的断言、
> `PrefixParseException` 的类名与继承关系，同样都是契约的一部分，勿改。
> 公开的**类型名、方法名、签名与异常类型**已经是最终形态（可以新增内部方法与私有成员），
> 不要改动下面 `API` 一节列出的这些。

## 对外契约

下面 11 条是 `prefixidx` 的**对外契约**，它们是本题验收点的唯一出处。
**它们是契约，不是「当前行为」的转述**；实现方式不限，但必须让这 11 条同时成立。

1. **模式语法**。模式由 `/` 分隔成段（先按第 7 条归一化）。
   `*` 必须是**独占一段**的**通配段**，且只能出现在**最后一段**：`/api/*` 合法；
   `/api/*/x`、`*/x`（`*` 不在最后一段）与 `/a*b`、`/*x`（`*` 没有独占一段）都不合法，
   构造即抛 `PrefixParseException`。某一段含空白字符（空格、制表符等）的模式同样不合法，
   构造即抛 `PrefixParseException`。段里除 `*` 以外的一切字符都是**字面**字符，没有别的元字符；
   只含 `*` 的模式（`*`）合法（它吃掉路径的第 1 段，见第 3 条）。
2. **大小写**。构造参数 `ignoreCase`：为 `true` 时段的比较按 `String.equalsIgnoreCase` 的语义
   （**与默认 Locale 无关**）；为 `false` 时严格区分大小写。
3. **`match` 最长优先**。`match(path)` 返回命中该路径的模式里**段数最多**的那一条；
   段数指模式归一化后的段数（含通配段）。匹配是**按段的前缀匹配**：
   模式与路径逐段对上即可，路径还可以有更深的段。
   `*` **恰好吃掉一个段**，不匹配 0 段 —— `/api/*` 不匹配 `/api`（只有 1 段）；
   吃掉之后路径仍然可以有更深的段 —— `/api/*` 匹配 `/api/x/y` 并捕获 `x`，
   `*` 匹配 `/a/b` 并捕获 `a`。
4. **字面字符数次级裁决**。段数并列时，比**字面段（非 `*` 段）的字符总数**（各段 `String.length()` 之和），
   多者优先：`/api/v1`（5 个字符）赢 `/api/*`（3 个字符）。
5. **字典序终级裁决**。段数与字面字符数都并列时，取**模式字符串**（构造时传入的原文，不做归一化改写）
   按 `String.compareTo` 字典序**最小**者。这条既是「任意输入下结果唯一确定」的保证，
   也决定了 `/api` 与 `//api`、`/api/` 与 `/api` 这类归一化后等价的模式之间谁胜出。
6. **不命中**。`match` 找不到任何命中的模式时返回 `Optional.empty()`，不抛异常。
7. **归一化**（模式与路径都适用）。把串按 `/` 切段并**丢弃所有空段**，再按 `/` 前缀重新拼接。
   由此：多重斜杠折叠（`//api//v1//` ≡ `/api/v1`）、首尾斜杠无意义（`/api/` ≡ `/api`、`api` ≡ `/api`）、
   空串与 `/`、`//` 都归一化为 **0 段**。
8. **`params()`**。返回被 `*` 捕获的那一段，**原样**返回（不做 URL 解码、不改大小写、不做归一化改写）；
   模式里没有 `*` 时返回**空列表**。返回的列表不可修改。
9. **`matchAll`**。返回命中该路径的**全部**模式（包含 `match` 的胜者），按第 3、4、5 条定义的
   优先级**从高到低**排列：第一个元素就是 `match` 会选中的那一条。顺序**完全确定** ——
   与模式的插入顺序无关，也不得依赖 `HashMap` 之类的迭代顺序。没有命中时返回**空列表**，不是 `null`。
10. **边界与非法入参**。
    - 空模式集（`List.of()`）：`match` 恒为 `Optional.empty()`，`matchAll` 恒为空列表。
    - 空路径、只含 `/` 的路径都不得抛异常；归一化后 0 段的模式（`/`、`//`）只匹配归一化后
      0 段的路径（`/`、`//`、空串），不匹配任何更深的路径。
    - `null` 模式集合、集合里的 `null` 元素、`null` 路径一律抛 `IllegalArgumentException`，
      **不许**抛 `NullPointerException`。
11. **确定性**。同样的输入（同一批模式 + 同一条路径）必须给出同一个结果：
    不依赖 JVM 默认时区 / 默认 Locale / `HashMap` 迭代顺序 / 任何随机源。

## API

```
prefixidx.PrefixIndex
  PrefixIndex(List<String> patterns, boolean ignoreCase)
                                   （模式不合法抛 PrefixParseException；null 入参抛 IllegalArgumentException）
  #match(String path)              -> Optional<Match>   （不命中返回 Optional.empty()）
  #matchAll(String path)           -> List<Match>       （按优先级从高到低；不命中返回空列表）

prefixidx.Match
  #pattern()                       -> String        （构造时传入的原始模式字符串）
  #params()                        -> List<String>  （被 * 捕获的段；无 * 时为空列表）

prefixidx.PrefixParseException extends IllegalArgumentException
```

## 验收

`check/Checker.java` 是固定验收程序，**不要修改**。它按 5 组共 11 个场景检查上面的契约。
**每个场景都在独立 JVM 子进程里跑，带 30 秒看门狗**（超时判不通过），
一个场景失败不会遮蔽其余场景（失败不早退）。

| 组 | 场景 | 对应契约 |
| --- | --- | --- |
| `parse` | `legal-patterns-accepted` | 1、2、7 |
| `parse` | `illegal-pattern-rejected` | 1、7 |
| `parse` | `normalization-effective` | 2、7、10 |
| `match` | `longest-segment-count-wins` | 3 |
| `match` | `tie-by-literal-chars` | 4 |
| `match` | `tie-by-lexicographic` | 5 |
| `match` | `no-match-returns-empty` | 6、10 |
| `params` | `wildcard-capture` | 3、8 |
| `params` | `capture-in-deeper-path` | 3、8 |
| `all` | `matchall-sorted-and-stable` | 9 |
| `robust` | `edges-and-null` | 6、10 |

共 11 个场景。判据全部确定性：没有墙钟、没有随机源，
顺序相关的断言只依赖（段数 → 字面字符数 → 字符串字典序）这条写死的规则。
