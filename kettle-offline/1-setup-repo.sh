#!/usr/bin/env bash
# ============================================================================
#  步骤 1：把本机 Kettle 的 lib 目录灌入 Maven 本地仓库
#
#  用法:
#     bash 1-setup-repo.sh <KETTLE_HOME> [版本]
#
#  示例:
#     bash 1-setup-repo.sh /d/starrocks/pdi-lib
#     bash 1-setup-repo.sh "D:/pdi/data-integration" 9.5.0.0-240
#
#  <KETTLE_HOME> 指向 data-integration 目录（其下有 lib/），
#                也可以直接指向 lib 目录本身。
#  [版本]        默认 9.5.0.0-240，必须与项目 pom 的 <pdi.version> 一致。
#
#  本地仓库会自动调用 mvn 探测（企业环境常被 settings.xml 改到别处），
#  想手工指定就设环境变量 M2_REPO。
# ============================================================================
set -euo pipefail

# ---------- 按需修改下面两行 ----------
JDK_HOME="${JDK_HOME:-D:/exercise/jdk-17.0.10}"          # 你的 JDK（11 或 17 都行）
MAVEN_HOME="${MAVEN_HOME:-D:/starrocks/tools/apache-maven-3.9.9}"   # 找不到 mvn 时用它兜底
# --------------------------------------

KETTLE_HOME="${1:-}"
VERSION="${2:-9.5.0.0-240}"

if [ -z "$KETTLE_HOME" ]; then
  echo "用法: bash 1-setup-repo.sh <KETTLE_HOME> [版本]"
  exit 2
fi

JAVA="$JDK_HOME/bin/java.exe"
[ -x "$JAVA" ] || JAVA="$JDK_HOME/bin/java"
if [ ! -x "$JAVA" ]; then
  echo "[错误] 找不到 java: $JAVA"
  echo "       请修改脚本顶部的 JDK_HOME"
  exit 1
fi

# ---------- 探测 Maven 本地仓库 ----------
find_mvn() {
  if command -v mvn >/dev/null 2>&1; then command -v mvn; return; fi
  for c in "$MAVEN_HOME/bin/mvn" "$MAVEN_HOME/bin/mvn.cmd"; do
    [ -f "$c" ] && { echo "$c"; return; }
  done
}
MVN="$(find_mvn || true)"

if [ -z "${M2_REPO:-}" ]; then
  if [ -n "$MVN" ]; then
    M2_REPO="$("$MVN" -B -q help:evaluate -Dexpression=settings.localRepository -DforceStdout 2>/dev/null \
               | tr -d '\r' | grep -E '^[A-Za-z]:[\\/]|^/' | head -1 || true)"
  fi
  if [ -z "${M2_REPO:-}" ]; then
    M2_REPO="$HOME/.m2/repository"
    echo "[提示] 未能通过 mvn 探测本地仓库，回退到 $M2_REPO"
    echo "       请与 mvn help:evaluate -Dexpression=settings.localRepository 的输出核对"
  else
    echo "[探测] Maven 本地仓库 = $M2_REPO"
  fi
else
  echo "[指定] Maven 本地仓库 = $M2_REPO"
fi
# ------------------------------------------

echo "使用 JDK : $JAVA"
"$JAVA" -version 2>&1 | head -1
echo

# 单文件源码运行，JDK 11+ 支持
"$JAVA" -Dfile.encoding=UTF-8 "$(dirname "$0")/PdiOfflineSetup.java" \
        "$KETTLE_HOME" "$M2_REPO" "$VERSION"

echo
echo "下一步：bash 2-build.sh"
