## JarLibsConsolidator

一键收集项目中的 JAR 和独立 `.class` 文件，统一输出到 `all-in-one` 目录，并自动添加为项目库，挂载到所有模块，让 IDEA 能索引和反编译这些类。

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.25-7F52FF?logo=kotlin) ![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A?logo=gradle) ![IntelliJ%20Platform](https://img.shields.io/badge/IntelliJ%20Platform-223--262.*-000?logo=intellijidea) ![JDK](https://img.shields.io/badge/JDK-17-5382A1)

### 📺 演示视频

<div align="center">
  <a href="https://www.youtube.com/watch?v=vE4H3-4ami0">
    <img src="https://img.youtube.com/vi/vE4H3-4ami0/maxresdefault.jpg" alt="JarLibsConsolidator Plugin Demo" width="560" height="315" style="border-radius: 8px; box-shadow: 0 4px 8px rgba(0,0,0,0.1);">
  </a>
  <br>
  <p><em>🎬 点击播放按钮观看完整演示 | <a href="https://www.youtube.com/watch?v=vE4H3-4ami0">在新窗口打开</a></em></p>
</div>

### 背景与动机

在进行代码审计工作时，我们经常需要分析已编译打包的 JAR 源码项目。然而，IntelliJ IDEA 往往无法自动识别所有的依赖关系，特别是在分析国产开源项目时更为明显。过去我一直使用以下命令来手动收集依赖：

```bash
cp `find ./ -name "*.jar"` ./all-in-one
```

但每次操作都颇为繁琐。

为了提高工作效率，我开发了这个插件，让依赖收集变得简单高效——只需右键点击，即可完成所有 JAR 文件的收集、整理和项目库配置。

### 特性
- **一键操作**：右键项目 → 选择"**一键添加依赖**"。
- **智能扫描**：递归扫描 `.jar`，跳过常见目录（如 `node_modules`、`target`、`build`、`.gradle`、`.mvn` 等）。
- **class 文件支持**：递归收集项目中的 `.class`，包含 `target/classes`、`build/classes`、`out/production`、多模块和隐藏目录中的编译产物；只有 class、没有 JAR 的项目也可使用。
- **恢复包目录**：读取字节码中的类名，将不冲突的类统一复制到 `all-in-one/classes/`，支持内部类、默认包，以及目录被打平或文件被重命名的 class 文件。
- **重名处理**：自动对同名 jar 加后缀去重（如 `x.jar` → `x_2.jar`）。
- **同名 class**：完整类名相同且字节内容相同的文件只保留一份；只有内容不同的同名类才放进 `all-in-one/classes-conflicts/`，按来源目录命名并注册为独立库根。
- **统一管理**：复制到 `all-in-one/`，创建项目级库 `all-in-one` 并添加至所有模块依赖。
- **版本范围**：插件声明支持 build `223`–`262.*`，包含 `IU-262.10315.125`。

### 前置依赖

安装 JDK 17。首次运行 `./gradlew` 时会自动下载 Gradle 8.11.1 并校验 SHA-256，无需手动放置分发包；构建时需能访问 Gradle、Maven 和 JetBrains 下载服务。

### 快速上手
1) **插件已上传至JetBrains Marketplace，可直接在IDEA插件市场搜索JarLibsConsolidator进行安装**

2) 本地运行（开发/体验）

```bash
./gradlew runIde
```

3) 打包安装（生成可安装的 zip）

```bash
./gradlew buildPlugin
# 产物：build/distributions/JarLibsConsolidator-<version>.zip
# IDE 中安装：Settings/Preferences → Plugins → ⚙ → Install Plugin from Disk…
```
也可以下载本仓库 Releases 中的 `JarLibsConsolidator-<version>.zip`，在 IDEA 中选择从磁盘安装，无需解压。

### 手动构建并发布 GitHub Release

1. 将工作流合并到默认分支 `main`。如果 fork 仓库的 Actions 未启用，先在 **Actions** 页面启用。
2. 打开 **Actions → Build and Release → Run workflow**，选择要发布的分支。
3. 在 `tag` 输入新版本号，例如 `v1.2` 或 `v1.2.3`，再点击 **Run workflow**。
4. 工作流会执行检查和构建，成功后为本次运行的提交创建标签、发布 Release，并上传可安装的插件 ZIP。

