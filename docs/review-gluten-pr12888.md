# Review：4ce20f396 — 新加的重写用例会在 ClickHouse 上被打开，注释里的机制断言又被代码否掉

## 一、改动概述

三部分：`gluten-ut/spark{34,35}` 各加一份 `testGluten("cast from timestamp II")`，从 `gluten-ut/spark40` 的同名重写版原样抄；四个模块的 `VeloxTestSettings` 在 `cast from timestamp II` 的原生 exclude 上方各加一行注释指向重写版；四个模块在 `Stop task set if FileAlreadyExistsException was thrown` 的 exclude 上方各加三行说明它为什么该被排除。6 文件 +42 行，纯新增，不动生产代码。

方向对：3.4/3.5 上那五条断言原来确实跑不到任何地方。但两处都在同一个位置出问题 —— 越过了自己验证过的边界。条目 1 是后端边界，条目 2 是证据边界。

## 二、Review 条目（按严重程度排序）

### 1. [HIGH · 覆盖范围] 重写版没在 ClickHouse settings 里配对排除，会在 CH 上打开一个从未跑过的用例（CONFIRMED）

- 锚点：`gluten-ut/spark35/src/test/scala/org/apache/gluten/utils/clickhouse/ClickHouseTestSettings.scala:384`
- 问题：新加的 `testGluten` 落在按 Spark 版本共享的 wrapper 文件 `gluten-ut/spark35/src/test/scala/org/apache/spark/sql/catalyst/expressions/GlutenCastSuite.scala` 里，而 `testGluten` 的注册不按后端分流 —— 跑哪些用例由当次加载的后端 settings 决定（`BackendTestSettings.instance` 反射加载 velox 或 clickhouse 那一份）。CH 的 spark35 settings 在 `enableSuite[GlutenCastSuite]` 块（360-390 行）里只排除了原生用例（384 行裸 `.exclude("cast from timestamp II")`），没有排除 `Gluten - ` 前缀的重写版。同一个块里 11 行之外就是这件事该有的写法：`data type casting` 是 `.exclude("data type casting")`（365）配 `.excludeCH("Gluten - data type casting")`（376）。`backends-clickhouse/pom.xml` 只有 `spark-3.3`（530）和 `spark-3.5`（536）两个 profile，所以 spark35 这份 CH settings 是活的，spark34 那份选不到。
- 失败场景：CH 的 spark35 UT job 开始跑 `Gluten - cast from timestamp II`，断言 `Double.NaN`、`1.0/0.0`、`Float.NaN`、`1.0f/0.0f` cast 到 `TimestampType` 得 null，以及 `Long.MaxValue` 原值返回。ClickHouse 在这些输入上的行为本机无法验证（没有 `libch.so`，CH 的 CI 也不在本仓 —— `clickhouse_be_trigger.yml` 只贴一条评论触发外部 CI）。任一条不一致，CH job 变红，而这个 PR 打的是 `[VL]` 标签、diff 里没有任何看起来像 CH 改动的东西。
- 处置：已修复。spark35 CH 加 `.excludeCH("Gluten - cast from timestamp II")`，spark34 CH 加 `.excludeGlutenTest("cast from timestamp II")`，各带一行说明，插在同一个 `enableSuite[GlutenCastSuite]` 块内已有的 Gluten 前缀条目旁边，用词按各自文件的既有写法。

### 2. [HIGH · 断言超出证据] 我自己新加的注释把「不走 Spark 的 FileFormatWriter」当成了「所以没有失败包装」，而 Gluten 恰好自己做了同一份包装（CONFIRMED）

- 锚点：`gluten-ut/spark34/src/test/scala/org/apache/gluten/utils/velox/VeloxTestSettings.scala:642`
- 问题：这条 exclude 上方 639-641 行的注释写的是 `because the native write does not go through the task-failure wrapping in Spark's FileFormatWriter`。前半句（不走 Spark 的 `FileFormatWriter`）成立，后半句暗示的因果不成立：`VeloxColumnarWriteFilesExec.scala:234-241` 的 catch 块是 Spark `FileFormatWriter.scala:415-420` 那两个分支的镜像 —— `case f: FileAlreadyExistsException if SQLConf.get.fastFailFileFormatOutput => throw new TaskOutputFileAlreadyExistException(f)`，以及 `case t: Throwable => SparkShimLoader.getSparkShims.throwExceptionInWrite(t, writePath, description.path)`。所以 Gluten 的 native write 是有 task 失败包装的，用例失败的真实原因（原始 Hadoop 异常从哪一段逃出去的）我没有查实。我只观测到异常类型不匹配，注释却写成了机制解释。
- 失败场景：下一个人照这条注释去找原因，会去看「Gluten 为什么不包装失败」，而代码里明明包装了；真正要查的是异常在哪一段逃出了 `VeloxColumnarWriteFilesRDD.compute` 的 try。同一条注释被复制到了四个模块，所以这个误导有四份。
- 处置：已修复（方案有调整：没有补上真实机制，而是把注释缩到只说观测到的现象）。四个模块统一改成 `// The case expects a SparkException; Gluten surfaces the raw` / `// FileAlreadyExistsException instead. Reproduced on Spark 3.4.4.`，`FileFormatWriter` 那个因果从句删掉。真实机制要查得先定位异常从哪一段逃出 try，不在本 PR 范围。

