import re
s = open('/Users/willem/IdeaProjects/fetch-info/reverse/h5js/index.js', encoding='utf-8', errors='replace').read()

print("=== tokenObject 在哪里被写入 (set) ===")
for m in re.finditer(r'set\)\(\s*"tokenObject"', s):
    print(s[m.start()-600:m.start()+150]); print("---\n")

print("=== execScript / toLgin 桥调用 ===")
for m in re.finditer(r'toLgin', s):
    print(repr(s[m.start()-200:m.start()+120])); print("---")
