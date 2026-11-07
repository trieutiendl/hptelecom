import json, os, pathlib, re
work=pathlib.Path(os.environ['BUILD_WORK'])
catalog=json.loads((work/'simulators.json').read_text())
choices=[]
for runtime, devices in catalog.get('devices',{}).items():
    if '.iOS-' not in runtime: continue
    for device in devices:
        if device.get('isAvailable'):
            choices.append({**device,'runtime':runtime})
selected=[]
for family in ['iPhone','iPad']:
    candidates=[d for d in choices if d.get('name','').startswith(family)]
    if not candidates: raise RuntimeError('No available '+family+' simulator')
    def priority(d):
        large = 'Pro Max' in d['name'] if family=='iPhone' else '13-inch' in d['name'] or '12.9-inch' in d['name']
        return tuple(int(n) for n in re.findall(r'\d+',d['runtime'])),large,d['name']
    choice=sorted(candidates,key=priority,reverse=True)[0]
    if not re.fullmatch(r'[0-9A-Fa-f-]{36}',choice['udid']): raise RuntimeError('Invalid simulator identifier')
    selected.append({k:choice[k] for k in ['udid','name','runtime']}|{'family':family})
(work/'simulator-choices.json').write_text(json.dumps(selected,indent=2)+'\n')
for choice in selected: print(choice['family']+' '+choice['udid'])
