s = open('/Users/willem/IdeaProjects/fetch-info/reverse/h5js/index.js', encoding='utf-8', errors='replace').read()
i = s.find('t.query.aesKey')
print(s[i-700:i+500])
