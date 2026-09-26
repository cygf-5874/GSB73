路由表里最长的那条前缀要赢，一样长的时候比字面字符数，再一样就按字典序定先后 ——
这条规则要落到实现里。prefixidx 是 Java 17 的前缀匹配索引，只用 JDK，
构建与自检走 `scripts/*.sh`（`scripts/check.sh` 是固定验收程序，别改）。

`src/main/java` 下三个类现在都是空壳，`src/test/java` 里 13 个用例全红。

任务：按 README「对外契约」的 11 条把它实现出来，让既有用例与固定件全过。

验收：
- bash scripts/build.sh 退出码 0；
- bash scripts/check.sh 退出码 0，11 个场景全过（parse 3 + match 4 + params 2 + all 1 + robust 1）。

约束：
1. 不改 `check/`、不改既有用例的断言；已公开的类与方法签名不变（可新增）。
2. 不引入第三方依赖，不用 Maven / Gradle，只用 JDK 标准库。
3. 结果必须与 `HashMap` 迭代顺序无关。
4. 参数非法一律抛 `IllegalArgumentException`，不许 `NullPointerException`。
