import re
s = open('/Users/willem/IdeaProjects/fetch-info/reverse/h5js/index.js', encoding='utf-8', errors='replace').read()

# aesKey/aesIv 的所有读取来源
print("=== aesIv 出现的上下文（看从哪读取）===")
for m in re.finditer(r'aesIv', s):
    ctx = s[m.start()-70:m.start()+40]
    # 只挑读取/URL/query相关
    if any(k in ctx for k in ['query','Query','options','getStorage','URL','url','search','location','getParam','route']):
        print(repr(ctx)); print("---")

print()
print("=== 从 URL/query 读 token 或 key 的代码 ===")
for m in re.finditer(r'(getQueryString|location\.search|options\.[a-zA-Z]*[Kk]ey|query\.[a-zA-Z]*[Kk]ey|decodeURI\([^)]*[Kk]ey)', s):
    print(repr(s[m.start()-60:m.start()+60])); print("---")
