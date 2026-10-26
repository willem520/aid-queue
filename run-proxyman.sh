#!/bin/zsh
# 经 Proxyman 抓本机 Java 外发 HTTPS（非手机 APP）。
#
# 使用前：
#   1. Proxyman 已安装，Certificate → Install on this Mac，并信任 Proxyman CA
#   2. SSL Proxying 列表含 jyt.ylbz.tj.gov.cn
#   3. 工具栏开启 macOS Proxy（或系统网络代理指向本机端口）
#
# 端口默认 9090，与 Proxyman Preferences → Advanced 不一致时：
#   PROXYMAN_PORT=10090 ./run-proxyman.sh
#
# runtime/truststore.jks 由 AppConfig 自动加载，用于 JVM 信任 Proxyman 签发的站点证书。

cd "$(dirname "$0")"

# Proxyman 重装/换机后 CA 会变，与 truststore 不一致会导致 CONNECT 999 / SSLHandshakeException
if [[ -x runtime/sync-proxyman-ca.sh ]]; then
  runtime/sync-proxyman-ca.sh
fi

PROXYMAN_PORT="${PROXYMAN_PORT:-9090}"
PROXY_HOST="${PROXYMAN_HOST:-127.0.0.1}"

if ! nc -z "${PROXY_HOST}" "${PROXYMAN_PORT}" 2>/dev/null; then
  echo "错误: ${PROXY_HOST}:${PROXYMAN_PORT} 无监听（请先打开 Proxyman，确认 Preferences → Advanced 端口）" >&2
  exit 1
fi

# spring-boot:run 会在子 JVM 里跑应用，仅 MAVEN_OPTS 不会把代理传给 HttpClient
JVM_PROXY="-Dhttps.proxyHost=${PROXY_HOST} -Dhttps.proxyPort=${PROXYMAN_PORT} \
-Dhttp.proxyHost=${PROXY_HOST} -Dhttp.proxyPort=${PROXYMAN_PORT}"

export JAVA_TOOL_OPTIONS="${JVM_PROXY} ${JAVA_TOOL_OPTIONS:-}"

echo "Proxyman 代理: ${PROXY_HOST}:${PROXYMAN_PORT}（JAVA_TOOL_OPTIONS + spring-boot 子 JVM）"
echo "请确认 Proxyman: 正在监听、未 Pause；SSL 列表含 jyt.ylbz.tj.gov.cn；列表选「全部」。"
echo "自检: curl -x http://${PROXY_HOST}:${PROXYMAN_PORT} -s -o /dev/null -w '%{http_code}\n' https://jyt.ylbz.tj.gov.cn/app-prod/ || echo '代理不可达'"

# 锁定 Java 21：Homebrew mvn 包装脚本在 JAVA_HOME 缺失时回落到最新 JDK，
# Lombok 注解处理在高版本 JDK 下失效会导致编译报错
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

exec mvn spring-boot:run -Dspring-boot.run.jvmArguments="${JVM_PROXY}"
