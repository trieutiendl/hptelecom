import base64, datetime, json, os, pathlib, plistlib, re, secrets, subprocess

work = pathlib.Path(os.environ['BUILD_WORK'])
release = json.loads((pathlib.Path(os.environ['BUILD_HELPERS'])/'release.json').read_text())
signing = json.loads(os.environ['HP_IOS_SIGNING_JSON'])
assert signing['teamId'] == release['teamId']
assert signing['bundleIdentifier'] == release['bundleIdentifier']
p12 = work/'distribution.p12'
profile = work/'app.mobileprovision'
p12.write_bytes(base64.b64decode(signing['p12'], validate=True))
profile.write_bytes(base64.b64decode(signing['profile'], validate=True))
decoded = subprocess.check_output(['security','cms','-D','-i',str(profile)])
metadata = plistlib.loads(decoded)
assert metadata['TeamIdentifier'] == [release['teamId']]
assert metadata['Entitlements']['application-identifier'] == release['teamId']+'.'+release['bundleIdentifier']
assert metadata['Entitlements'].get('get-task-allow') is False
assert not metadata.get('ProvisionedDevices')
assert not metadata.get('ProvisionsAllDevices')
assert metadata['ExpirationDate'] > datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
installed = pathlib.Path.home()/'Library/MobileDevice/Provisioning Profiles'/f"{metadata['UUID']}.mobileprovision"
installed.parent.mkdir(parents=True, exist_ok=True)
installed.write_bytes(profile.read_bytes())
(work/'installed-profile.txt').write_text(str(installed))
keychain = work/'ios-signing.keychain-db'
password = secrets.token_hex(32)
def run(*args):
    result = subprocess.run(list(args), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if result.returncode: raise RuntimeError('Signing keychain setup failed')
run('security','create-keychain','-p',password,str(keychain))
run('security','set-keychain-settings','-lut','7200',str(keychain))
run('security','unlock-keychain','-p',password,str(keychain))
run('security','import',str(p12),'-P',signing['password'],'-A','-t','cert','-f','pkcs12','-k',str(keychain))
run('security','set-key-partition-list','-S','apple-tool:,apple:,codesign:','-s','-k',password,str(keychain))
run('security','list-keychains','-d','user','-s',str(keychain),str(pathlib.Path.home()/'Library/Keychains/login.keychain-db'))
identities = subprocess.check_output(['security','find-identity','-v','-p','codesigning',str(keychain)],text=True)
fingerprints = re.findall(r'\d+\) ([0-9A-F]{40}) "[^"\n]*Distribution[^"\n]*"',identities)
assert len(fingerprints) == 1, 'Expected one distribution signing identity'
(work/'signing-identity.txt').write_text(fingerprints[0])
(work/'profile-uuid.txt').write_text(metadata['UUID'])
plistlib.dump({'method':'app-store-connect','teamID':release['teamId'],'signingStyle':'manual',
    'provisioningProfiles':{release['bundleIdentifier']:metadata['UUID']},
    'signingCertificate':fingerprints[0],'manageAppVersionAndBuildNumber':False,
    'uploadSymbols':True,'stripSwiftSymbols':True,'destination':'export'}, (work/'ExportOptions.plist').open('wb'))
p12.unlink()
print('App Store signing identity, profile and expiry verified.')
