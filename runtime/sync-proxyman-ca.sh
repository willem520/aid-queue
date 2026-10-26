#!/bin/zsh
# 将本机 Proxyman 当前根证书写入 runtime/truststore.jks（Proxyman 重装/换机会换 CA，需重跑）。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KS="$ROOT/runtime/truststore.jks"
PEM="${PROXYMAN_CA_PEM:-$HOME/Library/Application Support/com.proxyman.NSProxy/app-data/proxyman-ca.pem}"

if [[ ! -f "$PEM" ]]; then
  echo "未找到 Proxyman CA: $PEM" >&2
  echo "请在 Proxyman: Certificate → Export → Root Certificate as PEM" >&2
  exit 1
fi
if [[ ! -f "$KS" ]]; then
  echo "未找到 $KS，请先创建 truststore（含 JDK 根证书 + proxyman-ca）" >&2
  exit 1
fi

keytool -delete -alias proxyman-ca -keystore "$KS" -storepass changeit 2>/dev/null || true
keytool -importcert -noprompt -alias proxyman-ca -file "$PEM" -keystore "$KS" -storepass changeit
echo "已更新 proxyman-ca:"
keytool -exportcert -alias proxyman-ca -keystore "$KS" -storepass changeit -rfc \
  | openssl x509 -noout -subject -fingerprint -sha256
