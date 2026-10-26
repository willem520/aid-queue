#!/bin/zsh
# 不启动 Spring，经 Proxyman 打一条 quryMultiDrHorn，验证 Mac 代理是否生效。
# 用法：先开 Proxyman + macOS Proxy，再：
#   ./reverse/proxy_probe.sh
# 或指定端口：PROXYMAN_PORT=9090 ./reverse/proxy_probe.sh

cd "$(dirname "$0")/.."
PORT="${PROXYMAN_PORT:-9090}"
export HTTPS_PROXY="http://127.0.0.1:${PORT}"
export HTTP_PROXY="http://127.0.0.1:${PORT}"

python3 - <<'PY'
import json, time, hashlib, ssl, urllib.request, base64, random, string, os
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

TOKEN = "TJSYAPP_TOKEN_ALIAS_c1d3131f-37cd-4bb3-8538-f62eeba3f44c"
key = b"aec0cc61bdf84cb9ab7a8effb50b5640"
iv = b"0cfe3923faecc54a"
proxy = os.environ.get("HTTPS_PROXY", "")
print("HTTPS_PROXY =", proxy or "(未设置)")

payload = {
    "fixmedinsId": "H120101000041",
    "fixmedinsCode": "H12010100004",
    "caty": "000201",
    "inhospDrCodg": "8181",
    "rgstTypeCode": "2",
    "begndate": "2026-10-26",
    "enddate": "2026-11-24",
    "dataSouc": "1",
}
ts = str(int(time.time()))
nc = "".join(random.choices(string.ascii_lowercase + string.digits, k=10))
jyb = hashlib.md5((ts + nc + TOKEN + "jyb4443").encode()).hexdigest()
xt = hashlib.sha256((ts + nc + ts).encode()).hexdigest().upper()
body = base64.b64encode(
    AES.new(key, AES.MODE_CBC, iv).encrypt(
        pad(json.dumps(payload, separators=(",", ":")).encode(), 16)
    )
).decode()
headers = {
    "token": TOKEN,
    "dataSouc": "1",
    "Content-Type": "application/json;charset=UTF-8",
    "jyb-timestamp": ts,
    "jyb-nonce": nc,
    "jyb-signature": jyb,
    "X-Tif-Timestamp": ts,
    "X-Tif-Nonce": nc,
    "X-Tif-Signature": xt,
    "X-Tif-Paasid": "jyt_test",
}
req = urllib.request.Request(
    "https://jyt.ylbz.tj.gov.cn/app-prod/med/dr/quryMultiDrHorn",
    data=body.encode(),
    headers=headers,
    method="POST",
)
raw = urllib.request.urlopen(req, timeout=25, context=ssl.create_default_context()).read().decode().strip()
dec = unpad(AES.new(key, AES.MODE_CBC, iv).decrypt(base64.b64decode(raw)), 16).decode()
print("HTTP OK, 解密响应:", dec[:200])
print("若 Proxyman 仍无记录：检查 macOS Proxy、SSL Proxying 列表、端口", os.environ.get("HTTPS_PROXY"))
PY
