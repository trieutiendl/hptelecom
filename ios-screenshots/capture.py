"""Capture production native screens; keep all detailed diagnostics encrypted."""
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import time
import traceback
import uuid
from pathlib import Path

work = Path(os.environ['BUILD_WORK'])
helpers = Path(os.environ['BUILD_HELPERS'])
results = work / 'results'
diagnostics = results / 'capture-diagnostics'
diagnostics.mkdir(parents=True, exist_ok=True)
release = json.loads((helpers / 'release.json').read_text())
capture = json.loads((work / 'app' / 'capture.json').read_text())
devices = json.loads((results / 'simulators.json').read_text())['devices']
requested_groups = capture.get('groups')
if requested_groups is not None:
    if (not isinstance(requested_groups, list) or not requested_groups or
            any(not isinstance(group, str) or group not in {'iphone-medium', 'iphone-large'}
                for group in requested_groups) or len(set(requested_groups)) != len(requested_groups)):
        raise ValueError('Invalid requested screenshot device groups')
    available_groups = [device['group'] for device in devices]
    if any(available_groups.count(group) != 1 for group in requested_groups):
        raise ValueError('Requested screenshot device group is missing or duplicated')
    devices = [device for device in devices if device['group'] in requested_groups]
screens = capture['screens']
if not 3 <= len(screens) <= 10:
    raise ValueError('Expected 3 to 10 screenshots')
for screen in screens:
    if (not re.fullmatch(r'[a-z0-9-]+', screen['id']) or
            not re.fullmatch(r'[a-zA-Z0-9_-]+\.png', screen['file'])):
        raise ValueError('Invalid screenshot identifier or filename')
if len({s['file'] for s in screens}) != len(screens):
    raise ValueError('Duplicate screenshot filename')


def progress(message):
    # Only fixed stage names are public. Native output and exceptions stay in
    # results, which build.sh encrypts before the artifact is uploaded.
    print(f'[capture] {message}', flush=True)


def text(value):
    return value.decode('utf-8', errors='replace') if isinstance(value, bytes) else (value or '')


def command(*args, timeout=60, check=True):
    started = time.monotonic()
    record = {'command': ['xcrun', 'simctl', *args], 'timeoutSeconds': timeout}
    try:
        result = subprocess.run(record['command'], capture_output=True, text=True, timeout=timeout)
        record.update(returncode=result.returncode, stdout=result.stdout, stderr=result.stderr)
    except subprocess.TimeoutExpired as error:
        record.update(timedOut=True, stdout=text(error.stdout), stderr=text(error.stderr))
        result = None
    finally:
        record['elapsedSeconds'] = round(time.monotonic() - started, 2)
        with (diagnostics / 'commands.jsonl').open('a') as log:
            log.write(json.dumps(record) + '\n')
    if check and (result is None or result.returncode != 0):
        raise RuntimeError(f'simctl {args[0]} failed; see encrypted commands.jsonl')
    return result.stdout.strip() if result is not None else ''


def settle(value):
    seconds = float(value)
    if not 1 <= seconds <= 30:
        raise ValueError('Capture settling interval must be between 1 and 30 seconds')
    time.sleep(seconds)


def read_ready(path):
    try:
        return json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return {}


def wait_ready(path, screen_id=None, previous_revision=None, timeout=45, nonce=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        ready = read_ready(path)
        if (ready.get('version') == release['version'] and
                str(ready.get('build')) == str(release['build']) and
                ready.get('screen') in {s['id'] for s in screens} and
                (nonce is None or ready.get('nonce') == nonce) and
                (screen_id is None or (ready.get('screen') == screen_id and
                                      ready.get('revision') != previous_revision))):
            return ready
        time.sleep(0.25)
    raise RuntimeError('The native app did not confirm that the requested screen was ready')


def collect_failure(udid, folder, receipt=None):
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'error.txt').write_text(traceback.format_exc())
    command('io', udid, 'screenshot', '--type=png', str(folder / 'failed-screen.png'),
            timeout=20, check=False)
    # Bounded diagnostics also work when launch succeeds but React Native exits
    # before writing its receipt. They are never printed in the public workflow.
    native_log = command('spawn', udid, 'log', 'show', '--last', '3m',
                         '--style', 'compact', timeout=30, check=False)
    (folder / 'native-system.log').write_text(native_log)
    (folder / 'simulator-state.json').write_text(command('list', 'devices', '--json',
                                                        timeout=15, check=False))
    if receipt and receipt.exists():
        shutil.copy2(receipt, folder / 'last-ready.json')
    process_name = Path(os.environ['BUILD_APP']).stem.lower()
    crash_directory = Path.home() / 'Library' / 'Logs' / 'DiagnosticReports'
    if crash_directory.is_dir():
        for path in crash_directory.iterdir():
            if path.is_file() and path.name.lower().startswith(process_name):
                shutil.copy2(path, folder / path.name)


