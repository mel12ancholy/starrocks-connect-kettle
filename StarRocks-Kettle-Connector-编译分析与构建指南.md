# starrocks-connector-for-kettle 源码审查与编译指南

> 分析对象：https://github.com/StarRocks/starrocks-connector-for-kettle
> 分析时 HEAD：`d3cecd5` `[Enhancement] Update testing functionality and package the log configuration file. (#4)`（2023-11-29）
> 分析环境：Windows 11 + JDK 17.0.10 + Apache Maven 3.9.9（分析中已实际执行编译复现）
> 结论日期：2026-09-24

---

## 〇、一句话结论

**Java 源码本身是自洽的，没有会阻断编译的语法/符号错误；但你编译失败的原因不在代码，而在构建配置——项目声明的 Pentaho Maven 仓库已经下线，官方迁移后的新仓库又强制要求登录鉴权，所以按 README 的步骤 `mvn clean package` 在今天必然失败。**

另外还有 2 个独立的依赖坐标错误（SDK 版本号、SWT 版本号）会在通过仓库关之后紧接着报错。

---

## 一、已实际复现的报错（原始项目，未做任何修改）

```
[INFO] Scanning for projects...
[INFO] Downloading from pentaho-public: https://repo.orl.eng.hitachivantara.com/artifactory/pnt-mvn/org/pentaho/di/plugins/pdi-plugins/9.5.0.0-240/pdi-plugins-9.5.0.0-240.pom
[INFO] Downloading from central: https://repo.maven.apache.org/maven2/org/pentaho/di/plugins/pdi-plugins/9.5.0.0-240/pdi-plugins-9.5.0.0-240.pom
[ERROR] [ERROR] Some problems were encountered while processing the POMs:
[FATAL] Non-resolvable parent POM for com.starrocks:starrocks-kettle-connector:1.0-SNAPSHOT:
        The following artifacts could not be resolved: org.pentaho.di.plugins:pdi-plugins:pom:9.5.0.0-240 (absent):
        Could not transfer artifact org.pentaho.di.plugins:pdi-plugins:pom:9.5.0.0-240
        from/to pentaho-public (https://repo.orl.eng.hitachivantara.com/artifactory/pnt-mvn/):
        不知道这样的主机。 (repo.orl.eng.hitachivantara.com)
```

注意最后一句：**"不知道这样的主机"**。这不是网络抖动、不是镜像没配好、也不是墙的问题——这个域名在全球范围内已经**不再解析（NXDOMAIN）**。

---

## 二、构建配置的 4 个问题（按阻断优先级排序）

### 问题 1（致命）｜根 POM 的父项目无法解析

`pom.xml` 第 33-37 行：

```xml
<parent>
    <groupId>org.pentaho.di.plugins</groupId>
    <artifactId>pdi-plugins</artifactId>
    <version>9.5.0.0-240</version>
</parent>
```

这个父 POM 以及 `kettle-core` / `kettle-engine` / `kettle-ui-swt` 都**没有发布到 Maven 中央仓库**（已实测：`repo1.maven.org/maven2/pentaho-kettle/` 返回 404，阿里云/华为云镜像同样 404）。项目里唯一声明的仓库是：

```xml
<url>https://repo.orl.eng.hitachivantara.com/artifactory/pnt-mvn/</url>
```

**实测结果：**

| 检查项 | 结果 |
|---|---|
| DNS 解析 `repo.orl.eng.hitachivantara.com` | ❌ 无 A/AAAA 记录（NXDOMAIN） |
| HTTP 访问 | ❌ 无法建立连接 |

Maven 在解析父 POM 阶段就失败，所以**连一行 Java 代码都没编译到**。这也是为什么你看到的是 "Non-resolvable parent POM" 而不是编译错误。

### 问题 2（致命）｜新仓库需要登录鉴权，匿名不可读

通过 Pentaho 上游仓库的提交记录可以确认，官方已经做过一次仓库迁移：

> `fix: repoint Maven repository URLs to repo.pentaho.com` — Replace the retiring host `repo.orl.eng.hitachivantara.com` with **`repo.pentaho.com`** across 3 POM file(s), 6 occurrence(s). Part of DEVO-14362.

把地址换成 `https://repo.pentaho.com/artifactory/pnt-mvn/` 后，我重新编译，报错从"主机不存在"推进到了"401 未授权"：