标签去掉 `v` 后会作为插件版本和 ZIP 文件名中的版本，例如 `v1.2` 对应 `JarLibsConsolidator-1.2.zip`。已有标签会被拒绝，不会覆盖既有版本。如果创建标签后 Release 发布失败，需先检查并处理残留标签，再重新运行。

工作流使用仓库自带的 `GITHUB_TOKEN`，仅发布步骤拥有 `contents: write` 权限，无需配置个人令牌或 Marketplace 密钥。PR 和 `main` 分支推送只执行构建检查并保存 Actions artifact，只有手动运行才发布 Release。

本地指定同样的版本：

```bash
./gradlew clean check buildPlugin verifyPluginStructure -PpluginVersion=1.2
```

若旧版 `1.1` 提示仅支持 `253.*`，请从新的 Release 下载并安装 ZIP；修改仓库配置不会改变已经安装的旧包。

### 使用
- 在 Project 视图中右键项目根目录或任意目录 → 选择“**一键添加依赖**”。
- 扫描范围为整个项目根目录，JAR 和独立 class 文件会一起加入库。
- 若 `all-in-one/` 已存在，会提示是否删除并重建。
- 完成后可在项目结构的 `Libraries` 看到 `all-in-one`，并已挂载至所有模块。
- 等待 IDEA 索引完成后，即可识别这些类并查看反编译结果。class 文件更新后，需重新运行一次以刷新副本。

例如 `target/classes/com/example/App.class` 会按字节码中的名称复制为 `all-in-one/classes/com/example/App.class`，库的 Classes 根目录指向 `classes`，使 IDEA 能识别 `com.example.App`。不同文件夹中的其他不冲突类也合并到这个目录。

如果 `module-a/target/classes` 和 `module-b/target/classes` 都有 `com.example.App`，但字节内容不同，两个版本分别放在 `classes-conflicts/module-a__target__classes/com/example/App.class` 和 `classes-conflicts/module-b__target__classes/com/example/App.class`，不在公共 `classes/` 中任意保留一个版本。相同内容的副本不会增加额外库根。

从 1.5.4 起，复制时还会处理磁盘上实际已存在的目标（包括文件系统不区分大小写造成的冲突）：字节完全相同则复用；内容不同则保留既有文件，将新文件放进 `classes-conflicts/` 下按来源命名的库根，并同时注册两处库根。新文件来源记入 `sources.tsv`，日志记录源文件、既有目标及实际保存路径。不覆盖、不删除既有文件；目录、符号链接或权限错误仍会报告异常。完整类名分组严格区分大小写。

冲突根目录用项目内相对来源路径命名，路径分隔符替换为 `__`；同一文件夹内的不同版本会附加原始文件名。目录名重名或过长时附加来源路径摘要，`classes-conflicts/sources.tsv` 记录所保留版本的完整原始相对路径。冲突根放在公共 `classes/` 之外，避免来源目录被误识别为包名。

class 扫描跳过版本控制目录（`.git`、`.hg`、`.svn`）和已有 `all-in-one` 输出，不跟随符号链接。无法读取或头部无效的 class 文件会被跳过，完成提示中会显示跳过数量。

### 工作原理（简述）
1. 遍历工程目录收集 JAR 和独立 class 文件。
2. JAR 复制到 `all-in-one/`；class 按完整类名分组并比较字节内容，不冲突或内容相同的类统一整理到 `all-in-one/classes/`，内容不同的同名类放入按来源命名的冲突根目录。
3. 创建/刷新项目库 `all-in-one`，将 JAR 根和 class 包目录的根分别注册为 `CLASSES`，并添加到所有模块依赖。

### 导出 CLASS / 反编译导出 JAVA（ZIP）

先在项目视图右键执行 **一键添加依赖**，将要分析的 JAR/class 登记到 IDEA 依赖库；然后选择 **一键导出 CLASS（ZIP）** 或 **一键反编译并导出 JAVA（ZIP）**，输入白名单、黑名单，再选择 ZIP 保存位置。

导出范围仅包含 IDEA 已登记的项目依赖库和模块依赖库（含 `all-in-one`、已配置的项目外 Maven/Gradle 库，不含 JDK）。不会再次扫描整个项目，也不会自动纳入未登记的散落 class/JAR 或模块编译输出。库中的 JAR、多版本类及嵌套依赖 JAR 仍会读取；class 目录根按登记范围扫描。没有依赖库时会提示先添加依赖。不会跟随文件系统符号链接。

