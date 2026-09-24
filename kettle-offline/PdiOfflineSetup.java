import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * PdiOfflineSetup —— 把本地已安装的 Kettle(PDI) 的 lib 目录批量灌入 Maven 本地仓库，
 * 并生成聚合依赖 pdi-kettle-all 与占位父 POM pdi-plugins。
 *
 * 用法（JDK 11+，单文件直接运行）：
 *   java -Dfile.encoding=UTF-8 PdiOfflineSetup.java <KETTLE_HOME> [M2_REPO] [TARGET_VERSION] [--with-plugins]
 *
 * 参数：
 *   KETTLE_HOME     指向 data-integration 目录（其下有 lib/），也可以直接指向 lib 目录本身
 *   M2_REPO         本地仓库路径，默认 %USERPROFILE%\.m2\repository
 *   TARGET_VERSION  目标版本，默认 9.5.0.0-240（必须与项目 pom 的 <pdi.version> 一致）
 *   --with-plugins  额外扫描 plugins/​**​/lib 下的 jar（一般不需要）
 */
public class PdiOfflineSetup {

    static final String AGG_GROUP = "pentaho-kettle";
    static final String AGG_ARTIFACT = "pdi-kettle-all";
    static final String STUB_PARENT_GROUP = "org.pentaho.di.plugins";
    static final String STUB_PARENT_ARTIFACT = "pdi-plugins";

    static final List<String> REQUIRED = Arrays.asList("kettle-core", "kettle-engine", "kettle-ui-swt");

    static int installed = 0, skipped = 0, duplicated = 0;
    static final List<String> warnings = new ArrayList<>();
    static final List<String[]> coords = new ArrayList<>(); // g, a, v
    static final Set<String> seen = new HashSet<>();
    static String detectedKettleVersion = null;