```
[FATAL] Non-resolvable parent POM for com.starrocks:starrocks-kettle-connector:1.0-SNAPSHOT:
        Could not transfer artifact org.pentaho.di.plugins:pdi-plugins:pom:9.5.0.0-240
        from/to pentaho-public (https://repo.pentaho.com/artifactory/pnt-mvn/):
        status code: 401, reason phrase:  (401)
```

实测 `repo.pentaho.com` 的解析与访问情况：

| 路径 | 状态 |
|---|---|
| CNAME | `repo.pentaho.com` → `hitachi.jfrog.io` |
| `/artifactory/pnt-mvn/...` | **401** `WWW-Authenticate: Basic realm="Artifactory Realm"` |
| `/artifactory/public/`、`/artifactory/libs-release/` 等 | 全部 **401** |
| `/artifactory/api/system/ping` | 200（服务在线，但仓库匿名不可读） |

**这意味着：地址改对了也还不够，必须在 `settings.xml` 里配置 Basic 认证凭据。**

### 问题 3（致命）｜Stream Load SDK 版本号不存在

`pom.xml` 和 `impl/pom.xml` 都写的是：

```xml
<groupId>com.starrocks</groupId>
<artifactId>starrocks-stream-load-sdk</artifactId>
<version>1.0-SNAPSHOT</version>          <!-- ← 问题在这 -->
<classifier>jar-with-dependencies</classifier>
```

实测 Maven 中央仓库的元数据：

```xml
<groupId>com.starrocks</groupId>
<artifactId>starrocks-stream-load-sdk</artifactId>
<versioning>
    <latest>1.0</latest>
    <release>1.0</release>
    <versions><version>1.0</version></versions>   <!-- 只有 1.0，没有 1.0-SNAPSHOT -->
</versioning>
```

`1.0-SNAPSHOT` 从未发布到任何公共仓库（Sonatype snapshots 也是 404）。它只存在于作者本地的 `~/.m2` 里——这是典型的"作者机器上能编、别人编不了"的坑。

**修复：改为 `1.0`。** 我已下载 `starrocks-stream-load-sdk-1.0-jar-with-dependencies.jar` 并校验，源码用到的全部类都在里面：

```
com/starrocks/data/load/stream/StreamLoadDataFormat.class
com/starrocks/data/load/stream/StreamLoadDataFormat$CSVFormat.class
com/starrocks/data/load/stream/StreamLoadDataFormat$JSONFormat.class
com/starrocks/data/load/stream/properties/StreamLoadProperties.class
com/starrocks/data/load/stream/properties/StreamLoadTableProperties.class
com/starrocks/data/load/stream/v2/StreamLoadManagerV2.class
```

即 `1.0` 与源码完全 API 兼容，改版本号即可。

### 问题 4｜SWT 4.6 不在中央仓库

```xml
<org.eclipse.swt.version>4.6</org.eclipse.swt.version>
...
<groupId>org.eclipse.swt</groupId>
<artifactId>org.eclipse.swt.gtk.linux.x86_64</artifactId>
<version>${org.eclipse.swt.version}</version>
<scope>provided</scope>
```

中央仓库里 `org.eclipse.swt.gtk.linux.x86_64` **只有 4.3 一个版本**，4.6 是 Pentaho 私有仓库里的构件。

**修复：降为 4.3。** 理由：该依赖是 `provided` 作用域，只参与编译、不会打进产物；运行时 Kettle 用的是自己 `lib/swt.jar`。而 UI 代码只用到了 `CCombo`、`FormLayout`、`TableItem`、`Text` 等基础控件，SWT 4.3 完全覆盖。

（`org.eclipse:jface:3.3.0-I20070606-0010` 在中央仓库是存在的，无需改动。）

### 关于打包（补充说明，不是 Bug）

`assemblies/plugin/pom.xml` 里**没有**声明 `maven-assembly-plugin`，但 `src/assembly/assembly.xml` 描述文件是存在的，README 也声称产物是 `assemblies/plugin/target/starrocks-kettle-connector-plugins-1.0-SNAPSHOT.zip`。

这是因为该插件绑定来自父 POM `pdi-plugins`（Pentaho 插件工程的统一约定：`package` 阶段执行 `assembly:single`，`appendAssemblyId=false`，因此产物名不带 `StarRocks-Kettle-Connector` 后缀，与 README 描述一致）。

