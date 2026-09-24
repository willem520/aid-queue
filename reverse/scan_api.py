import re
s = open('/Users/willem/IdeaProjects/fetch-info/reverse/h5js/index.js', encoding='utf-8', errors='replace').read()

print("=== /app/lgn 函数上下文 ===")
i = s.find('url:"/app/lgn"')
print(s[i-500:i+320])
print()

print("=== 号源/预约/挂号相关 url ===")
urls = sorted(set(re.findall(r'url:\s*"(/med/[^"]*)"', s)))
for u in urls:
    if any(k in u for k in ['otp','ordr','orn','ook','rgst','detl']):
        print("  ", u)
print()

print("=== 可能凭 token 换 aesKey 的端点 ===")
allurls = sorted(set(re.findall(r'url:\s*"([^"]+)"', s)))
for u in allurls:
    if any(k in u.lower() for k in ['key','token','lgn','refresh','init','secret','pwd']):
        print("  ", u)
