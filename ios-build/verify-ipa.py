import hashlib, json, os, pathlib, plistlib, subprocess, zipfile
work = pathlib.Path(os.environ['BUILD_WORK'])
release = json.loads((pathlib.Path(os.environ['BUILD_HELPERS'])/'release.json').read_text())
ipas = list((work/'export').glob('*.ipa'))
assert len(ipas) == 1, 'Expected one IPA'
with zipfile.ZipFile(ipas[0]) as archive:
    assert archive.testzip() is None
    infos = [n for n in archive.namelist() if n.startswith('Payload/') and n.count('/') == 2 and n.endswith('.app/Info.plist')]
    assert len(infos) == 1
    info = plistlib.loads(archive.read(infos[0]))
    assert info['CFBundleIdentifier'] == release['bundleIdentifier']
    assert info['CFBundleShortVersionString'] == release['version']
    assert info['CFBundleVersion'] == release['build']
    for permission in ['NSCameraUsageDescription','NSMicrophoneUsageDescription','NSPhotoLibraryAddUsageDescription']:
        assert info.get(permission)
    assert any(n.endswith('/_CodeSignature/CodeResources') for n in archive.namelist())
    assert any(n.endswith('/embedded.mobileprovision') for n in archive.namelist())
verify_dir = work/'verify-export'
subprocess.run(['ditto','-xk',str(ipas[0]),str(verify_dir)],check=True)
apps = list((verify_dir/'Payload').glob('*.app'))
assert len(apps) == 1
subprocess.run(['codesign','--verify','--deep','--strict',str(apps[0])], check=True)
profile = plistlib.loads(subprocess.check_output(['security','cms','-D','-i',str(apps[0]/'embedded.mobileprovision')]))
assert profile['Entitlements']['application-identifier'] == release['teamId']+'.'+release['bundleIdentifier']
assert profile['Entitlements'].get('get-task-allow') is False
assert not profile.get('ProvisionedDevices')
receipt = {**release, 'ipaSha256':hashlib.sha256(ipas[0].read_bytes()).hexdigest(),
    'ipaBytes':ipas[0].stat().st_size, 'codesign':'verified', 'githubRunId':os.environ.get('GITHUB_RUN_ID'),
    'appleUpload':'not yet submitted', 'deviceTesting':'not run'}
(work/'verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
print('Exported IPA identity, version, permissions and code signature verified.')