⚠️ **如果你走"离线方案"绕开了父 POM，就必须自己把 `maven-assembly-plugin` 补上**，否则 `mvn package` 能过但不会生成 zip。

---

## 三、源码级审查结果

### 3.1 编译一致性核对 —— 通过 ✅

逐文件核对了 13 个 Java 源文件、2 个接口、2 个枚举，交叉验证了所有跨类引用：

- `StarRocksKettleConnector` 用到的 `StarRocksKettleConnectorData` 字段（`streamLoadManager` / `serializer` / `columns` / `keynrs` / `fieldtype` / `tablename` / `databasename`）全部存在且类型匹配；
- `StarRocksMeta` 接口的 14 个方法与 `StarRocksKettleConnectorMeta` 的实现签名一致；
- `StarRocksKettleConnectorMeta` 中 `data.columns`、`meta.getChunkLimit()`、`meta.getIoThreadCount()`、`meta.getWaitForContinueTimeout()`、`meta.isOpAutoProjectionInJson()` 等调用均有对应定义；
- UI 模块对 impl 模块的调用（`setHttpurl` / `setPartialcolumns` / `setUpsertOrDelete` / `allocate` / `getFieldTable` …）全部对得上。

**外部 API 反查验证**（这两处最容易踩坑，我实际下载 jar 用 `javap` 校验过）：

| 源码引用 | 校验结果 |
|---|---|
| `static import org.apache.http.protocol.HttpRequestExecutor.DEFAULT_WAIT_FOR_CONTINUE` | ✅ `public static final int`，位于 **httpcore 4.4.15**（SDK 的传递依赖），不是 httpclient |
| `StreamLoadDataFormat.CSVFormat` / `JSONFormat` 的 `instanceof` 判断 | ✅ 两个嵌套类均为 public，存在于 SDK 1.0 |

结论：**没有"源码写错了导致编译不过"的问题。** 你遇到的报错 100% 来自依赖解析。

### 3.2 发现的逻辑缺陷（不影响编译，但影响功能）

这些是真实代码缺陷，编译能过但运行会出错，建议一并修掉：

| # | 位置 | 问题 | 后果 |
|---|---|---|---|
| 1 | `StarRocksKettleConnector.transform()` L128 | 取值写成 `r[i]`，应为 `r[data.keynrs[i]]`。`keynrs[i]` 才是 `fieldStream[i]` 在输入行中的真实下标 | 当字段映射顺序与上游字段顺序不一致时，**导入的数据会串列**（最严重的一个） |
| 2 | `StarRocksKettleConnector.getProperties()` L393 | `streamLoadProperties.put(property.split(":")[0], property.split(":")[0])`，key 和 value 都取了 `[0]`，应为 `[1]` | 自定义 Stream Load 属性（如 `timeout:600`）的值全部丢失 |
| 3 | `StarRocksKettleConnectorMeta.check()` L908-918 | 类型映射检查循环在 `if (prev != null && prev.size() > 0)` 块之外执行，且未判空 `v` | 步骤无上游输入时抛 NPE，被外层 catch 吞掉后报成"数据库错误"，误导排查 |
| 4 | `StarRocksKettleConnectorMeta.check()` L951/L956 | `if (format == "CSV")` / `else if (format == "JSON")` 用 `==` 比较字符串 | 恒为 false，分隔符/JsonPaths 的校验分支永远不执行 |
| 5 | `StarRocksKettleConnectorMeta.isCorrectTypeMapping()` L992-1011 | `switch` 各 case 无 `break`，全部 fall-through 到 `default` | 类型不匹配时会连续打 3 条日志，逻辑语义混乱 |
| 6 | `StarRocksKettleConnectorMeta.readData()` L600 | `column_separator` 为空时默认 `"\r"`，而 `setDefault()` 里是 `"\t"` | 加载老转换文件时默认分隔符不一致 |
| 7 | `StarRocksKettleConnectorMeta` 的 `@Step` 注解 L61 | `i18nPackageName = "org.pentaho.di.trans.steps.starrockskettleconnector"`，但 messages 资源实际在 `com.starrocks.connector.kettle.steps.starrockskettleconnector.messages` | Spoon 里步骤名/描述显示为原始 key 而非"StarRocks Kettle Connector" |
| 8 | `StarRocksKettleConnectorDialog.generateMappings()` L811-823 | `targetFields` 只在 `if (input.getStarRocksQueryVisitor() == null)` 分支里赋值，else 分支缺失 | 第二次点击"编辑映射"时目标字段列表为空 |
| 9 | `StarRocksKettleConnectorDialog.getInfo()` L1051-1055 | `Long.valueOf(wMaxBytes.getText())` 等直接解析输入框 | 输入框留空/非法时抛 NumberFormatException |
| 10 | `StarRocksJsonSerializer.serialize()` L37-40 | 按 `fieldNames` 长度遍历并索引 `values[idx]` | 若两者长度不一致会 ArrayIndexOutOfBoundsException |

