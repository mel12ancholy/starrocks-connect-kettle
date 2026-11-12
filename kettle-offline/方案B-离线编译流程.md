# 方案 B：离线编译 StarRocks Kettle Connector 完整流程

> 适用场景：拿不到 Pentaho 仓库账号（方案 A 不可行），但手上有已安装的 Kettle（PDI）。
> 核心思路：**不去重建 Pentaho 的仓库，而是把本机 Kettle 的 `lib` 目录灌进 Maven 本地仓库，让项目从本地仓库取依赖。**

---

## 一、为什么只能走这条路

我穷举过所有可能：

| 来源 | 结果 |
|---|---|
| `repo.orl.eng.hitachivantara.com`（项目原本声明的地址） | ❌ 域名已注销，全球不解析 |
| `repo.pentaho.com`（官方迁移后的新地址） | ❌ 15 个仓库名全部 401，需要 Basic 登录 |
| Maven 中央仓库 | ❌ `pentaho-kettle` 组完全不存在 |
| 阿里云 / 华为云 / 腾讯云镜像 | ❌ 都是中央仓库的代理，同样没有 |
| JBoss / Atlassian / Mulesoft / RedHat 等公共聚合仓库 | ❌ 都没有代理 Pentaho |
| SourceForge（PDI 发行包老巢） | ❌ 发行包已下架，只剩一个 PDF |

**结论：Pentaho 构件在公网上已经不存在任何匿名可读的来源。** 唯一办法就是从本机已装好的 Kettle 里取。

---

## 二、Maven 本地仓库在哪（**每台机器都可能不一样，必须先确认**）

本地仓库路径不是固定的 `~/.m2/repository`，它会被 Maven 的 `settings.xml` 覆盖。**实测两台机器就不一样：**

| 机器 | Maven | 本地仓库                                                        |
|---|---|-------------------------------------------------------------|
| 你的本地电脑 | `D:\starrocks\tools\apache-maven-3.9.9`（无 settings.xml） | `C:\Users\<用户名>\.m2\repository`                             |
| 你的公司电脑 | `D:\Maven\apache-maven-3.9.11` | **`D:\Maven\apache-maven-3.9.11\repo`** ← 被 settings.xml 改过 |

所以**在要执行编译的那台机器上**先跑这一条，记下输出：

```bash
mvn help:evaluate -Dexpression=settings.localRepository
```

> ⚠️ 不要加 `-o`（离线模式）。离线模式下 Maven 连 `maven-dependency-plugin` 自身都加载不了
> （它要先下载 `doxia-sink-api`、`commons-text` 等插件依赖），会报
> `Cannot access xxx in offline mode and the artifact ... has not been downloaded from it before`。
> 这条报错跟你的项目无关，纯粹是 `-o` 引起的。

好消息：**灌仓库工具已经会自动调用 mvn 探测这个路径**，你不用手工填。如果你手工传了一个和实际不一致的路径，工具会打印醒目告警：

```
############################################################
# 警告：你指定的本地仓库与 Maven 实际使用的仓库不一致！
#   你指定的   : D:\Maven\apache-maven-3.9.11\repo
#   Maven 实际 : C:\Users\<用户名>\.m2\repository
############################################################
```

灌入完成后，关键文件会出现在（以公司电脑为例）：

```
D:\Maven\apache-maven-3.9.11\repo\
├── org\pentaho\di\plugins\pdi-plugins\9.5.0.0-240\
│       └── pdi-plugins-9.5.0.0-240.pom                    ← 占位父 POM
├── pentaho-kettle\
│   ├── pdi-kettle-all\9.5.0.0-240\
│   │       └── pdi-kettle-all-9.5.0.0-240.pom             ← 聚合依赖（项目只需引用它）
│   ├── kettle-core\9.5.0.0-240\kettle-core-9.5.0.0-240.jar
│   ├── kettle-engine\9.5.0.0-240\kettle-engine-9.5.0.0-240.jar
│   └── kettle-ui-swt\9.5.0.0-240\kettle-ui-swt-9.5.0.0-240.jar
├── pentaho\pentaho-metastore-api\...\
└── ...（lib 目录里的其余 jar，按各自真实坐标落位）
```