### 3. [MEDIUM · 一致性] 同一文件的 TryCast 块声称有重写版，但那个 suite 没有重写版（CONFIRMED，本 PR 不修）

- 锚点：`gluten-ut/spark34/src/test/scala/org/apache/gluten/utils/velox/VeloxTestSettings.scala:111`
- 问题：`enableSuite[GlutenTryCastSuite]` 块里 `.exclude("cast from timestamp II") // Rewrite test for Gluten not supported with ANSI mode`，四个模块都有这一行。但 `testGluten("cast from timestamp II")` 全仓只存在于 `GlutenCastSuite`（34/35）和 `GlutenCastWithAnsiOffSuite`（40/41），TryCast 那个 wrapper 里没有。这条注释是既有的，不是本次引入；但本 PR 在同一文件相隔 7 行的位置新增了一句 `Rewritten in GlutenCastSuite ...`，于是同一个用例名在同一文件里有了两句关于「重写」的说法，一句准确、一句指向不存在的东西。
- 失败场景：做和本 PR 同样的注释体检的人读到 TryCast 那句，会去找 TryCast 的重写版，找不到，然后要么以为自己漏了，要么把那条 exclude 当成有理由的而放过 —— 而它的真实原因至今未经验证。
- 处置：未修复（原因：既有问题，且真实原因未验证。凭空换一句注释只是把一个错的说法换成另一个没依据的。要修得先摘掉那条 exclude 在 3.4 上实跑一次 `GlutenTryCastSuite`，属于另一个 PR）。已写进本 PR 描述。

## 三、结论

改两处，都在条目 1 和 2。条目 1 给 spark34 和 spark35 的 CH settings 各补一行配对排除，措辞按各自文件既有写法（34 用 `excludeGlutenTest`，35 用 `excludeCH("Gluten - ...")`）。条目 2 把那三行注释缩到只说观测到的事，删掉 `FileFormatWriter` 那个因果从句，四个模块一起改。

条目 3 不改：真实原因未验证，凭空改一句注释只是把一个错的说法换成另一个没有依据的说法。要修得先把那条 exclude 摘掉在 3.4 上跑一次 `GlutenTryCastSuite`，那是另一个 PR 的活。本 PR 描述里点一句。

另有一处记录不改：`gluten-ut/spark40/src/test/scala/org/apache/gluten/utils/clickhouse/ClickHouseTestSettings.scala:383` 和 `gluten-ut/spark41/src/test/scala/org/apache/gluten/utils/clickhouse/ClickHouseTestSettings.scala:383` 同样只排除了原生用例而重写版早就在那里，但 CH 没有 4.0/4.1 的 profile，那两份 settings 任何 CI 都选不到，是惰性的。

新加的测试体里 Long.MinValue 说明重复两遍、且含一句本仓查不到证据的 `Velox computes correctly; only the collect path fails`，也不改：这两段是从 `gluten-ut/spark40/src/test/scala/org/apache/spark/sql/catalyst/expressions/GlutenCastWithAnsiOffSuite.scala:135` 起逐字抄的，只在 34/35 改会造出第四个变体，而不造第四个变体正是这个 PR 的目的之一。

## 四、验证

`test-compile` 四个 profile 全绿：`-Pspark-3.4`、`-Pspark-3.5 -Pscala-2.13`、`-Pspark-4.0 -Pscala-2.13`、`-Pspark-4.1 -Pscala-2.13`。`spotless:check` 四个模块全绿。

断言集与原生用例逐条对齐：3.4.4 和 3.5.5 的 `CastWithAnsiOffSuite.test("cast from timestamp II")` 两份字节相同（各 505-512 行），六条断言；重写版保留前五条、期望值一致，只去掉 `Long.MinValue`。3.4.4 的 test-sources jar 本机没有，从 baidu 镜像拉下来读的原文。

