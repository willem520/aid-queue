"""SM2 解密实现（sm2p256v1），用于解密津医保登录响应"""

# SM2 曲线参数 (sm2p256v1)
P  = 0xFFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF
A  = 0xFFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC
B  = 0x28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93
N  = 0xFFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123
GX = 0x32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7
GY = 0xBC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0

def inv_mod(a, m):
    return pow(a, -1, m)

def pt_add(p1, p2):
    if p1 is None: return p2
    if p2 is None: return p1
    x1,y1=p1; x2,y2=p2
    if x1==x2 and (y1+y2)%P==0: return None
    if p1==p2:
        lam=(3*x1*x1+A)*inv_mod(2*y1,P)%P
    else:
        lam=(y2-y1)*inv_mod(x2-x1,P)%P
    x3=(lam*lam-x1-x2)%P
    y3=(lam*(x1-x3)-y1)%P
    return (x3,y3)

def pt_mul(k, p):
    r=None; add=p
    while k>0:
        if k&1: r=pt_add(r,add)
        add=pt_add(add,add); k>>=1
    return r

# ---- SM3 ----
def rotl(x,n):
    n%=32
    return ((x<<n)|(x>>(32-n)))&0xffffffff if n else x&0xffffffff

def sm3(msg):
    if isinstance(msg,str): msg=msg.encode()
    msg=bytearray(msg)
    l=len(msg)*8
    msg.append(0x80)
    while len(msg)%64!=56: msg.append(0)
    msg+=l.to_bytes(8,'big')
    def p0(x): return x^rotl(x,9)^rotl(x,17)
    def p1(x): return x^rotl(x,15)^rotl(x,23)
    V=[0x7380166f,0x4914b2b9,0x172442d7,0xda8a0600,0xa96f30bc,0x163138aa,0xe38dee4d,0xb0fb0e4e]
    for off in range(0,len(msg),64):
        B=msg[off:off+64]
        W=[int.from_bytes(B[i*4:i*4+4],'big') for i in range(16)]
        for j in range(16,68):
            W.append((p1(W[j-16]^W[j-9]^rotl(W[j-3],15))^rotl(W[j-13],7)^W[j-6])&0xffffffff)
        Wp=[W[j]^W[j+4] for j in range(64)]
        Aa,Bb,Cc,Dd,Ee,Ff,Gg,Hh=V
        for j in range(64):
            T=0x79cc4519 if j<16 else 0x7a879d8a
            SS1=rotl((rotl(Aa,12)+Ee+rotl(T,j%32))&0xffffffff,7)
            SS2=SS1^rotl(Aa,12)
            FF=(Aa^Bb^Cc) if j<16 else ((Aa&Bb)|(Aa&Cc)|(Bb&Cc))
            GG=(Ee^Ff^Gg) if j<16 else ((Ee&Ff)|((~Ee&0xffffffff)&Gg))
            TT1=(FF+Dd+SS2+Wp[j])&0xffffffff
            TT2=(GG+Hh+SS1+W[j])&0xffffffff
            Dd=Cc; Cc=rotl(Bb,9); Bb=Aa; Aa=TT1
            Hh=Gg; Gg=rotl(Ff,19); Ff=Ee; Ee=p0(TT2)
        V=[x^y for x,y in zip(V,[Aa,Bb,Cc,Dd,Ee,Ff,Gg,Hh])]
    return b''.join(x.to_bytes(4,'big') for x in V)

def kdf(z, klen):
    ct=1; out=b''
    while len(out)<klen:
        out+=sm3(z+ct.to_bytes(4,'big')); ct+=1
    return out[:klen]

def sm2_decrypt(cipher_hex, d_hex, mode=1):
    data=bytes.fromhex(cipher_hex)
    if data[0]==0x04:
        x1=int.from_bytes(data[1:33],'big'); y1=int.from_bytes(data[33:65],'big')
        rest=data[65:]
    else:
        x1=int.from_bytes(data[0:32],'big'); y1=int.from_bytes(data[32:64],'big')
        rest=data[64:]
    if mode==1:  # C1C3C2
        C3=rest[:32]; C2=rest[32:]
    else:        # C1C2C3
        C2=rest[:-32]; C3=rest[-32:]
    d=int(d_hex,16)
    x2,y2=pt_mul(d,(x1,y1))
    x2b=x2.to_bytes(32,'big'); y2b=y2.to_bytes(32,'big')
    t=kdf(x2b+y2b, len(C2))
    M=bytes(a^b for a,b in zip(C2,t))
    u=sm3(x2b+M+y2b)
    return M, u==C3

if __name__=='__main__':
    print('SM3自检:', sm3(b'abc').hex())
    print('期望值 : 66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0')