验证是否灌好：

```bash
# 方式一：直接看目录（最直观，不联网）
dir "D:\Maven\apache-maven-3.9.11\repo\pentaho-kettle"

# 方式二：让 Maven 验证能否解析（注意：不要加 -o）
mvn dependency:get -Dartifact=pentaho-kettle:kettle-core:9.5.0.0-240
```

---

## 二·五、在**哪台机器**上编译？

两种都行，选一个：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 在公司电脑编译**（推荐） | 公司电脑上有 Kettle，直接把 `kettle-src` 项目拷过去，跑第 2、3 步 | 不用拷 200MB 的 lib | 要保证公司电脑能访问 Maven 中央仓库 |
| **B. 在本地电脑编译** | 把公司电脑的 `lib` 目录拷回来，跑第 1、2、3 步 | 编译环境干净可控 | 要拷一次 lib 目录 |

> 注意：**灌仓库和编译必须在同一台机器上做**。在公司电脑灌的仓库，本地电脑上的 Maven 是看不到的。

---

## 三、操作流程（4 步）

### 第 0 步：确认公司电脑上 Kettle 的版本

去公司电脑上找到 Kettle 安装目录，进入 `data-integration\lib`，看 `kettle-core-*.jar` 的文件名：

```
kettle-core-8.3.0.0-371.jar   ← 你公司电脑实测就是这个版本，本项目已按它对齐
kettle-core-9.5.0.0-240.jar   ← 上游默认值；换版本要同步改 pom（第 2 步会讲）
```

> 上游默认锁定 **9.5.0.0-240**，但**本项目已改为 `8.3.0.0-371`**（匹配你公司机器）。
> 版本不一致也能编，但必须同步改 `pom.xml` 的 `<pdi.version>` 和 `<parent><version>`，见第 2 步。
> 另外编译目标会跟着版本自动变：PDI 8.x → Java 8 字节码，PDI 9.x → Java 11。

### 第 1 步：把 lib 目录拷到本地

在公司电脑上把整个 `data-integration\lib` 目录拷到本地，例如：

```
D:\starrocks\pdi-lib\lib\        ← 里面是 kettle-core-*.jar 等一堆 jar
```

**拷整个 lib 目录，不要挑。** 原因：`kettle-core` 编译时还需要它的一批传递依赖（`pentaho-metastore-api`、`commons-lang` 等），少一个就会报"程序包不存在"。整个 lib 大约 200 个 jar、几百 MB，U 盘拷一下的事。

（如果嫌大，也可以只拷 `kettle-core*.jar`、`kettle-engine*.jar`、`kettle-ui-swt*.jar`、`pentaho-metastore*.jar`，然后按第五节的办法迭代补 jar。）

### 第 2 步：灌入 Maven 本地仓库

在本地执行（Git Bash）：

```bash
cd /d/starrocks/kettle-offline
bash 1-setup-repo.sh /d/starrocks/pdi-lib
```

如果你的 Kettle 版本不是 9.5.0.0-240，带上版本号：

```bash
bash 1-setup-repo.sh /d/starrocks/pdi-lib 9.4.0.1-467
```

不想用脚本的话，直接敲：

```bash
export JAVA_HOME="D:\exercise\jdk-17.0.10"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 PdiOfflineSetup.java /d/starrocks/pdi-lib
```

> 路径写法随意：`/d/starrocks/pdi-lib`（Git Bash 风格）和 `D:\starrocks\pdi-lib`（Windows 风格）
> 工具都会自动识别。省略第 2、3 个参数就使用默认值（`C:\Users\<用户名>\.m2\repository` 和 `9.5.0.0-240`）。