`Stop task set if FileAlreadyExistsException was thrown` 在 spark34 上摘掉 exclude 实跑过：71 个用例 70 过 1 失败，失败信息：

```
Expected exception org.apache.spark.SparkException to be thrown,
but org.apache.hadoop.fs.FileAlreadyExistsException was thrown
```

断言在 Spark 自己的 `InsertSuite` 里（3.4.4 的 2035 行）。这条观测支撑「该排除」，不支撑条目 2 里被删掉的那个因果。

一个自己踩的问题：后台跑构建时把 `| tail -6` 接在 `mvn` 后面，`$?` 拿到的是 `tail` 的返回码，`-q` 又把真正的错误行吞了，于是三个 `spotless:check` 报了假失败。逐个重跑（不加 `-q`、不接管道）都是 `BUILD SUCCESS`。被测命令必须是整条命令的最后一个。

## 五、Phase 3（对修复后的代码复审）

**第 1 轮：不干净。** 五个问题（配对排除是否真生效、是否过度排除、插入位置与措辞、替换文案是否站得住、有没有引入同等或更高严重度的问题）里前四个清白，第五个挑出两条 LOW：

1. 我新写的注释里 `never ran on ClickHouse` 是一句本仓证明不了的历史断言 —— 4ce20f396 这个不带 CH 排除的版本已经推上去了，如果外部 CH CI 在那之后跑过，这个用例就真跑过一次。**已改成 `not vetted on ClickHouse`**，只说能证明的。这和条目 2 是同一类毛病（断言超出证据），出现在我修条目 2 的同一批编辑里。
2. spark34 那条 CH 排除是惰性的（`backends-clickhouse/pom.xml` 只有 `spark-3.3`/`spark-3.5` 两个 profile，spark34 那份 CH settings 永远不会成为加载的 `instance`），真正起作用的只有 spark35 那条。**已写进 PR 描述**，免得 reviewer 把它读成有 CI 效果。

顺带确认到的两条事实（不是问题，但值得留在笔记里）：`testGluten` 注册的名字是 `test(GLUTEN_TEST + name)`（`GlutenTestsBaseTrait.scala:49-51`），排除按精确集合成员匹配（`BackendTestSettings.scala:175-177`）；被删掉的 `FileFormatWriter` 那句断言全仓已无残留（grep `task-failure wrapping` 只命中本评审文档）。

**第 2 轮：也不干净。** 只审了 reword 动的那两行，挑出两条：

1. `Velox-only rewrite` 这个说法自相矛盾。重写版注册在按版本共享的 wrapper 里、`testGluten` 不分后端，它并不是天生 Velox 独有 —— 恰恰是这行排除让它变成 Velox 独有的，拿结果当理由。而换成 `Velox-validated` 也不对：34/35 这两份新抄的重写版**本机没跑过**（我跑的是 `GlutenInsertSuite`，不是 `GlutenCastSuite`），4.0/4.1 那份在 CI 上绿不代表这两份绿。**最后的写法是把这半句整个删掉**：`// Gluten rewrite of the vanilla case excluded below; not vetted on ClickHouse.` 三个从句各自可证：它是 `testGluten` 注册的重写版、原生用例确实排在同一块下方、CH 上确实没有验证过。
2. `docs/review-gluten-pr12888.files/fix.patch` 是第 1 轮之前存的，已经与工作树不符（还带着被撤回的 `never ran` 措辞和过期的 blob hash）。**已重新生成。**

**第 3 轮：干净。** 三个从句逐条可证（重写版是 `testGluten` 注册的；原生 exclude 在同一块下方 —— spark34 注释 554 行、原生 587 行，块 546-596；spark35 注释 377 行、原生 386 行，块 360-392）；被撤回的两种措辞只活在本文档第五节里作为改动记录，代码和 `fix.patch` 里都没有残留，`fix.patch` 与 `git diff` 逐字节相同；`diff.patch` 里还有 8 处旧的 `FileFormatWriter` 注释是对的 —— 它记录的是 4ce20f396 那个「改之前」的状态，正是条目 2 引用的问题本身。两条 finding 都真的解决了：条目 1 的活防线是 spark35 那条精确匹配（唯一既能构建 CH 又有重写版的模块），条目 2 四个模块现在只剩观测句。无新问题。

**连续无新问题轮数：1/1，达到本次约定的 N=1。**
