"""Create private medium and large iPhones, using known native pixel dimensions."""
import json
import os
import subprocess
from pathlib import Path

work = Path(os.environ['BUILD_WORK'])

def listing(kind):
    return json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', kind, '--json'], timeout=30))

runtimes = [r for r in listing('runtimes')['runtimes']
            if r.get('isAvailable') and r['identifier'].startswith('com.apple.CoreSimulator.SimRuntime.iOS-')]
runtimes.sort(key=lambda r: tuple(int(p) for p in r['version'].split('.')), reverse=True)
types = {t['name']: t['identifier'] for t in listing('devicetypes')['devicetypes']}
# The currently required App Store group accepts 1206x2622 or 1179x2556.
# Avoid inferring screen dimensions from an unfamiliar model name.
groups = [
    ('iphone-medium', [('iPhone 16 Pro', [1206, 2622]), ('iPhone 15 Pro', [1179, 2556]),
                       ('iPhone 14 Pro', [1179, 2556])], True),
    ('iphone-large', [('iPhone 16 Pro Max', [1320, 2868])], False),
]
selected = []
attempts = []
for group, models, required in groups:
    chosen = None
    for runtime in runtimes:
        for name, dimensions in models:
            if name not in types:
                continue
            try:
                result = subprocess.run([
                    'xcrun', 'simctl', 'create', f'HP Telecom Review 47 {group}', types[name], runtime['identifier']
                ], text=True, capture_output=True, timeout=45)
                attempt = {'name': name, 'runtime': runtime['version'],
                           'returncode': result.returncode, 'stderr': result.stderr}
            except subprocess.TimeoutExpired:
                result = None
                attempt = {'name': name, 'runtime': runtime['version'], 'timedOut': True}
            attempts.append(attempt)
            (work / 'results' / 'simulator-selection.json').write_text(json.dumps(attempts, indent=2))
            if result is None:
                continue
            if result.returncode == 0:
                chosen = {'group': group, 'name': name, 'dimensions': dimensions,
                          'runtime': runtime['version'], 'deviceType': types[name],
                          'udid': result.stdout.strip()}
                break
        if chosen:
            break
    if chosen:
        selected.append(chosen)
        # Persist immediately so cleanup can delete a created device even after a later failure.
        (work / 'simulators.txt').write_text(''.join(d['udid'] + '\n' for d in selected))
    elif required:
        raise RuntimeError('No compatible iPhone simulator for the required screenshot group')
(work / 'simulator.txt').write_text(selected[0]['udid'])
(work / 'results' / 'simulators.json').write_text(json.dumps({
    'devices': selected, 'captureKind': 'native iOS simulator, unsigned Release configuration'
}, indent=2))
print(f'Created {len(selected)} isolated iPhone simulators.')
