"""Summarize the exact native XCTest without exporting typed input or raw logs."""
import json
import os
from pathlib import Path
import re
import sys


CASE = 'testFreshInstallLoginAndReopenNotebook'
MODELS = ('iPhone 17 Pro Max', 'iPhone 16 Pro Max')
STAGES = ('ready', 'password', 'advance', 'pasted', 'retry', 'mismatch', 'create', 'incorrect', 'correct', 'reopen')
NOTICES = ('none', 'short', 'username', 'mismatch', 'wrong', 'missing', 'input', 'duplicate', 'created')


def probes(log):
    checkpoints = []
    fields = {'stage', 'notice', 'nameState', 'secureLength', 'keyboard', 'button', 'secure'}
    for raw in re.findall(r'^\[FIELDWORK_QA\](\{[^\n]*\})\s*$', log, re.MULTILINE):
        value = json.loads(raw)
        if (set(value) != fields or value['stage'] not in STAGES or value['notice'] not in NOTICES
                or type(value['nameState']) is not int or value['nameState'] not in (0, 1, 2)
                or type(value['secureLength']) is not int or not -1 <= value['secureLength'] <= 128
                or any(type(value[k]) is not bool for k in ('keyboard', 'button', 'secure'))):
            raise ValueError('Unexpected diagnostic fields.')
        checkpoints.append(value)
    paths = re.findall(r'^\[FIELDWORK_KEYBOARD\](native-visible|native-hidden|paste|shortcut)\s*$', log, re.MULTILINE)
    if len(checkpoints) > len(STAGES) or len(paths) > 20:
        raise ValueError('Unexpected number of native input observations.')
    return checkpoints, paths


def summarize(log, inventory, udid, exit_code):
    matches = [(runtime, phone) for runtime, group in inventory['devices'].items()
               for phone in group if phone['udid'] == udid]
    if len(matches) != 1:
        raise ValueError('The selected simulator was not identified.')
    runtime, phone = matches[0]
    if not re.fullmatch(r'com\.apple\.CoreSimulator\.SimRuntime\.iOS-\d+(?:-\d+)*', runtime):
        raise ValueError('The simulator runtime is not a recognized iOS version.')
    if phone['name'] not in MODELS or phone['state'] != 'Booted' or not phone.get('isAvailable'):
        raise ValueError('The selected iPhone simulator was unavailable.')
    # Use the exact test result in addition to the process exit code: a filter
    # that silently selects zero tests must never authorize a store delivery.
    verdicts = re.findall(r"Test Case '-\[PublicScreenshots\.PublicScreenshots " + CASE
                          + r"\]' (passed|failed|skipped)\b", log)
    end_to_end = (exit_code == 0 and verdicts == ['passed']
                  and re.search(r'Executed 1 test, with 0 failures', log) is not None)
    checkpoints, paths = probes(log)
    typed_only = len(paths) == 6 and all(p.startswith('native-') for p in paths)
    baseline = re.findall(r'^\[FIELDWORK_CONTROL\]length=(-?\d{1,3}) keyboard=(true|false) system=(true|false)\s*$',
                          log, re.MULTILINE)
    system = re.findall(r'^\[FIELDWORK_SYSTEM\]keyboard=(true|false) prompt=(true|false)\s*$', log, re.MULTILINE)
    menu = re.findall(r'^\[FIELDWORK_MENU\]available=(true|false)\s*$', log, re.MULTILINE)
    hits = re.findall(r'^\[FIELDWORK_HIT\]area=(true|false) onScreen=(true|false) covered=(true|false) '
                      r'windows=(\d{1,2}) alerts=(\d{1,2})\s*$', log, re.MULTILINE)
    if len(baseline) > 1 or len(system) > 12 or len(menu) > 12 or len(hits) > 4:
        raise ValueError('Unexpected native baseline diagnostics.')
    return {
        'test': CASE, 'result': 'passed' if end_to_end and typed_only else 'failed',
        'endToEnd': end_to_end, 'ordinaryTypingOnly': typed_only,
        'device': phone['name'], 'runtime': runtime.split('iOS-')[1].replace('-', '.'),
        'checkpoints': checkpoints, 'inputPaths': paths,
        'control': ({'length': max(-1, min(128, int(baseline[0][0]))),
                     'keyboard': baseline[0][1] == 'true', 'systemKeyboard': baseline[0][2] == 'true'}
                    if baseline else None),
        'system': [{'keyboard': k == 'true', 'prompt': p == 'true'} for k, p in system],
        'pasteMenu': [v == 'true' for v in menu],
        'hitFailures': [{'area': a == 'true', 'onScreen': s == 'true', 'covered': c == 'true',
                         'windows': int(w), 'alerts': int(n)} for a, s, c, w, n in hits],
        'assertionLines': sorted({int(n) for n in re.findall(r'PublicScreenshots\.swift:(\d+):[^\n]*error:', log)}),
    }


def main():
    log_file, devices_file, udid, exit_code, output = sys.argv[1:]
    result = summarize(Path(log_file).read_text(errors='replace'), json.loads(Path(devices_file).read_text()),
                       udid, int(exit_code))
    manifest = os.environ.get('PUBLIC_LOGIN_MANIFEST')
    if manifest:
        release = json.loads(Path(manifest).read_text())
        result.update({key: release[key] for key in ('bundleIdentifier', 'version', 'build', 'sourceSha256')})
        result.update({'commit': os.environ['GITHUB_SHA'], 'runId': os.environ['GITHUB_RUN_ID'],
                       'attempt': os.environ['GITHUB_RUN_ATTEMPT']})
    Path(output).write_text(json.dumps(result) + '\n')
    print(CASE, result['result'], result['device'], result['runtime'], 'assertionLines', result['assertionLines'])
    return 0 if result['result'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
