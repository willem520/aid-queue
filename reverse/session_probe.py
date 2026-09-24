"""探测：带 cryptoType:2 头但不带 dataKey/dataIv，body 用现有 token 对应的会话 AES 加密。
假设：网关按 token 查服务端会话取 aesKey/aesIv 解密 body（13-06-26 真实 WebView 请求无 dataKey 头，正是此模式）。
用 quryDisabledInfo（只读安全接口）探测。
"""
import time, random, string, hashlib, json, ssl, urllib.request, base64, sys
from envelope_probe import aes_cbc_encrypt

TOKEN = "TJSYAPP_TOKEN_ALIAS_3065cec1-d630-485d-a36b-7cab091b8e98"
BASE = "https://jyt.ylbz.tj.gov.cn/app-prod"

def nonce(): return ''.join(random.choices(string.ascii_lowercase+string.digits, k=10))

def probe(path, payload, key, iv, crypto_type):
    ts = str(int(time.time())); nc = nonce()
    jyb = hashlib.md5((ts+nc+TOKEN+"jyb4443").encode()).hexdigest()
    xt  = hashlib.sha256((ts+nc+ts).encode()).hexdigest().upper()
    plain = json.dumps(payload, ensure_ascii=False, separators=(',',':')).encode()
    body = base64.b64encode(aes_cbc_encrypt(plain, key, iv)).decode()
    headers = {
        "token": TOKEN, "cryptoType": crypto_type,
        "jyb-timestamp": ts, "jyb-nonce": nc, "jyb-signature": jyb,
        "X-Tif-Timestamp": ts, "X-Tif-Nonce": nc, "X-Tif-Signature": xt,
        "X-Tif-Paasid": "jyt_test", "dataSouc": "1",
        "Content-Type": "application/json;charset=UTF-8", "charset": "utf-8",
        "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MDLIFE/2.0",
        "Origin": "https://jyt.ylbz.tj.gov.cn:4443",
        "Referer": "https://jyt.ylbz.tj.gov.cn:4443/",
    }
    req = urllib.request.Request(BASE+path, data=body.encode(), headers=headers, method="POST")
    ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
    try:
        r = urllib.request.urlopen(req, timeout=25, context=ctx); raw = r.read().decode(errors='replace'); code = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors='replace'); code = e.code
    print(f"key={key.decode(errors='replace')[:10]}… iv={iv.decode(errors='replace')[:10]}… cryptoType={crypto_type} → HTTP {code}, 前缀 {raw[:80]!r}")
    return code, raw

if __name__ == "__main__":
    k, iv, ct = sys.argv[1].encode(), sys.argv[2].encode(), sys.argv[3] if len(sys.argv) > 3 else "2"
    probe("/med/otp/comm/quryDisabledInfo", {"dataSouc": "1"}, k, iv, ct)
