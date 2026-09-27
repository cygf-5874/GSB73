三段裁决，先后不能乱：先比段数，再比字面字符数，最后按字典序 —— 这条链要能解释给自己听。
prefixidx 是 Java 17 的前缀匹配索引，只用 JDK 标准库，构建与自检走 `scripts/*.sh`
（`scripts/check.sh` 是固定验收程序，别改）。

`src/main/java` 下四个类现在都是空壳，`src/test/java` 里 13 个用例全红。

任务：按 README「对外契约」的 14 条把它实现出来，让既有用例与固定件全过。
README「规模要求」那一节也要满足 —— 固定件会在受限堆里跑一遍规模场景。

验收：
- bash scripts/build.sh 退出码 0；
- bash scripts/check.sh 退出码 0，16 个场景全过
  （parse 3 + match 4 + params 1 + all 1 + explain 2 + resolve 3 + robust 1 + scale 1）。

约束：
1. 不改 `check/`、不改既有用例的断言；已公开的类与方法签名不变（可新增）。
2. 不引入第三方依赖，不用 Maven / Gradle，只用 JDK 标准库。
3. 结果必须与 `HashMap` 迭代顺序无关；字符串比较用 `String.compareTo`，不用 `Collator`。
4. 参数非法一律抛 `IllegalArgumentException` 或 `PrefixParseException`，不许 `NullPointerException`。
5. 不许把判据改松：`check/` 里的规模阈值与指纹口径都是固定的。