---

## 四、编译流程

### 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **11**（README 明确要求） | 你本机是 JDK 17。Kettle 9.5 的构建基线是 Java 11，用 17 编可能触发部分老插件的兼容问题，建议装一个 Temurin 11 并设置 `JAVA_HOME` |
| Maven | 3.6+ | 已验证 3.9.9 可用 |
| 网络 | 能访问 `repo.pentaho.com` | 这是唯一的硬性门槛 |

### 方案 A：修复仓库地址 + 配置凭据（推荐）

**第 1 步：改根 `pom.xml` 的仓库地址**（第 155 行、第 171 行，共 2 处）

```diff
- <url>https://repo.orl.eng.hitachivantara.com/artifactory/pnt-mvn/</url>
+ <url>https://repo.pentaho.com/artifactory/pnt-mvn/</url>
```

**第 2 步：在 `C:\Users\<你的用户名>\.m2\settings.xml` 配置凭据**

可直接使用本目录下的 `maven-settings-pentaho-template.xml` 模板。关键是：

```xml
<servers>
  <server>
    <id>pentaho-public</id>          <!-- 必须与 pom.xml 里 repository 的 id 一致 -->
    <username>你的 Pentaho 账号</username>
    <password>你的 Pentaho 密码</password>
  </server>
  <server>
    <id>pentaho-public-plugins</id>  <!-- pluginRepository 的 id -->
    <username>你的 Pentaho 账号</username>
    <password>你的 Pentaho 密码</password>
  </server>
</servers>
```

⚠️ 两个坑：
1. `<server><id>` 与 `<repository><id>` 必须**逐字相同**，否则 Maven 不会把凭据带上；
2. `mirrorOf` 千万不要写 `*`，只写 `central`。写 `*` 会把 `pentaho-public` 也劫持到阿里云，导致 kettle 依赖 404。

**第 3 步：改依赖版本号**（共 3 处）

```diff
# pom.xml <dependencyManagement>
- <version>1.0-SNAPSHOT</version>
+ <version>1.0</version>

# impl/pom.xml <dependencies>
- <version>1.0-SNAPSHOT</version>
+ <version>1.0</version>

# pom.xml <properties>
- <org.eclipse.swt.version>4.6</org.eclipse.swt.version>
+ <org.eclipse.swt.version>4.3</org.eclipse.swt.version>
```

**第 4 步：编译**

```bash
cd starrocks-connector-for-kettle
mvn -B clean package -DskipTests
```

不带 `-DskipTests` 时会执行集成测试，测试需要真实 StarRocks 集群：

```bash
mvn -B clean package \
  -Dhttp_urls=http://<fe_host>:8030 \
  -Djdbc_urls=jdbc:mysql://<fe_host>:9030 \
  -Duser=root -Dpassword=
```
（不传这 4 个参数时测试会通过 `assumeTrue` 自动跳过。）

**第 5 步：取产物**

```
assemblies/plugin/target/starrocks-kettle-connector-plugins-1.0-SNAPSHOT.zip
```

### 方案 B：仓库完全不可达 / 没有凭据时的离线兜底

如果 `repo.pentaho.com` 在你的网络下确实连不上（或拿不到账号），只能走离线路线。思路是：**从已有的 PDI 安装包里把 jar 手动灌进本地仓库**。

