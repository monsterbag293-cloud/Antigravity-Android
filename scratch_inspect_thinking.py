import zipfile, re

zf = zipfile.ZipFile('app/src/main/assets/jetski-bundle.zip')
js = zf.read('main.js').decode('utf-8', 'ignore')

matches = [m.start() for m in re.finditer(r'thinking', js, re.IGNORECASE)]
print('Matches for thinking:', len(matches))

# Let's search for "Thinking" or model thought level UI strings
keywords = ['thought', 'thinking', 'budget', 'modelThinking', 'reasoning', 'effort']
for kw in keywords:
    m = re.findall(rf'\b[a-zA-Z0-9_]*{kw}[a-zA-Z0-9_]*\b', js, re.IGNORECASE)
    print(kw, len(m), set(m[:10]))
