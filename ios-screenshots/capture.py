"""Capture production React Native screens selected by the private fixture harness."""
import hashlib
import json
import os
import re
import struct
import subprocess
import time
from pathlib import Path

work = Path(os.environ['BUILD_WORK'])
helpers = Path(os.environ['BUILD_HELPERS'])
release = json.loads((helpers / 'release.json').read_text())
capture = json.loads((work / 'app' / 'capture.json').read_text())
devices = json.loads((work / 'results' / 'simulators.json').read_text())['devices']
scheme = capture['scheme']
if not re.fullmatch(r'[a-z][a-z0-9+-]*', scheme):
    raise ValueError('Invalid capture URL scheme')
screens = capture['screens']
if not 3 <= len(screens) <= 10:
    raise ValueError('Expected 3 to 10 screenshots')

def command(*args):
    subprocess.run(['xcrun', 'simctl', *args], check=True)

def settle(value):
    seconds = float(value)
    if not 1 <= seconds <= 30:
        raise ValueError('Capture settling interval must be between 1 and 30 seconds')
    time.sleep(seconds)

records = []
for device in devices:
    udid = device['udid']
    output = work / 'results' / 'screenshots' / device['group']
    output.mkdir(parents=True, exist_ok=True)
    command('boot', udid)
    command('bootstatus', udid, '-b')
    command('install', udid, os.environ['BUILD_APP'])
    command('status_bar', udid, 'override', '--time', '9:41', '--dataNetwork', 'wifi',
            '--wifiMode', 'active', '--wifiBars', '3', '--batteryState', 'charged', '--batteryLevel', '100')
    command('launch', udid, release['bundleIdentifier'])
    settle(capture.get('initialSettleSeconds', 12))
    container = Path(subprocess.check_output([
        'xcrun', 'simctl', 'get_app_container', udid, release['bundleIdentifier'], 'data'
    ], text=True).strip())
    ready_file = container / 'Documents' / 'capture-ready.json'
    for screen in screens:
        screen_id = screen['id']
        filename = screen['file']
        if not re.fullmatch(r'[a-z0-9-]+', screen_id) or not re.fullmatch(r'[a-zA-Z0-9_-]+\.png', filename):
            raise ValueError('Invalid screenshot identifier or filename')
        destination = output / filename
        if destination.exists():
            raise ValueError('Duplicate screenshot filename')
        previous_revision = None
        if ready_file.exists():
            previous_revision = json.loads(ready_file.read_text()).get('revision')
        command('openurl', udid, f'{scheme}://screen/{screen_id}')
        deadline = time.monotonic() + 30
        while True:
            try:
                ready = json.loads(ready_file.read_text())
            except (FileNotFoundError, json.JSONDecodeError):
                ready = {}
            if (ready.get('screen') == screen_id and ready.get('revision') != previous_revision
                    and ready.get('version') == release['version'] and str(ready.get('build')) == release['build']):
                break
            if time.monotonic() >= deadline:
                raise RuntimeError(f'The native app did not confirm that screen {screen_id} was ready')
            time.sleep(0.25)
        settle(screen.get('settleSeconds', 8))
        command('io', udid, 'screenshot', '--type=png', str(destination))
        raw = destination.read_bytes()
        if raw[:8] != b'\x89PNG\r\n\x1a\n':
            raise ValueError('Simulator did not produce a PNG')
        width, height = struct.unpack('>II', raw[16:24])
        if [width, height] != device['dimensions']:
            raise ValueError(f'Unexpected iPhone screenshot dimensions: {width}x{height}')
        records.append({'id': screen_id, 'file': f'{device["group"]}/{filename}',
                        'width': width, 'height': height, 'sha256': hashlib.sha256(raw).hexdigest()})
    command('shutdown', udid)
(work / 'results' / 'screenshots.json').write_text(json.dumps({
    'version': release['version'], 'build': release['build'], 'screenshots': records,
    'fixtureData': 'Fictitious local demonstration data; no server changes',
    'imageProcessing': 'None. Native simctl PNG screenshots.'
}, ensure_ascii=False, indent=2))
print(f'Captured and dimension-checked {len(records)} native screenshots.')
