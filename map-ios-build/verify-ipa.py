import datetime, hashlib, json, os, pathlib, plistlib, subprocess, zipfile
work=pathlib.Path(os.environ['BUILD_WORK'])
release=json.loads((pathlib.Path(os.environ['BUILD_HELPERS'])/'release.json').read_text())
assert release['bundleIdentifier']=='vn.hptelecom.map' and release['teamId']=='DX247Z5GQ8'
assert (work/'simulator-tests-passed.txt').read_text().strip()=='passed'
ipas=list((work/'export').glob('*.ipa'))
assert len(ipas)==1, 'Expected one HP Map IPA'
with zipfile.ZipFile(ipas[0]) as archive:
    assert archive.testzip() is None
    infos=[n for n in archive.namelist() if n.startswith('Payload/') and n.count('/')==2 and n.endswith('.app/Info.plist')]
    assert len(infos)==1
    info=plistlib.loads(archive.read(infos[0]))
    assert info['CFBundleIdentifier']==release['bundleIdentifier']
    assert info['CFBundleShortVersionString']==release['version'] and info['CFBundleVersion']==release['build']
    assert info.get('CFBundleDisplayName')=='HP Map' and info.get('NSLocationWhenInUseUsageDescription')
    sdk=info.get('DTSDKName','')
    assert sdk.startswith('iphoneos') and int(sdk[8:].split('.')[0]) >= 26, 'App Store requires iOS 26 SDK or later'
    assert any(n.endswith('/_CodeSignature/CodeResources') for n in archive.namelist())
    assert any(n.endswith('/embedded.mobileprovision') for n in archive.namelist())
    assert any(n.endswith('/Web/hpmap/app.js') for n in archive.namelist())
    assert any(n.endswith('/Web/hpmap/ios-native.js') for n in archive.namelist())
    assert any(n.endswith('/Web/hpmap/ios-api.js') for n in archive.namelist())
verify=work/'verify-export'
subprocess.run(['ditto','-xk',str(ipas[0]),str(verify)],check=True)
apps=list((verify/'Payload').glob('*.app')); assert len(apps)==1
subprocess.run(['codesign','--verify','--deep','--strict',str(apps[0])],check=True)
entitlements=plistlib.loads(subprocess.check_output(['codesign','-d','--entitlements',':-',str(apps[0])],stderr=subprocess.DEVNULL))
assert entitlements.get('application-identifier')==release['teamId']+'.'+release['bundleIdentifier']
assert entitlements.get('com.apple.developer.team-identifier')==release['teamId']
assert entitlements.get('get-task-allow',False) is False
executable=(apps[0]/info['CFBundleExecutable']).read_bytes()
assert b'--hpmap-ui-test' not in executable and b'ui_test_not_a_live_key' not in executable, 'Test fixture leaked into Release'
profile=plistlib.loads(subprocess.check_output(['security','cms','-D','-i',str(apps[0]/'embedded.mobileprovision')]))
assert profile['TeamIdentifier']==[release['teamId']]
assert profile['Entitlements']['application-identifier']==release['teamId']+'.'+release['bundleIdentifier']
assert profile['Entitlements'].get('get-task-allow') is False
assert not profile.get('ProvisionedDevices') and not profile.get('ProvisionsAllDevices')
assert profile['ExpirationDate']>datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
receipt={**release,'ipaSha256':hashlib.sha256(ipas[0].read_bytes()).hexdigest(),'ipaBytes':ipas[0].stat().st_size,
         'codesign':'verified','githubRunId':os.environ.get('GITHUB_RUN_ID'),'simulatorTests':'passed',
         'appleUpload':'not submitted','deviceTesting':'not run','testFixtureInRelease':False,'signedEntitlements':'verified','minimumIOSVersion':info.get('MinimumOSVersion'),
         'sdkName':sdk,'xcodeBuild':info.get('DTXcodeBuild'),'xcodeVersion':info.get('DTXcode')}
(work/'verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
print('HP Map identity, version, simulator tests and App Store code signature verified.')
