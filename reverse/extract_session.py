"""从 iMazing 备份中提取津医保会话凭据（token/aesKey/aesIv）。

原理：
  iOS 备份 Manifest.db → AppDomain-com.yinhai.tjjyb → Documents/MDLIFE/PrefsData.plist
  PrefsData 值为 AES-128-ECB 全零密钥加密的 hex 串（MDLIFE SDK 存储层），解密即得明文。

用法：python3 extract_session.py [备份根目录]
  默认自动在 ~/Library/Application Support/iMazing/Backups 下找最新设备目录。
"""
import sys, os, sqlite3, plistlib, subprocess, glob

HOME = os.path.expanduser("~/Library/Application Support/iMazing/Backups")
DOMAIN = "AppDomain-com.yinhai.tjjyb"

def find_backup(argv):
    if len(argv) > 1: return argv[1]
    devs = [d for d in glob.glob(os.path.join(HOME, "*")) if os.path.isdir(d)]
    if not devs: sys.exit("未找到 iMazing 备份目录")
    return max(devs, key=os.path.getmtime)

def decrypt_prefs_value(v):
    """AES-128-ECB(key=16个0x00) 解密 hex 串，去 PKCS7。"""
    if not isinstance(v, str) or len(v) < 32 or len(v) % 32: return v
    try: data = bytes.fromhex(v)
    except ValueError: return v
    p = subprocess.run(["openssl", "enc", "-d", "-aes-128-ecb", "-K", "00" * 16],
                       input=data, capture_output=True)
    if p.returncode: return v
    out = p.stdout
    if out and 1 <= out[-1] <= 16 and out.endswith(bytes([out[-1]]) * out[-1]):
        out = out[:-out[-1]]
    try: return out.decode("utf-8")
    except UnicodeDecodeError: return v

def main():
    root = find_backup(sys.argv)
    db = os.path.join(root, "Manifest.db")
    if not os.path.exists(db): sys.exit(f"备份无 Manifest.db: {root}")
    print(f"备份目录: {root}")
    conn = sqlite3.connect(db)
    row = conn.execute("SELECT fileID FROM Files WHERE domain=? AND relativePath=?",
                       (DOMAIN, "Documents/MDLIFE/PrefsData.plist")).fetchone()
    if not row: sys.exit("备份中未找到 PrefsData.plist（津医保未安装或备份不含应用数据）")
    fid = row[0]
    blob_path = os.path.join(root, fid[:2], fid)
    with open(blob_path, "rb") as f:
        prefs = plistlib.load(f)
    vals = {k: decrypt_prefs_value(v) for k, v in prefs.items()}
    for k in ("token", "aesKey", "aesIv", "userid", "mob", "appVersion"):
        print(f"{k} = {vals.get(k)}")

if __name__ == "__main__":
    main()
