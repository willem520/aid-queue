"""从 iMazing 备份中提取津医保会话凭据（token/aesKey/aesIv）。

原理：
  iOS 备份 Manifest.db → AppDomain-com.yinhai.tjjyb → Documents/MDLIFE/PrefsData.plist
  PrefsData 值为 AES-128-ECB 全零密钥加密的 hex 串（MDLIFE SDK 存储层），解密即得明文。

用法：python3 extract_session.py [备份根目录]
  默认自动在 ~/Library/Application Support/iMazing/Backups 下找最新设备目录。
"""
import re
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
    for k in ("token", "aesKey", "aesIv", "userid", "mob", "appVersion", "sm2PublicKey"):
        print(f"{k} = {vals.get(k)}")

    priv_candidates = [
        k for k, v in vals.items()
        if isinstance(v, str) and re.fullmatch(r"[0-9a-fA-F]{64}", v)
    ]
    manifest_path = os.path.join(root, "Manifest.plist")
    is_encrypted = None
    if os.path.exists(manifest_path):
        with open(manifest_path, "rb") as f:
            is_encrypted = plistlib.load(f).get("IsEncrypted")
    print(f"\n备份 Manifest IsEncrypted = {is_encrypted!r} （false 表示未设「加密备份」密码）")

    kc_row = conn.execute(
        "SELECT fileID FROM Files WHERE domain=? AND relativePath=?",
        ("KeychainDomain", "keychain-backup.plist"),
    ).fetchone()
    kc_hits = 0
    if kc_row:
        kc_path = os.path.join(root, kc_row[0][:2], kc_row[0])
        with open(kc_path, "rb") as f:
            kc = plistlib.load(f)
        needles = (b"yinhai", b"tjjyb", b"MDLIFE", b"com.yinhai")
        for item in kc.get("genp", []):
            data = item.get("v_Data", b"")
            if isinstance(data, bytes) and any(n in data for n in needles):
                kc_hits += 1
        print(
            f"Keychain 备份: genp 条目 {len(kc.get('genp', []))} 个，"
            f"明文含 yinhai/tjjyb 的 v_Data: {kc_hits} 个"
        )
    else:
        print("Keychain 备份: 本备份无 keychain-backup.plist")

    print("\n--- SM2 私钥 ---")
    if priv_candidates:
        print("Prefs 中疑似 64hex 私钥字段:", ", ".join(priv_candidates))
    else:
        print("Prefs 中无 64 位 hex 私钥（仅有 sm2PublicKey 公钥）。")
    print(
        "Keychain 里即使有津医保条目，v_Data 也是系统加密 blob，"
        "需设备 KeyBag + 常见「加密备份」密码才能解；且 EC 私钥常被标记为不可导出。"
    )
    print("结论: 设备私钥不需要——号源接口 quryOtpHorn 用的是 H5 bundle 内置的静态 SM2 密钥对，")
    print("      见 reverse/h5js/index.js 的 00ec75…05002，直接填进 application.yml 的 session.sm2-private-key。")

if __name__ == "__main__":
    main()