JAVA 导出调用 **当前 IDEA 自带的 Java Bytecode Decompiler（Fernflower）引擎**，不额外下载或内置第三方反编译器。需要启用 IDEA 的 Java Bytecode Decompiler 插件；关闭它时，JAVA 导出菜单隐藏，CLASS 导出仍可用。反编译会生成方法体，不使用仅含签名的 class 查看器存根。源码不保证与原始源码完全一致或能直接构建；内部类单独导出，以严格遵守类名过滤规则。

从 1.5.5 起，单个类首次反编译失败或未生成源码时，会用同一 IDEA 引擎关闭泛型签名重建后重试一次。这用于处理内部类隐藏构造参数与泛型签名不一致等兼容问题；普通成功类不增加额外反编译。恢复结果保留方法体，但 `List<String>` 等泛型信息可能退化为原始类型，`export-report.txt` 会明确记录降级及首次失败原因，结果不进入无警告缓存。两次均失败仍列入失败报告，完整原因写入日志；取消操作不触发重试。重试仍只读取命中的单个类，不读取或导出被过滤的外部类/内部类。

两种导出共用以下规则：多条规则用换行、英文逗号或中文逗号分隔，区分大小写；白名单为空表示全部，非空时命中任意一条即可；**黑名单优先，命中即排除**。

| 规则 | 含义 | 示例 |
| --- | --- | --- |
| `Service` | 包名任意一段或简单类名包含关键字 | `com.api.UserService` |
| `com.example` | 完整包名精确匹配 | `com.example.User`，不含子包 |
| `com.example.*` | 指定包及子包 | `com.example.User`、`com.example.api.User` |
| `*example*` | 任意包名段包含 `example` | `org.myexampletools.api.User` |
| `*example` | 任意包名段以 `example` 开头 | `org.exampletools.api.User` |
| `example*` | 任意包名段以 `example` 结尾 | `org.myexample.api.User` |

单段规则中，前置/后置 `*` 的方向按本项目约定执行，与通常的 glob 方向相反。其他组合中 `*` 表示任意字符，`?` 表示单个字符；带点的包路径规则匹配完整包名。匹配依据是字节码里的真实包名，而不是 JAR 条目或磁盘文件夹名。

普通条目按包路径导出，例如 `com/example/User.class` 或 `com/example/User.java`。相同完整类名和相同字节内容去重；同名但内容不同的版本全部保留在 `classes-conflicts/来源名称--字节摘要/` 下，不会互相覆盖。JAVA 导出也按原始字节码判断版本，反编译失败的版本会列入报告。

ZIP 内包含 `export-report.txt`（扫描、过滤、去重、导出计数和失败/警告列表）以及 `export-sources.tsv`（导出文件对应的来源）。导出可取消；先完成临时 ZIP，再替换目标文件，取消或打包失败不会覆盖已有文件。为限制异常输入的资源消耗，单个 class 上限 64 MiB，嵌套 JAR 上限 512 MiB、最多递归 8 层，超出部分会报告为失败/警告。

JAVA 导出完成后单独提示“反编译失败：N 个”。这里统计最终没有生成源码的类版本；重试成功、普通反编译警告和扫描读取失败不混入该计数。N 大于 0 时，同一个 ZIP 额外包含 `decompilation-failures.csv`（UTF-8 BOM），列出完整类名、来源文件/JAR 条目、字节码 SHA-256、计划导出路径、异常类型和原因链；同名不同字节码分别记录。N 为 0 不生成该 CSV，CLASS 导出也不生成。CSV 支持逗号、双引号和多行异常；公式样式的字段会加前导单引号，完整原始消息仍保存在文本报告中。

扫描阶段按依赖库根去重；只处理 class/JAR 候选文件。命中的 class 只打开一次，复用已读取的字节码头，不重复解压；快照平铺到临时目录，避免逐类创建包目录。包级过滤判断在本次导出内复用（最多 2048 个包），类名关键字仍逐类检查。扫描进度最多每 100 ms 更新一次，显示计数和当前路径，不再固定显示 10%。过滤仍以字节码真实包名为准，不用 JAR 条目名猜测包名。

