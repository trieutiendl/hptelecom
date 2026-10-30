import pathlib, re
choices=[]
for app in pathlib.Path('/Applications').glob('Xcode*.app'):
    directory=app/'Contents/Developer'
    if (directory/'usr/bin/xcodebuild').is_file():
        choices.append((tuple(int(x) for x in re.findall(r'\d+',app.name)),directory))
if not choices: raise RuntimeError('No installed Xcode is available')
print(sorted(choices,key=lambda item:item[0])[-1][1])
