"""H5 自动登录 live 验证（路径A：实名+短信）

两步式：
  python3 h5_login.py send  <mob>                 # 1) 发验证码
  python3 h5_login.py login <mob> <vercode> <certno> <psnName>   # 2) 登录

登录成功后打印 token / aesKey / aesIv。
请求体: "04"+SM2hex(C1C3C2, 服务端公钥)
响应体: SM2hex, 用客户端私钥解密
"""
import sys, os, time, random, string, hashlib, json, ssl, urllib.request
import sm2_dec as m

SRV_PUB = (0x650dd64bda01cd29a03c09792080ea855d783c79e774508ed8bf0a1618aed206,
           0x38966f2095bb8e9ff1f083f4cba1ccc60c32a34e95af3e6cf5ef397760470461)
PRIV = "ec7512f9799598e558446c25b9d0c376495ab6dcf57edd602228b15f7ba050"
SALT = "jyb4443"
BASE = "https://jyt.ylbz.tj.gov.cn"
CTX = "/app-prod"   # H5 url 前会拼 suffic.back

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

def call(path, payload_obj, token="", enc="sm2", ua="app"):
    ts = str(int(time.time()))
    nc = nonce()
    jyb = hashlib.md5((ts+nc+token+SALT).encode()).hexdigest()
    xt  = hashlib.sha256((ts+nc+ts).encode()).hexdigest().upper()
    plain = json.dumps(payload_obj, ensure_ascii=False, separators=(',',':'))
    if enc == "none":
        body = plain
    elif enc == "sm2":
        # sm-crypto doEncrypt 输出已含 C1 的 04 前缀，不再叠加
        body = sm2_encrypt(plain, SRV_PUB)
    else:  # sm2double: JS 中 "04"+doEncrypt 的字面行为
        body = "04" + sm2_encrypt(plain, SRV_PUB)
    headers = {
        "token": token,
        "jyb-timestamp": ts, "jyb-nonce": nc, "jyb-signature": jyb,
        "X-Tif-Timestamp": ts, "X-Tif-Nonce": nc, "X-Tif-Signature": xt,
        "X-Tif-Paasid": "jyt_test",
        "dataSouc": "1",
        "Content-Type": "application/json",
        "User-Agent": ("MDLIFE/3.4.9 (iPhone; iOS 26.6; Scale/3.00)" if ua == "app"
                        else "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"),
        "Origin": "https://jyt.ylbz.tj.gov.cn:4443",
        "Referer": "https://jyt.ylbz.tj.gov.cn:4443/",
    }
    req = urllib.request.Request(BASE+CTX+path, data=body.encode(), headers=headers, method="POST")
    ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
    try:
        r = urllib.request.urlopen(req, timeout=30, context=ctx)
        raw = r.read().decode(errors="replace"); code=r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace"); code=e.code
    except Exception as e:
        print(f"请求异常: {e}"); sys.exit(1)
    print(f"[{path}] HTTP {code}, 响应长度 {len(raw)}")
    cand = raw.strip()
    if cand.startswith('"'): cand = cand.strip('"')
    if cand.startswith("04"):
        pt, ok = m.sm2_decrypt(cand, PRIV, 1)
        print(f"SM2 解密成功 (C3校验:{ok})")
        return code, pt.decode(errors="replace")
    print(f"响应非 SM2 密文: {raw[:200]!r}")
    return code, raw

if __name__ == "__main__":
    act = sys.argv[1] if len(sys.argv) > 1 else ""
    if act == "send":
        mob = sys.argv[2]
        enc = sys.argv[3] if len(sys.argv) > 3 else "sm2"
        ua = sys.argv[4] if len(sys.argv) > 4 else "app"
        code, text = call("/app/sendRegVerCode", {"mob": mob, "dataSouc": "1"}, enc=enc, ua=ua)
        print("发码响应:", text[:500])
    elif act == "login":
        mob, vercode, certno, psnName = sys.argv[2:6]
        payload = {"certno": certno, "psnName": psnName, "lgnType": "1",
                   "mob": mob, "vercode": vercode,
                   "isFace": "1", "isReg": "0", "devNo": "111",
                   "dataSouc": "1"}
        code, text = call("/asm/lgn", payload)
        print("登录响应:", text[:800])
        try:
            obj = json.loads(text)
            data = obj.get("data") if isinstance(obj.get("data"), dict) else obj
            print("---- 会话凭据 ----")
            for k in ("token","aesKey","aesIv"):
                if isinstance(data, dict) and data.get(k):
                    print(f"{k} = {data[k]}")
        except Exception as e:
            print("响应非 JSON:", e)
    else:
        print(__doc__)
