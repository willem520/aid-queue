"""cryptoType:2 信封方案 live 探测：
客户端自生成随机 AES key/iv → SM2(服务端公钥)加密放 dataKey/dataIv 头 → AES-CBC 加密 body。
若服务端接受（响应用我方 AES 密钥可解），则预约完全不需要登录会话密钥。
"""
import os, time, random, string, hashlib, json, ssl, urllib.request, base64
import sm2_dec as m
from h5_login import sm2_encrypt, nonce as mk_nonce

SRV_PUB = (0x650dd64bda01cd29a03c09792080ea855d783c79e774508ed8bf0a1618aed206,
           0x38966f2095bb8e9ff1f083f4cba1ccc60c32a34e95af3e6cf5ef397760470461)
TOKEN = "TJSYAPP_TOKEN_ALIAS_3065cec1-d630-485d-a36b-7cab091b8e98"
BASE = "https://jyt.ylbz.tj.gov.cn/app-prod"

# ---- 纯 Python AES-128 CBC/PKCS7 ----
SBOX = bytes.fromhex(
    "637c777bf26b6fc53001672bfed7ab76ca82c97dfa5947f0add4a2af9ca472c0"
    "b7fd9326363ff7cc34a5e5f171d8311504c723c31896059a071280e2eb27b275"
    "09832c1a1b6e5aa0523bd6b329e32f8453d100ed20fcb15b6acbbe394a4c58cf"
    "d0efaafb434d338545f9027f503c9fa851a3408f929d38f5bcb6da2110fff3d2"
    "cd0c13ec5f974417c4a77e3d645d197360814fdc222a908846eeb814de5e0bdb"
    "e0323a0a4906245cc2d3ac629195e479e7c8376d8dd54ea96c56f4ea657aae08"
    "ba78252e1ca6b4c6e8dd741f4bbd8b8a703eb5664803f60e613557b986c11d9e"
    "e1f8981169d98e949b1e87e9ce5528df8ca1890dbfe6426841992d0fb054bb16")
RCON = [0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36]
def _xtime(a): return ((a<<1)^0x1b)&0xff if a&0x80 else (a<<1)&0xff
def _mix_col(c):
    t=c[0]^c[1]^c[2]^c[3]
    return bytes([c[0]^t^_xtime(c[0]^c[1]), c[1]^t^_xtime(c[1]^c[2]),
                  c[2]^t^_xtime(c[2]^c[3]), c[3]^t^_xtime(c[3]^c[0])])
def _key_exp(key):
    w=[list(key[i:i+4]) for i in range(0,16,4)]
    for i in range(4,44):
        t=w[i-1][:]
        if i%4==0:
            t=t[1:]+t[:1]; t=[SBOX[b] for b in t]; t[0]^=RCON[i//4-1]
        w.append([w[i-4][j]^t[j] for j in range(4)])
    rk=[]
    for r in range(11):
        rk.append(sum([w[4*r+c] for c in range(4)],[]))
    return rk
def _enc_block(blk, rk):
    s=[blk[r+4*c] for c in range(4) for r in range(4)]  # 列主序
    s=[s[i]^rk[0][i] for i in range(16)]
    for rnd in range(1,11):
        # SubBytes
        s=[SBOX[b] for b in s]
        # ShiftRows（列主序存储 s[c*4+r]：第 r 行左移 r 位）
        s=[s[4*((c+r)%4)+r] for c in range(4) for r in range(4)]
        if rnd<10:
            s2=[]
            for c in range(4):
                s2 += list(_mix_col(s[4*c:4*c+4]))
            s=s2
        s=[b^rk[rnd][i] for i,b in enumerate(s)]
    return bytes(s[r+4*c] for c in range(4) for r in range(4))
def aes_cbc_encrypt(data: bytes, key: bytes, iv: bytes) -> bytes:
    pad = 16-len(data)%16
    data += bytes([pad])*pad
    rk=_key_exp(key)
    out=b''; prev=iv
    for i in range(0,len(data),16):
        blk=bytes(a^b for a,b in zip(data[i:i+16],prev))
        enc=_enc_block(blk,rk)
        out+=enc; prev=enc
    return out
def aes_cbc_decrypt(data: bytes, key: bytes, iv: bytes) -> bytes:
    # 解密用正向表逆向太繁，这里只做探测：尝试用加密器验证回环即可
    raise NotImplementedError

def probe(path, payload_obj, token=TOKEN):
    ts=str(int(time.time())); nc=mk_nonce()
    jyb=hashlib.md5((ts+nc+token+"jyb4443").encode()).hexdigest()
    xt=hashlib.sha256((ts+nc+ts).encode()).hexdigest().upper()
    akey=os.urandom(16); aiv=os.urandom(16)
    plain=json.dumps(payload_obj,ensure_ascii=False,separators=(',',':')).encode()
    body=base64.b64encode(aes_cbc_encrypt(plain,akey,aiv)).decode()
    headers={
        "token":token, "cryptoType":"2",
        "dataKey": sm2_encrypt(akey, SRV_PUB),
        "dataIv":  sm2_encrypt(aiv, SRV_PUB),
        "jyb-timestamp":ts,"jyb-nonce":nc,"jyb-signature":jyb,
        "X-Tif-Timestamp":ts,"X-Tif-Nonce":nc,"X-Tif-Signature":xt,
        "X-Tif-Paasid":"jyt_test","dataSouc":"1",
        "Content-Type":"application/json;charset=UTF-8",
        "User-Agent":"MDLIFE/3.4.9 (iPhone; iOS 26.6; Scale/3.00)",
        "charset":"utf-8",
    }
    req=urllib.request.Request(BASE+path,data=body.encode(),headers=headers,method="POST")
    ctx=ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
    try:
        r=urllib.request.urlopen(req,timeout=25,context=ctx); raw=r.read().decode(errors='replace'); code=r.status
    except urllib.error.HTTPError as e:
        raw=e.read().decode(errors='replace'); code=e.code
    print(f"[{path}] HTTP {code}, 响应长度 {len(raw)}, 前缀 {raw[:40]!r}")
    return code, raw, akey, aiv

if __name__=="__main__":
    # NIST SP800-38A AES-128-CBC 标准向量自测
    k=bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
    iv=bytes.fromhex("000102030405060708090a0b0c0d0e0f")
    pt=bytes.fromhex("6bc1bee22e409f96e93d7e117393172a")
    expect="7649abac8119b246cee98e9b12e9197d"
    got=aes_cbc_encrypt(pt,k,iv)[:16].hex()
    print("AES NIST 自测:", "通过" if got==expect else f"失败 got={got} expect={expect}")
    assert got==expect
    print("== 探测: quryDisabledInfo (只读查询,安全) ==")
    code,raw,akey,aiv=probe("/med/otp/comm/quryDisabledInfo",{"dataSouc":"1"})
    # 响应若是 base64 AES，尝试保存密钥供后续解密
    print("本次 AES 密钥(hex):",akey.hex(),"IV:",aiv.hex())
    open('last_envelope_key.txt','w').write(akey.hex()+"\n"+aiv.hex()+"\n"+raw)