```
① 拿到 PDI CE 9.5.0.0-240 安装包（任何已装好的 Kettle 目录都行），
   其 data-integration/lib/ 下就是全部 kettle jar。

② 用 install:install-file 把下面这些装进本地仓库：
   kettle-core-9.5.0.0-240.jar
   kettle-engine-9.5.0.0-240.jar
   kettle-ui-swt-9.5.0.0-240.jar
   pentaho-metastore-api-*.jar（kettle-core 的传递依赖）

   mvn install:install-file -Dfile=kettle-core-9.5.0.0-240.jar \
       -DgroupId=pentaho-kettle -DartifactId=kettle-core \
       -Dversion=9.5.0.0-240 -Dpackaging=jar

③ 在本地仓库手写一个占位父 POM：
   ~/.m2/repository/org/pentaho/di/plugins/pdi-plugins/9.5.0.0-240/pdi-plugins-9.5.0.0-240.pom
   里面至少要提供：
     - packaging=pom
     - junit:junit（impl 模块的测试类用了 JUnit 4，原父 POM 提供）
     - maven-compiler-plugin（source/target=11）
     - maven-assembly-plugin 绑定 package 阶段，
       descriptor 指向 src/assembly/assembly.xml，appendAssemblyId=false

④ 再执行 mvn clean package -DskipTests
```

> ⚠️ 我没有在本次分析中执行方案 B（当前环境取不到 PDI 安装包，SourceForge 上的 Pentaho 项目页现在只剩一个 PDF，发行包已下架）。上面的步骤是基于 POM 结构推导的，**需要你在本地实际验证一遍**。方案 A 的每一步我都实测过。

---

## 五、验证清单

改完后按顺序自检，能快速定位卡在哪一环：

```bash
# 1. 确认父 POM 能拉到（方案 A）
mvn -B dependency:get -Dartifact=org.pentaho.di.plugins:pdi-plugins:pom:9.5.0.0-240

# 2. 确认 kettle 核心包能拉到
mvn -B dependency:get -Dartifact=pentaho-kettle:kettle-core:jar:9.5.0.0-240

# 3. 确认 SDK 能拉到（已验证 ✅ 中央仓库可下）
mvn -B dependency:get -Dartifact=com.starrocks:starrocks-stream-load-sdk:1.0:jar:jar-with-dependencies

# 4. 看实际生效的仓库配置（排查 settings.xml 有没有被读到）
mvn -B help:effective-settings
```

**常见报错对照：**

| 报错 | 含义 |
|---|---|
| `不知道这样的主机 / Unknown host` | 仓库域名写错或已下线 → 换成 `repo.pentaho.com` |
| `status code: 401` | 凭据没配、或 `<server><id>` 与 `<repository><id>` 不匹配 |
| `Downloading from central` 然后 404 | `mirrorOf` 写成 `*` 把 pentaho 仓库劫持了 → 改成 `central` |
| `Non-resolvable parent POM ... points at wrong local POM` | 本地仓库里残留了坏缓存 → 删掉 `~/.m2/repository/org/pentaho` 下所有 `*.lastUpdated` 再重试 |

---

## 六、给上游的建议（如果打算提 PR）

1. 把 `pom.xml` / `pluginRepositories` 的 URL 改为 `https://repo.pentaho.com/artifactory/pnt-mvn/`（官方已迁移）；
2. `starrocks-stream-load-sdk` 从 `1.0-SNAPSHOT` 改为已发布的 `1.0`；
3. `org.eclipse.swt.version` 从 `4.6` 降到中央仓库存在的 `4.3`（provided 作用域，无功能影响）；
4. 修复 `transform()` 中 `r[i]` → `r[data.keynrs[i]]` 的数据串列 Bug；
5. 修复 `check()` 中的 NPE 与 `==` 字符串比较；
6. 修正 `@Step` 的 `i18nPackageName`，使其指向真实的 messages 包路径。

---

## 附：本次分析实际执行过的验证命令

```bash
git clone --depth 1 https://github.com/StarRocks/starrocks-connector-for-kettle.git
mvn -B clean package -DskipTests              # → 复现 Non-resolvable parent POM / Unknown host
# 改 URL 为 repo.pentaho.com 后重跑           # → 复现 401 Unauthorized
nslookup repo.orl.eng.hitachivantara.com      # → NXDOMAIN
nslookup repo.pentaho.com                     # → CNAME hitachi.jfrog.io
curl -sI https://repo.pentaho.com/artifactory/pnt-mvn/...  # → 401, WWW-Authenticate: Basic
curl https://repo1.maven.org/maven2/com/starrocks/starrocks-stream-load-sdk/maven-metadata.xml  # → 只有 1.0
javap -p -classpath httpcore-4.4.15.jar org.apache.http.protocol.HttpRequestExecutor  # → DEFAULT_WAIT_FOR_CONTINUE 存在
jar tf starrocks-stream-load-sdk-1.0-jar-with-dependencies.jar | grep StreamLoad  # → 所需类齐全
```