    public static void main(String[] args) throws Exception {
        // 位置参数 = 不以 -- 开头且非空的参数；--with-plugins 可出现在任意位置
        List<String> pos = new ArrayList<>();
        boolean withPlugins = false;
        for (String a : args) {
            if (a == null || a.isBlank()) continue;
            if (a.startsWith("--")) {
                if (a.equals("--with-plugins")) withPlugins = true;
                else die("未知参数：" + a + "\n" + usage());
            } else {
                pos.add(a);
            }
        }
        if (pos.isEmpty()) {
            System.out.println(usage());
            System.exit(2);
        }

        Path kettleHome = normalize(pos.get(0));
        Path m2 = normalize(pos.size() >= 2 ? pos.get(1)
                : System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository");
        String targetVersion = pos.size() >= 3 ? pos.get(2) : "9.5.0.0-240";

        // ---- 1. 定位 lib 目录 ----
        Path libDir = kettleHome.resolve("lib");
        if (!Files.isDirectory(libDir)) {
            if (looksLikeLib(kettleHome)) {
                libDir = kettleHome;
            } else {
                die("找不到 lib 目录：" + libDir + "\n请把 KETTLE_HOME 指向 data-integration 目录（其下有 lib/）。");
            }
        }

        System.out.println("========================================================");
        System.out.println("  Kettle lib 目录 : " + libDir);
        System.out.println("  Maven 本地仓库  : " + m2);
        System.out.println("  目标版本        : " + targetVersion);
        System.out.println("========================================================");

        List<Path> jars = new ArrayList<>();
        collectJars(libDir, jars);
        if (withPlugins) {
            Path plugins = kettleHome.resolve("plugins");
            if (Files.isDirectory(plugins)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(plugins)) {
                    for (Path p : ds) {
                        Path pl = p.resolve("lib");
                        if (Files.isDirectory(pl)) collectJars(pl, jars);
                    }
                }
            }
        }
        System.out.println("发现 jar 数量：" + jars.size());
        if (jars.isEmpty()) die("lib 目录下没有 jar。");

        // ---- 2. 逐个写入本地仓库 ----
        for (Path jar : jars) {
            String[] c = resolveCoordinate(jar, targetVersion);
            if (c == null) { skipped++; continue; }
            String key = c[0] + ":" + c[1] + ":" + c[2];
            if (seen.contains(key)) {
                duplicated++;
                warnings.add("坐标重复，后者被忽略：" + key + "  <- " + jar.getFileName());
                continue;
            }
            seen.add(key);

            if (c[1].equals("kettle-core") && c[0].equals("pentaho-kettle")) detectedKettleVersion = c[2];
            if (c[1].equals(AGG_ARTIFACT) && c[0].equals(AGG_GROUP)) continue; // 不覆盖自己的聚合包

            writeArtifact(m2, c[0], c[1], c[2], jar);
            coords.add(c);
            installed++;
        }

        // ---- 3. 检查三个必需构件 ----
        List<String> missing = new ArrayList<>();
        for (String need : REQUIRED) {
            boolean found = false;
            for (String[] c : coords) if (c[1].equals(need)) { found = true; break; }
            if (!found) missing.add(need);
        }
        for (String m : missing) warnings.add("缺少必需构件：" + m + "（确认 KETTLE_HOME 指向的是 PDI 9.5 的 data-integration）");

        // ---- 4. 生成聚合 POM ----
        StringBuilder deps = new StringBuilder();
        for (String[] c : coords) {
            deps.append("    <dependency>\n")
                .append("      <groupId>").append(c[0]).append("</groupId>\n")
                .append("      <artifactId>").append(c[1]).append("</artifactId>\n")
                .append("      <version>").append(c[2]).append("</version>\n")
                .append("    </dependency>\n");
        }
        String aggPom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>" + AGG_GROUP + "</groupId>\n"
                + "  <artifactId>" + AGG_ARTIFACT + "</artifactId>\n"
                + "  <version>" + targetVersion + "</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <name>PDI Kettle lib aggregate (offline)</name>\n"
                + "  <dependencies>\n" + deps + "  </dependencies>\n"
                + "</project>\n";
        writePom(m2, AGG_GROUP, AGG_ARTIFACT, targetVersion, aggPom);
        System.out.println("已生成聚合依赖：" + AGG_GROUP + ":" + AGG_ARTIFACT + ":pom:" + targetVersion
                + "（含 " + coords.size() + " 个构件）");

        // ---- 5. 生成占位父 POM ----
        String stub = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!-- 离线占位父 POM：替代 org.pentaho.di.plugins:pdi-plugins，仅提供编译所需的最小配置 -->\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>" + STUB_PARENT_GROUP + "</groupId>\n"
                + "  <artifactId>" + STUB_PARENT_ARTIFACT + "</artifactId>\n"
                + "  <version>" + targetVersion + "</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <name>PDI Plugins (offline stub)</name>\n"
                + "  <properties>\n"
                + "    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "    <!-- 必须锁定 11：Kettle 9.5 运行在 Java 11 上，产物字节码不能高于 55 -->\n"
                + "    <maven.compiler.release>11</maven.compiler.release>\n"
                + "  </properties>\n"
                + "  <build>\n"
                + "    <pluginManagement>\n"
                + "      <plugins>\n"
                + "        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>\n"
                + "        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.1</version></plugin>\n"
                + "        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-assembly-plugin</artifactId><version>3.7.1</version></plugin>\n"
                + "        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId><version>3.6.1</version></plugin>\n"
                + "        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>\n"
                + "        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin>\n"
                + "        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-install-plugin</artifactId><version>3.1.2</version></plugin>\n"
                + "      </plugins>\n"
                + "    </pluginManagement>\n"
                + "  </build>\n"
                + "</project>\n";
        writePom(m2, STUB_PARENT_GROUP, STUB_PARENT_ARTIFACT, targetVersion, stub);
        System.out.println("已生成占位父 POM：" + STUB_PARENT_GROUP + ":" + STUB_PARENT_ARTIFACT + ":" + targetVersion);

        // ---- 6. 报告 ----
        StringBuilder rep = new StringBuilder();
        rep.append("PDI Offline Setup Report\n");
        rep.append("========================\n");
        rep.append("lib 目录        : ").append(libDir).append("\n");
        rep.append("本地仓库        : ").append(m2).append("\n");
        rep.append("目标版本        : ").append(targetVersion).append("\n");
        rep.append("检测到 Kettle 版本: ").append(detectedKettleVersion == null ? "未知" : detectedKettleVersion).append("\n");
        rep.append("写入构件数      : ").append(installed).append("\n");
        rep.append("跳过            : ").append(skipped).append("\n");
        rep.append("坐标重复        : ").append(duplicated).append("\n\n");
        if (detectedKettleVersion != null && !detectedKettleVersion.equals(targetVersion)) {
            rep.append("!!! 版本不一致 !!!\n");
            rep.append("你机器上的 Kettle 是 ").append(detectedKettleVersion)
               .append("，但工具按 ").append(targetVersion).append(" 写入。\n");
            rep.append("请把项目根 pom.xml 的 <pdi.version> 与 <parent><version> 都改成 ")
               .append(detectedKettleVersion).append("，\n");
            rep.append("并重新执行本工具：java PdiOfflineSetup.java <KETTLE_HOME> \"").append(m2).append("\" ")
               .append(detectedKettleVersion).append("\n\n");
        }
        if (!warnings.isEmpty()) {
            rep.append("警告：\n");
            for (String w : warnings) rep.append("  - ").append(w).append("\n");
        }
        Path report = Paths.get(System.getProperty("user.dir"), "pdi-offline-report.txt");
        Files.write(report, rep.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println();
        System.out.println(rep);
        System.out.println("报告已写入：" + report);
        if (detectedKettleVersion != null && !detectedKettleVersion.equals(targetVersion)) {
            System.out.println(">>> 注意：检测到 Kettle 版本为 " + detectedKettleVersion
                    + "，请把项目 pom 的 <pdi.version> 改成这个值后重新运行本工具。");
        }
    }

    // ---------------------------------------------------------------- helpers

    static String usage() {
        return "用法: java -Dfile.encoding=UTF-8 PdiOfflineSetup.java <KETTLE_HOME> [M2_REPO] [TARGET_VERSION] [--with-plugins]\n"
             + "  KETTLE_HOME     data-integration 目录（其下有 lib/）\n"
             + "  M2_REPO         默认 %USERPROFILE%\\.m2\\repository\n"
             + "  TARGET_VERSION  默认 9.5.0.0-240";
    }

    static void die(String msg) {
        System.err.println("[错误] " + msg);
        System.exit(1);
    }

    /**
     * 兼容 Git Bash / MSYS 风格路径：/d/starrocks/pdi-lib -> D:\starrocks\pdi-lib
     * 其余情况原样返回（仅做绝对化与规范化）。
     */
    static Path normalize(String raw) {
        String s = raw.trim();
        if (s.length() >= 3 && s.charAt(0) == '/' && Character.isLetter(s.charAt(1)) && s.charAt(2) == '/') {
            s = Character.toUpperCase(s.charAt(1)) + ":\\" + s.substring(3).replace('/', '\\');
        } else if (s.length() == 2 && s.charAt(1) == ':' && Character.isLetter(s.charAt(0))) {
            s = s + "\\";
        } else {
            s = s.replace('/', File.separatorChar);
        }
        return Paths.get(s).toAbsolutePath().normalize();
    }

    static boolean looksLikeLib(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "kettle-core*.jar")) {
            return ds.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    static void collectJars(Path dir, List<Path> out) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) out.add(p);
        }
        Collections.sort(out);
    }

    /** 从 jar 内嵌的 META-INF/maven/<g>/<a>/pom.properties 读取真实坐标；读不到则按文件名兜底。 */
    static String[] resolveCoordinate(Path jar, String targetVersion) {
        String fileName = jar.getFileName().toString();
        if (fileName.endsWith("-sources.jar") || fileName.endsWith("-javadoc.jar")
                || fileName.endsWith("-tests.jar")) {
            return null; // 这些不是编译期需要的构件
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("META-INF/maven/") && n.endsWith("/pom.properties")) {
                    Properties p = new Properties();
                    try (InputStream in = zf.getInputStream(e)) { p.load(in); }
                    String g = p.getProperty("groupId");
                    String a = p.getProperty("artifactId");
                    String v = p.getProperty("version");
                    if (g != null && a != null && v != null && !g.isBlank() && !a.isBlank() && !v.isBlank()) {
                        return new String[]{g.trim(), a.trim(), v.trim()};
                    }
                }
            }
        } catch (Exception ignored) {
            // 落到文件名兜底
        }
        // 兜底：用文件名当 artifactId，保证唯一即可
        String base = fileName.substring(0, fileName.length() - 4).replaceAll("[^A-Za-z0-9._-]", "_");
        return new String[]{"local.pdi", base, targetVersion};
    }

    static Path artifactDir(Path m2, String g, String a, String v) {
        return m2.resolve(g.replace('.', File.separatorChar)).resolve(a).resolve(v);
    }

    static void writeArtifact(Path m2, String g, String a, String v, Path jar) throws IOException {
        Path dir = artifactDir(m2, g, a, v);
        Files.createDirectories(dir);
        Path dest = dir.resolve(a + "-" + v + ".jar");
        Files.copy(jar, dest, StandardCopyOption.REPLACE_EXISTING);
        writePom(m2, g, a, v, minimalPom(g, a, v));
    }

    static void writePom(Path m2, String g, String a, String v, String content) throws IOException {
        Path dir = artifactDir(m2, g, a, v);
        Files.createDirectories(dir);
        Files.write(dir.resolve(a + "-" + v + ".pom"), content.getBytes(StandardCharsets.UTF_8));
    }

    static String minimalPom(String g, String a, String v) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
             + "  <modelVersion>4.0.0</modelVersion>\n"
             + "  <groupId>" + g + "</groupId>\n"
             + "  <artifactId>" + a + "</artifactId>\n"
             + "  <version>" + v + "</version>\n"
             + "  <packaging>jar</packaging>\n"
             + "</project>\n";
    }
}
