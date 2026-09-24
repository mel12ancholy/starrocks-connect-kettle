#!/usr/bin/env bash
# ============================================================================
#  步骤 2：编译并打包 StarRocks Kettle Connector 插件
#
#  用法:
#     bash 2-build.sh [项目目录]
#     默认项目目录为 ../kettle-src
# ============================================================================
set -euo pipefail

# ---------- 按需修改下面两行 ----------
JDK_HOME="${JDK_HOME:-D:/exercise/jdk-17.0.10}"
MAVEN_HOME="${MAVEN_HOME:-D:/starrocks/tools/apache-maven-3.9.9}"
# --------------------------------------

PROJECT_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)/kettle-src}"

if [ ! -f "$PROJECT_DIR/pom.xml" ]; then
  echo "[错误] 在 $PROJECT_DIR 下找不到 pom.xml"
  exit 1
fi

export JAVA_HOME="$JDK_HOME"
MVN="$MAVEN_HOME/bin/mvn"
[ -x "$MVN" ] || MVN="$MAVEN_HOME/bin/mvn.cmd"

if [ ! -f "$MVN" ] && [ ! -x "$MVN" ]; then
  echo "[错误] 找不到 mvn: $MVN"
  exit 1
fi

echo "项目目录 : $PROJECT_DIR"
echo "JAVA_HOME: $JAVA_HOME"
echo

cd "$PROJECT_DIR"

# 关键点说明：
#   -Dmaven.test.skip=true  跳过测试的编译与执行。
#       必须加，因为测试代码依赖 kettle-core / kettle-engine 的 tests 分类器构件，
#       这些构件只存在于 Pentaho 私有仓库，离线拿不到。
#   release=11 由占位父 POM 锁定，保证产物是 Java 11 字节码
#       （Kettle 9.5 跑在 Java 11 上，用 Java 17 字节码会 UnsupportedClassVersionError）。
"$MVN" -B clean package -Dmaven.test.skip=true "$@"

echo
echo "============================================================"
echo " 产物："
ls -l "$PROJECT_DIR"/assemblies/plugin/target/*.zip 2>/dev/null || echo "  (未生成 zip，请检查上面的日志)"
echo "============================================================"
