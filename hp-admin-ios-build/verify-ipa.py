import datetime, hashlib, json, os, pathlib, plistlib, subprocess, zipfile
work=pathlib.Path(os.environ['BUILD_WORK'])
release=json.loads((pathlib.Path(os.environ['BUILD_HELPERS'])/'release.json').read_text())
assert release['bundleIdentifier']=='vn.hptelecom.admin.ios' and release['teamId']=='DX247Z5GQ8'
for family in ['iPhone','iPad']: assert (work/(family+'-tests-passed.txt')).read_text().strip()=='passed'
capture_requested=os.environ.get('HP_ADMIN_CAPTURE_REQUESTED')=='1'
screenshots={family:json.loads((work/('screenshots-'+family+'.json')).read_text()) for family in ['iPhone','iPad']} if capture_requested else {}
if capture_requested: assert all(v['passed'] and v['count']==10 and v.get('sourceSha256')==release['sourceSha256'] and v.get('githubRunId')==os.environ.get('GITHUB_RUN_ID') for v in screenshots.values())
ipas=list((work/'export').glob('*.ipa')); assert len(ipas)==1
with zipfile.ZipFile(ipas[0]) as archive:
    assert archive.testzip() is None
    infos=[n for n in archive.namelist() if n.startswith('Payload/') and n.count('/')==2 and n.endswith('.app/Info.plist')]
    assert len(infos)==1
    prefix=infos[0][:-len('Info.plist')]
    info=plistlib.loads(archive.read(infos[0]))
    assert info['CFBundleIdentifier']==release['bundleIdentifier']
    assert info['CFBundleShortVersionString']==release['version'] and info['CFBundleVersion']==release['build']
    assert info['CFBundleDisplayName']=='HP Telecom Quản trị'
    assert info.get('MinimumOSVersion')=='15.0' and sorted(info.get('UIDeviceFamily',[]))==[1,2]
    assert not info.get('NSAppTransportSecurity'), 'No ATS exceptions are allowed'
    assert isinstance(info.get('NSLocationWhenInUseUsageDescription'),str) and info['NSLocationWhenInUseUsageDescription'].strip()
    assert not any(k in info for k in ['NSCameraUsageDescription','NSPhotoLibraryUsageDescription','NSLocationAlwaysUsageDescription','NSLocationAlwaysAndWhenInUseUsageDescription','NSMicrophoneUsageDescription'])
    assert not info.get('UIBackgroundModes'), 'No background location or background execution modes are allowed'
    assert not info.get('UIFileSharingEnabled',False) and not info.get('LSSupportsOpeningDocumentsInPlace',False)
    sdk=info.get('DTSDKName',''); assert sdk.startswith('iphoneos') and int(sdk[8:].split('.')[0])>=26
    for resource in ['admin-bridge.js','ios-upload.js','PrivacyInfo.xcprivacy']:
        actual=archive.read(prefix+resource)
        source=work/'app/Admin'/('Resources/'+resource if resource.endswith('.js') else resource)
        if resource.endswith('.js'): assert actual==source.read_bytes()
        else: assert plistlib.loads(actual)==plistlib.loads(source.read_bytes())
verify=work/'verify-export'
subprocess.run(['ditto','-xk',str(ipas[0]),str(verify)],check=True)
apps=list((verify/'Payload').glob('*.app')); assert len(apps)==1
subprocess.run(['python3',str(work/'app/scripts/check-privacy.py'),'--source',str(work/'app/Admin/PrivacyInfo.xcprivacy'),'--packaged',str(apps[0]),'--json-output',str(work/'privacy-packaged.json')],check=True)
privacy_check=json.loads((work/'privacy-packaged.json').read_text())
assert privacy_check['passed'] and privacy_check['packaged_checked']
subprocess.run(['codesign','--verify','--deep','--strict',str(apps[0])],check=True)
entitlements=plistlib.loads(subprocess.check_output(['codesign','-d','--entitlements',':-',str(apps[0])],stderr=subprocess.DEVNULL))
assert entitlements.get('application-identifier')==release['teamId']+'.'+release['bundleIdentifier']
assert entitlements.get('com.apple.developer.team-identifier')==release['teamId'] and entitlements.get('get-task-allow',False) is False
executable=(apps[0]/info['CFBundleExecutable']).read_bytes()
assert b'--hp-admin-ui-test' not in executable, 'Offline test fixture must not ship in Release'
assert b'--hp-admin-authenticated-capture' not in executable and b'HP_ADMIN_CAPTURE_SESSION' not in executable, 'Private capture bootstrap must not ship in Release'
assert b'HP_ADMIN_CAPTURE_EXPECTED_USERNAME' not in executable and b'DebugCaptureSession' not in executable, 'Capture-only authentication code must not ship in Release'
assert b'HP_ADMIN_CAPTURE_ORIGIN' not in executable, 'Capture-only origin bootstrap must not ship in Release'
assert all(value not in executable for value in [b'--hp-admin-keychain-test',b'keychain-self-test',b'hp-admin-keychain-result',b'HP Admin Keychain round trip passed']), 'Keychain self-test must not ship in Release'
assert b'hp-admin-capture-stage.json' not in executable, 'DEBUG phase diagnostics must not ship in Release'
profile=plistlib.loads(subprocess.check_output(['security','cms','-D','-i',str(apps[0]/'embedded.mobileprovision')]))
assert profile['TeamIdentifier']==[release['teamId']]
assert profile['Entitlements']['application-identifier']==release['teamId']+'.'+release['bundleIdentifier']
assert profile['Entitlements'].get('get-task-allow') is False
assert not profile.get('ProvisionedDevices') and not profile.get('ProvisionsAllDevices')
assert profile['ExpirationDate']>datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
receipt={**release,'ipaSha256':hashlib.sha256(ipas[0].read_bytes()).hexdigest(),'ipaBytes':ipas[0].stat().st_size,'codesign':'verified','githubRunId':os.environ.get('GITHUB_RUN_ID'),'simulatorTests':{'iPhone':'passed','iPad':'passed','mode':'offline native menus plus real public login screenshots; no production authentication'},'screenshots':{'AppStore':'actual public login/native menu/About','offlineFixture':'functional test evidence only'},'appleUpload':'not submitted','deviceTesting':'not run','testFixtureInRelease':False,'signedEntitlements':'verified','minimumIOSVersion':info.get('MinimumOSVersion'),'sdkName':sdk,'xcodeBuild':info.get('DTXcodeBuild'),'xcodeVersion':info.get('DTXcode')}
receipt['privacyManifest']=privacy_check
receipt['simulatorTests']['mode']='offline UI plus real limited-reviewer screenshots' if capture_requested else 'offline UI plus unauthenticated public login; reviewer capture not requested'
receipt['screenshotsOrigin']='https://hptelecom.id.vn'
receipt['captureRequested']=capture_requested
receipt['screenshots']={'AppStore':'ten verified unedited limited-reviewer screenshots per device family' if capture_requested else 'authenticated capture not requested','iPhone':10 if capture_requested else 0,'iPad':10 if capture_requested else 0,'offlineFixture':'functional test evidence only'}
(work/'verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
print('Separate admin identity, device families, version, resources, UI tests and signature verified.')