**这一步做了什么：**

1. 扫描 `pdi-lib/lib/*.jar`；
2. 从每个 jar 内部的 `META-INF/maven/<groupId>/<artifactId>/pom.properties` 读出**真实坐标**（不是靠猜文件名），按 Maven 仓库目录规范写入 `C:\Users\<用户名>\.m2\repository`；
3. 生成聚合依赖 `pentaho-kettle:pdi-kettle-all`，把 lib 里所有 jar 列进去；
4. 生成占位父 POM `org.pentaho.di.plugins:pdi-plugins`（替代已下线的官方父 POM，并把编译级别锁死在 Java 11）；
5. 自动跳过 `-tests.jar` / `-sources.jar` / `-javadoc.jar`；
6. 写一份 `pdi-offline-report.txt`，报告检测到的 Kettle 版本和警告。

> 工具是**幂等**的，重复执行只会覆盖，不会出错。

**如果报告提示版本不一致**，去改 `kettle-src/pom.xml` 两处，然后重跑第 2 步：

```diff
  <properties>
      <org.eclipse.swt.version>4.3</org.eclipse.swt.version>
      <jface.version>3.3.0-I20070606-0010</jface.version>
-     <pdi.version>9.5.0.0-240</pdi.version>
+     <pdi.version>9.4.0.1-467</pdi.version>
  </properties>

  <parent>
      <groupId>org.pentaho.di.plugins</groupId>
      <artifactId>pdi-plugins</artifactId>
-     <version>9.5.0.0-240</version>
+     <version>9.4.0.1-467</version>
  </parent>
```

### 第 3 步：编译打包

```bash
cd /d/starrocks/kettle-offline
bash 2-build.sh
```

或者直接敲命令：

```bash
cd /d/starrocks/kettle-src
export JAVA_HOME="D:\exercise\jdk-17.0.10"
/d/starrocks/tools/apache-maven-3.9.9/bin/mvn -B clean package -Dmaven.test.skip=true
```

> **`-Dmaven.test.skip=true` 必须加。** 测试代码用到了 `kettle-core` / `kettle-engine` 的 `tests` 分类器构件（`RestorePDIEngineEnvironment`、`LoadSaveTester` 等），这些是 Maven 仓库里的测试包，**不在 lib 目录里**，离线拿不到。我已经把这两个依赖从 pom 里移除了，但测试源码还在，所以必须跳过测试编译。

### 第 4 步：取产物

```
D:\starrocks\kettle-src\assemblies\plugin\target\starrocks-kettle-connector-plugins-1.0-SNAPSHOT.zip
```

