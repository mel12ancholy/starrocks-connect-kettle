#!/usr/bin/env bash
# ============================================================================
#  步骤 2：编译并打包 StarRocks Kettle Connector 插件
#
#  用法:
#     bash 2-build.sh [项目目录]
#     默认项目目录为 ../kettle-src
#
#  注意：本地仓库路径由 Maven 自己决定，本脚本不需要（也不应该）干预。
# ============================================================================
set -euo pipefail

# ---------- 按需修改 ----------
# JDK 优先用环境变量 JAVA_HOME / JDK_HOME，都没设才用下面的默认值
JDK_HOME="${JDK_HOME:-${JAVA_HOME:-D:/exercise/jdk-17.0.10}}"
# Maven 优先用 PATH 上的 mvn，找不到才用 MAVEN_HOME
MAVEN_HOME="${MAVEN_HOME:-D:/starrocks/tools/apache-maven-3.9.9}"
# ------------------------------

PROJECT_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)/kettle-src}"

if [ ! -f "$PROJECT_DIR/pom.xml" ]; then
  echo "[错误] 在 $PROJECT_DIR 下找不到 pom.xml"
  exit 1
fi

# ---------- 找 mvn：PATH 优先 ----------
MVN=""
if command -v mvn >/dev/null 2>&1; then
  MVN="$(command -v mvn)"
else
  for c in "$MAVEN_HOME/bin/mvn" "$MAVEN_HOME/bin/mvn.cmd"; do
    [ -f "$c" ] && { MVN="$c"; break; }
  done
fi
if [ -z "$MVN" ]; then
  echo "[错误] 找不到 mvn。请把 Maven 加进 PATH，或设置 MAVEN_HOME"
  exit 1
fi

export JAVA_HOME="$JDK_HOME"

echo "项目目录 : $PROJECT_DIR"
echo "Maven    : $MVN"
echo "JAVA_HOME: $JAVA_HOME"
echo

# 顺手报告一下 Maven 实际使用的本地仓库，方便和灌仓库时的输出核对
"$MVN" -B -q help:evaluate -Dexpression=settings.localRepository -DforceStdout 2>/dev/null \
  | tr -d '\r' | grep -E '^[A-Za-z]:[\\/]|^/' | head -1 \
  | sed 's/^/本地仓库 : /' || true
echo

cd "$PROJECT_DIR"

# 关键点说明：
#   -Dmaven.test.skip=true  跳过测试的编译与执行。
#       必须加，因为测试代码依赖 kettle-core / kettle-engine 的 tests 分类器构件，
#       这些构件只存在于 Pentaho 私有仓库，离线拿不到。
#   编译目标由占位父 POM 按 PDI 版本自动推导：PDI 8.x → Java 8，PDI 9.x → Java 11。
#       产物字节码高于运行时会 UnsupportedClassVersionError。
#       公司机器 Kettle 为 8.3.0.0-371，故目标为 Java 8。
#   不要加 -o：第一次构建需要联网从中央仓库/镜像下载插件与依赖。
"$MVN" -B clean package -Dmaven.test.skip=true "$@"

echo
echo "============================================================"
echo " 产物："
ls -l "$PROJECT_DIR"/assemblies/plugin/target/*.zip 2>/dev/null || echo "  (未生成 zip，请检查上面的日志)"
echo "============================================================"
