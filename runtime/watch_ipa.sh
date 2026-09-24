#!/bin/zsh
WATCH="/Users/willem/Library/Group Containers/K36BKF7T3D.group.com.apple.configurator/Library/Caches/Assets/TemporaryItems/MobileApps"
OUT="/Users/willem/Desktop/ipa-captured"
mkdir -p "$OUT"
echo "[$(date '+%H:%M:%S')] 开始监听: $WATCH"
SEEN=""
for i in $(seq 1 900); do   # 最多监听 30 分钟 (900 * 2s)
  # 扫描 .ipa 以及 Configurator 下载时的临时包
  FOUND=$(ls -1 "$WATCH" 2>/dev/null)
  if [ -n "$FOUND" ]; then
    echo "[$(date '+%H:%M:%S')] 发现文件:"
    ls -la "$WATCH"
    # 拷贝所有文件到输出目录
    for f in "$WATCH"/*; do
      [ -e "$f" ] || continue
      cp -p "$f" "$OUT"/ 2>/dev/null && echo "[$(date '+%H:%M:%S')] 已拷贝: $(basename "$f")"
    done
    SEEN="yes"
    # 若已有 .ipa 则提前结束
    if ls "$OUT"/*.ipa >/dev/null 2>&1; then
      echo "[$(date '+%H:%M:%S')] 已捕获 .ipa，监听结束"
      break
    fi
  fi
  sleep 2
done
echo "[$(date '+%H:%M:%S')] === 最终捕获结果 ==="
ls -la "$OUT" 2>/dev/null
[ -z "$SEEN" ] && echo "全程未发现新文件（可能下载路径不同，或尚未开始下载）"
