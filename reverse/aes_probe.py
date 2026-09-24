"""纯 Python AES-128 单块解密 + aesKey 派生碰撞测试"""
import base64, hashlib, os

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
def xtime(a): return ((a<<1)^0x1b)&0xff if a&0x80 else (a<<1)&0xff
def mul(a,b):
    r=0
    for _ in range(8):
        if b&1: r^=a
        b>>=1; a=xtime(a)
    return r
def inv_sbox():
    t=[0]*256
    for i,v in enumerate(SBOX): t[v]=i
    return t
INV=inv_sbox()
def key_exp(k):
    w=[list(k[i:i+4]) for i in range(0,16,4)]
    for i in range(4,44):
        t=w[i-1][:]
        if i%4==0:
            t=t[1:]+t[:1]; t=[SBOX[x] for x in t]; t[0]^=RCON[i//4-1]
        w.append([w[i-4][j]^t[j] for j in range(4)])
    rk=[]
    for r in range(11):
        rr=[]
        for c in range(4):
            rr+=w[4*r+c]
        rk.append(rr)
    return rk
def dec_block(blk,rk):
    s=[blk[r+4*c] for c in range(4) for r in range(4)]  # 列主序
    def addkey(rr):
        for c in range(4):
            for r in range(4):
                s[c*4+r]^=rk[rr][c*4+r]
    addkey(10)
    for rnd in range(9,0,-1):
        # InvShiftRows
        s=[s[(c- r)%4*4+r] if False else s[((c-r)%4)*4+r] for c in range(4) for r in range(4)]
        s=[INV[x] for x in s]  # InvSubBytes
        addkey(rnd)
        # InvMixColumns
        for c in range(4):
            a=s[c*4:c*4+4]
            s[c*4+0]=mul(a[0],14)^mul(a[1],11)^mul(a[2],13)^mul(a[3],9)
            s[c*4+1]=mul(a[0],9)^mul(a[1],14)^mul(a[2],11)^mul(a[3],13)
            s[c*4+2]=mul(a[0],13)^mul(a[1],9)^mul(a[2],14)^mul(a[3],11)
            s[c*4+3]=mul(a[0],11)^mul(a[1],13)^mul(a[2],9)^mul(a[3],14)
    s=[s[((c-1)%4)*4+r] for c in range(4) for r in range(4)]
    s=[INV[x] for x in s]
    addkey(0)
    out=[0]*16
    for c in range(4):
        for r in range(4):
            out[r+4*c]=s[c*4+r]
    return bytes(out)

resp_path="/Users/willem/Desktop/Raw_08-14-2026-13-06-26.folder"
resp_file=[f for f in os.listdir(resp_path) if f.startswith("[2531] Response")][0]
txt=open(os.path.join(resp_path,resp_file),encoding="utf-8",errors="replace").read()
ct=base64.b64decode(txt.split("\n\n",1)[1].strip())
c0,c1=ct[:16],ct[16:32]
pt_prefix=b'{"code":"0","mes'

def try_pair(key,iv,label):
    rk=key_exp(key)
    p1=dec_block(c1,rk)  # P1 = D(C1) xor C0? 不对，CBC: P1 = D(C1) ^ C0
    pt1=bytes(a^b for a,b in zip(dec_block(c1,rk),c0))
    if pt1.startswith(pt_prefix[:16]):
        print(f"!!! 命中 {label}: key={key.hex()} iv={iv.hex()}")
        return True
    # 首块验证需要 iv: P0 = D(C0) ^ iv
    pt0=bytes(a^b for a,b in zip(dec_block(c0,rk),iv))
    if pt0.startswith(pt_prefix):
        print(f"!!! 命中(首块) {label}: key={key.hex()} iv={iv.hex()}")
        return True
    return False

tok_full="TJSYAPP_TOKEN_ALIAS_3065cec1-d630-485d-a36b-7cab091b8e98"
tok_uuid="3065cec1-d630-485d-a36b-7cab091b8e98"
bases=[tok_full,tok_uuid,"jyb4443","jyt-ui-prod","MDLIFE","1234567890123456","admin","1786673842"]
hit=False
for b in bases:
    bb=b.encode()
    cands=[(bb.ljust(16,b'0')[:16],bb.ljust(16,b'0')[:16]),
           (hashlib.md5(bb).digest(),hashlib.md5(bb).digest()),
           (hashlib.sha256(bb).digest()[:16],hashlib.sha256(bb).digest()[16:]),
           (hashlib.md5(bb+b"jyb4443").digest(),hashlib.md5(b"jyb4443"+bb).digest())]
    for k,iv in cands:
        if len(k)==16 and len(iv)==16 and try_pair(k,iv,f"derive({b[:14]})"): hit=True
print("结论:", "命中，可离线派生！" if hit else "无法离线派生 → aesKey/aesIv 是服务端会话级随机值，必须从 APP 会话中获取")