JAVA 导出使用有限并行：根据 CPU 数量保留一个逻辑核心，最多 4 个工作线程（单/双核至少使用 1 个线程），每个任务保留独立反编译器上下文。只提前处理与线程数相同数量的类，ZIP 按固定顺序写入，避免无界排队和同名版本互相覆盖。

同一次 IDE 会话内，成功且无警告的反编译结果保存在有界 LRU 内存缓存中：最多 4096 项、约 32 MiB 的输入/源码数据预算，重启或插件卸载后失效。缓存按完整类名和 SHA-256 定位，并再次比较原始字节内容；字节码变化会重新反编译。过滤在缓存查询前执行，失败/警告结果不会缓存。再次导出仍需扫描、校验字节码和打包，不会直接复用旧 ZIP。

性能对比可运行 `./gradlew test --tests '*ExportBenchmarkTest' -PbenchmarkExports=true`。该测试保留优化前实现，在同一 JVM 中对真实 JUnit JAR 和嵌套 Fat JAR 进行预热和交替三轮测试，记录旧版串行、新版首次导出和缓存命中后的中位耗时，并检查所有源码及来源映射一致。扫描对比可运行 `./gradlew test --tests '*ScanBenchmarkTest' -PbenchmarkExports=true`，以冻结的 1.5.1 实现对比 4000 类普通/嵌套 JAR 的全选和 1% 命中过滤，项目另有 12000 个无关文件。耗时不作为测试通过阈值；PR 构建会自动运行两项对比。

### 兼容性与要求
- **IDE 声明范围**：build `223`–`262.*`（包含 `IU-262.10315.125`），保留原最低版本 `223`。
- **编译基线**：IntelliJ IDEA Ultimate 2024.1.6；扩大版本范围解决安装时的版本上限拦截，不代表已经在所有 IDE 版本上完成运行验证。
- **构建 JDK / 字节码目标**：17；运行 IDEA 使用其自带的 JetBrains Runtime。
- **运行时插件**：`com.intellij.java`（已通过平台打包）

### 常见问答
- **会修改源码吗？** 不会，仅复制 JAR/class 文件并写入项目库配置。
- **扫描很慢怎么办？** class 扫描会覆盖整个项目，包括编译输出；可取消任务，并整理不需要的旧编译产物后重试。
- **同一个类有多个版本怎么办？** 相同字节内容去重，内容不同则保留各版本并标明来源，但 IDEA 仍按模块依赖/库根顺序解析同名类。请移除不需要的旧版本以避免歧义。
- **class 缺少依赖怎么办？** 独立 class 文件本身不包含所有外部依赖；请把相应依赖 JAR 一并放入项目后再次收集。

### 开发
```bash
# 单元测试（使用 javac 生成真实字节码并验证类路径加载）
./gradlew test

# 验证插件
./gradlew verifyPlugin

# 本地运行沙箱 IDE
./gradlew runIde

# 构建可分发包
./gradlew buildPlugin
```


### 异常日志与 DEBUG 排查

从 1.5.3 起，操作失败弹窗会显示失败阶段、异常类型及原因链；复制失败还会包含源文件与目标文件路径。原始异常堆栈写入 IDEA 的 `idea.log`，无需开启 DEBUG。日志中的操作开始、扫描完成和成功结束记录可用于判断卡在哪个阶段及总耗时。

1. 打开 **Help → Show Log in Explorer**（macOS 为 Show Log in Finder），查看 `idea.log`。
2. 如需逐文件诊断，打开 **Help → Diagnostic Tools → Debug Log Settings**，添加一行：
   ```text
   #org.le1a.jarlibsconsolidator
   ```
3. 重新执行出错操作，搜索 `org.le1a.jarlibsconsolidator`、`复制 class 失败` 或 `Export failed`，提供该时间附近包含 `Caused by` 的完整堆栈。
4. 排查结束后删除该 DEBUG 配置，避免大量文件映射日志影响速度和占用磁盘。

DEBUG 记录扫描跳过的路径/异常、源文件到目标路径的映射、库根、导出过滤条件与 ZIP 条目，不记录字节码或反编译源码内容。分享日志前可遮盖敏感路径。导出单项失败的异常类型仍全部保存在 ZIP 内 `export-report.txt`；为限制损坏归档产生的大量日志，每次导出默认只记录前 20 个单项失败的完整堆栈，其余堆栈在 DEBUG 下记录。取消操作不会当作失败报错。