部署：解压后得到 `starrocks-kettle-connector` 目录，连同它一起丢进 Kettle 的 `data-integration\plugins\`，
再把 MySQL JDBC 驱动放进 `starrocks-kettle-connector\lib\`，重启 Spoon 即可在"批量加载"分类下看到该步骤。

---

## 四、我已经替你改好的东西

改动都在 `D:\starrocks\kettle-src`，`git diff` 可查：

| 文件 | 改动 | 为什么 |
|---|---|---|
| `pom.xml` | 移除 pentaho 两个仓库声明（注释保留了原文） | 该仓库 401 无法使用，留着只会拖慢并产生误导性报错 |
| `pom.xml` | `<pdi.version>` 与 `<parent><version>` 改为 `8.3.0.0-371` | 你公司 Kettle 实测为 8.3.0.0-371，不一致会报找不到 `pdi-kettle-all` |
| `pom.xml` | SWT `4.6` → `4.3` | 中央仓库只有 4.3，`provided` 作用域不影响功能 |
| `pom.xml` | SDK `1.0-SNAPSHOT` → `1.0` | `1.0-SNAPSHOT` 从未发布 |
| `impl/pom.xml` | 新增 `pentaho-kettle:pdi-kettle-all:pom:provided` | 一次性引入 lib 全部 jar |
| `impl/pom.xml` | 移除 `kettle-core` / `kettle-engine` 的 `tests` 分类器依赖 | 离线拿不到 |
| `ui/pom.xml` | 新增 `pentaho-kettle:pdi-kettle-all:pom:provided` | 同上 |
| `assemblies/plugin/pom.xml` | 显式声明 `maven-assembly-plugin` | 原来由父 POM 提供，用占位父 POM 后必须自己声明，否则不生成 zip |

**已验证的部分**（我在你这台机器上实际跑过）：

- ✅ 手工按仓库布局写入的构件能被 Maven 正常解析（含离线模式）
- ✅ `<type>pom</type>` + `<scope>provided</scope>` 的聚合依赖可以传递解析，且传递进来的依赖**继承 provided**（所以 kettle 的 jar 不会被打进插件包）
- ✅ `release=11` + JDK 17 编译产出 `major version: 55`（Java 11 字节码，Kettle 9.5 能加载）
- ✅ 用合成的假 lib 目录跑通全流程：父 POM 解析 → 依赖解析 → 进入 javac 编译
- ✅ JDK 17 可以正常读取 SWT 4.3 / JFace 3.3（2007 年的老 jar）的 class 文件
- ✅ 工具能自动调用 mvn 探测真实本地仓库（实测识别出 `C:\Users\<用户名>\.m2\repository`）
- ✅ 手工传入与 Maven 不一致的仓库路径时，会打印醒目告警
- ✅ 路径兼容 Git Bash 风格 `/d/...` 与 Windows 风格 `D:\...`；`--with-plugins` 可放任意位置；重复坐标自动去重并告警

**未能验证的部分**（需要你在本地跑）：

- ⚠️ 真实 kettle jar 的完整编译。我手上拿不到 PDI 安装包，所以是用合成的假 jar 验证到"进入 javac 阶段"为止，报错内容是 `程序包 org.pentaho.di.core.exception 不存在`——这正是假 jar 没有真实类导致的，说明 Maven 管线已经通了。
- ⚠️ 最后 `maven-assembly-plugin` 打 zip 的环节。

---

## 五、常见报错与处理

### 0. `Cannot access xxx in offline mode and the artifact ... has not been downloaded from it before`

**这是 `-o` 造成的，跟项目无关。** 离线模式下 Maven 连插件本身都加载不了：

```
[ERROR] Cannot access aliyun-maven (https://maven.aliyun.com/repository/public) in offline mode
        and the artifact org.apache.maven.doxia:doxia-sink-api:jar:1.12.0 has not been downloaded from it before.
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-dependency-plugin:3.7.0:get
```

**处理：去掉 `-o`。** 你机器能上中央仓库（公司 settings.xml 里配了阿里云镜像），第一次必须联网让 Maven 把插件和依赖下全。只有在**所有依赖都已缓存**之后，`-o` 才有意义。

### 1. 编译成功但 Maven 说找不到 kettle 构件

99% 是**本地仓库路径对不上**。先确认：

```bash
mvn help:evaluate -Dexpression=settings.localRepository
```

拿这个输出和灌仓库时工具打印的 `Maven 本地仓库` 一行对比，必须一致。不一致就用环境变量指定后重灌：

```bash
M2_REPO="D:\Maven\apache-maven-3.9.11\repo" bash 1-setup-repo.sh /d/starrocks/pdi-lib
```

### 2. `程序包 org.pentaho.di.xxx 不存在` / `找不到符号`

说明有 jar 没进本地仓库。按顺序试：

```bash
# a. 确认 lib 目录拷全了
ls /d/starrocks/pdi-lib/lib | wc -l        # 正常应该有 100+ 个 jar

# b. 确认仓库里确实写进去了（<本地仓库> 换成实际路径）
ls "<本地仓库>/pentaho-kettle/"

# c. 如果某些类在 plugins 目录下的插件包里，加上 --with-plugins 重跑
#    （--with-plugins 可以放在任意位置，路径写成 Git Bash 风格 /d/... 也可以，工具会自动转换）
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 PdiOfflineSetup.java --with-plugins /d/starrocks/pdi-lib
```

如果还是缺，看报错里的类名，去公司电脑上 `grep` 是哪个 jar 提供的，单独拷过来再跑一次工具。

### 3. `UnsupportedClassVersionError` / 插件加载失败

字节码版本高于 Kettle 运行的 JVM。占位父 POM 里已经用 `<maven.compiler.release>11</maven.compiler.release>` 锁死了，**不要删掉这行**。如果改过，检查产物：

```bash
javap -verbose impl/target/classes/com/starrocks/connector/kettle/steps/starrockskettleconnector/StarRocksKettleConnector.class | grep major
# 必须是 55（Java 11）。如果是 61 就是 Java 17 字节码，Kettle 9.5 加载不了。
```

### 4. `找不到符号 org.junit...`

忘了加 `-Dmaven.test.skip=true`。

### 5. `Non-resolvable parent POM ... pdi-plugins`

占位父 POM 没生成，或版本对不上。检查（`<本地仓库>` 换成实际路径）：

```bash
ls "<本地仓库>/org/pentaho/di/plugins/pdi-plugins/"
```

目录名必须和 `pom.xml` 里 `<parent><version>` 完全一致。

### 6. `assembly descriptor not found` / 没生成 zip

`assemblies/plugin/pom.xml` 里的 assembly 插件配置被改坏了，或者 `src/assembly/assembly.xml` 被删了。

### 7. 编译很慢 / 一直卡在下载

Maven 在逐个尝试远程仓库。**先把依赖跑通一遍，之后**才可以用离线模式加速：

```bash
mvn -o -B clean package -Dmaven.test.skip=true
```

> 但注意：`-o` 只有在插件和依赖**全部已缓存**之后才可用。如果报
> `Cannot access xxx in offline mode`，说明还有东西没下过，去掉 `-o` 再跑一次即可。

---

## 六、注意事项

1. **`-Dmaven.test.skip=true` 是长期的。** 想跑测试的话，需要另外拿到 `kettle-core-<版本>-tests.jar` 和 `kettle-engine-<版本>-tests.jar`，从公司电脑的 Kettle 目录里找找看（通常在 Maven 仓库缓存里，不在发行包中）。找到后灌进本地仓库，再把 `impl/pom.xml` 里被注释掉的那两个依赖恢复。
2. **SWT 用的是 Linux GTK 版本（4.3），这是编译用的。** 因为它是 `provided` 作用域，不会被打包；运行时用的是 Kettle 自带的 SWT，所以不影响 Windows 上使用。
3. **工具只扫 `lib/`，不扫 `plugins/`。** 这是刻意的——`plugins/` 下是各种步骤插件，混进来可能引入冲突版本的公共库。真缺东西再用 `--with-plugins`。
4. **换 Kettle 版本要重跑第 2 步。** 本地仓库里的构件是按版本号分目录的，版本变了就是另一套。
5. **别把本地仓库里的 `org/pentaho` 或 `pentaho-kettle` 目录删了。** 删了就要重新灌。注意路径是上一步实测出来的那个，不一定是 `~/.m2/repository`。

---

## 七、附：本次分析中用到的实测命令

```bash
# 确认本地仓库路径
mvn help:evaluate -Dexpression=settings.localRepository -DforceStdout

# 验证手工写入仓库布局可被解析（离线）
mvn -o dependency:get -Dartifact=com.example:testlib:1.0

# 验证聚合依赖 + provided 传递
mvn dependency:list | grep -E "testlib|pdi-kettle-all"

# 验证字节码版本
javap -verbose target/classes/App.class | grep major

# 验证 JDK17 可读老 jar
javac -classpath "jface.jar;swt43.jar" P.java
```
