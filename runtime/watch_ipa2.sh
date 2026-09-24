#!/bin/zsh
WATCH="/Users/willem/Library/Group Containers/K36BKF7T3D.group.com.apple.configurator/Library/Caches/Assets/TemporaryItems"
OUT="/Users/willem/Desktop/ipa-captured"
mkdir -p "$OUT"
echo "[$(date '+%H:%M:%S')] 递归监听: $WATCH"
for i in $(seq 1 900); do
  # 递归找任何 ipa / 大文件(>1MB, 疑似下载中的包)
  find "$WATCH" -type f \( -name "*.ipa" -o -size +1M \) 2>/dev/null | while read -r f; do
    b=$(basename "$f")
    if [ ! -e "$OUT/$b" ]; then
      cp -p "$f" "$OUT/$b" 2>/dev/null && echo "[$(date '+%H:%M:%S')] 捕获: $b ($(du -h "$OUT/$b" | cut -f1))"
    fi
  done
  sleep 1
done