records = []


def save_manifest():
    (results / 'screenshots.json').write_text(json.dumps({
        'version': release['version'], 'build': release['build'], 'screenshots': records,
        'fixtureData': 'Fictitious local demonstration data; no server changes',
        'imageProcessing': 'None. Native simctl PNG screenshots.'
    }, ensure_ascii=False, indent=2))


for device in devices:
    udid = device['udid']
    group = device['group']
    output = results / 'screenshots' / group
    output.mkdir(parents=True, exist_ok=True)
    device_diagnostics = diagnostics / group
    device_diagnostics.mkdir(parents=True, exist_ok=True)
    ready_file = None
    stage = 'boot'
    try:
        progress(f'{group}: booting simulator')
        command('boot', udid, timeout=45)
        command('bootstatus', udid, '-b', timeout=180)
        stage = 'install'
        progress(f'{group}: installing compiled app')
        command('install', udid, os.environ['BUILD_APP'], timeout=120)
        command('status_bar', udid, 'override', '--time', '9:41', '--dataNetwork', 'wifi',
                '--wifiMode', 'active', '--wifiBars', '3', '--batteryState', 'charged',
                '--batteryLevel', '100', timeout=30)
        container = Path(command('get_app_container', udid, release['bundleIdentifier'], 'data', timeout=30))
        ready_file = container / 'Documents' / 'capture-ready.json'
        for screen in screens:
            screen_id = screen['id']
            stage = f'screen-{screen_id}'
            progress(f'{group}: capturing {screen_id}')
            destination = output / screen['file']
            if destination.exists():
                raise ValueError('Screenshot destination already exists')
            # A fresh app process avoids native view/StatusBar recycling between
            # fixture scenes. The same installed app and private local DB remain.
            command('terminate', udid, release['bundleIdentifier'], timeout=20, check=False)
            if ready_file.exists():
                ready_file.unlink()
            # Only the simulator-only fixture reads this local control file.
            # Atomic replacement avoids partial JSON and system URL prompts.
            nonce = uuid.uuid4().hex
            control_file = ready_file.parent / 'capture-command.json'
            control_file.parent.mkdir(parents=True, exist_ok=True)
            temporary_control = ready_file.parent / f'capture-command-{nonce}.tmp'
            temporary_control.write_text(json.dumps({'screen': screen_id, 'nonce': nonce}))
            temporary_control.replace(control_file)
            for attempt in (1, 2):
                progress(f'{group}: launching {screen_id} (attempt {attempt})')
                try:
                    command('launch', f'--stdout={device_diagnostics / (screen_id + "-stdout-" + str(attempt) + ".log")}',
                            f'--stderr={device_diagnostics / (screen_id + "-stderr-" + str(attempt) + ".log")}',
                            udid, release['bundleIdentifier'], timeout=45)
                    settle(capture.get('initialSettleSeconds', 12))
                    wait_ready(ready_file, screen_id, nonce=nonce)
                    break
                except Exception:
                    collect_failure(udid, device_diagnostics / f'{screen_id}-launch-attempt-{attempt}', ready_file)
                    if attempt == 2:
                        raise
                    progress(f'{group}: retrying scene launch once')
                    command('terminate', udid, release['bundleIdentifier'], timeout=20, check=False)
                    if ready_file.exists():
                        ready_file.unlink()
                    time.sleep(3)
            settle(screen.get('settleSeconds', 8))
            # Reapply the real simulator status override after each cold launch.
            command('status_bar', udid, 'override', '--time', '9:41', '--dataNetwork', 'wifi',
                    '--wifiMode', 'active', '--wifiBars', '3', '--batteryState', 'charged',
                    '--batteryLevel', '100', timeout=30)
            time.sleep(1)
            command('io', udid, 'screenshot', '--type=png', str(destination), timeout=30)
            raw = destination.read_bytes()
            if len(raw) < 24 or raw[:8] != b'\x89PNG\r\n\x1a\n':
                raise ValueError('Simulator did not produce a PNG')
            width, height = struct.unpack('>II', raw[16:24])
            if [width, height] != device['dimensions']:
                raise ValueError(f'Unexpected iPhone screenshot dimensions: {width}x{height}')
            records.append({'id': screen_id, 'file': f'{group}/{screen["file"]}',
                            'width': width, 'height': height, 'sha256': hashlib.sha256(raw).hexdigest()})
            save_manifest()
        progress(f'{group}: screenshot dimensions verified')
    except Exception:
        progress(f'{group}: capture failed at {stage}; preserving encrypted diagnostics')
        collect_failure(udid, device_diagnostics / f'failure-{stage}', ready_file)
        raise
    finally:
        command('shutdown', udid, timeout=30, check=False)
save_manifest()
progress(f'Captured and dimension-checked {len(records)} native screenshots.')
