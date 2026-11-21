"""Summarize the exact native XCTest without exporting typed input or raw logs."""
import json
import os
from pathlib import Path
import re
import sys


CASE = 'testFreshInstallLoginAndReopenNotebook'
MODELS = ('iPhone 17 Pro Max', 'iPhone 16 Pro Max')


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
    passed = (exit_code == 0 and verdicts == ['passed']
              and re.search(r'Executed 1 test, with 0 failures', log) is not None)
    return {
        'test': CASE, 'result': 'passed' if passed else 'failed',
        'device': phone['name'], 'runtime': runtime.split('iOS-')[1].replace('-', '.'),
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
