import json, os, pathlib, re
work=pathlib.Path(os.environ['BUILD_WORK'])
catalog=json.loads((work/'simulators.json').read_text())
choices=[]
for runtime, devices in catalog.get('devices',{}).items():
    if '.iOS-' not in runtime: continue
    for device in devices:
        if device.get('isAvailable') and device.get('name','').startswith('iPhone'):
            choices.append({**device,'runtime':runtime})
if not choices: raise RuntimeError('No available iPhone simulator')
choices.sort(key=lambda item:tuple(int(x) for x in re.findall(r'\d+',item['runtime'])),reverse=True)
choice=choices[0]
if not re.fullmatch(r'[0-9A-Fa-f-]{36}',choice['udid']): raise RuntimeError('Invalid simulator identifier')
(work/'simulator-choice.json').write_text(json.dumps({key:choice[key] for key in ('udid','name','runtime')},indent=2)+'\n')
print(choice['udid'])
