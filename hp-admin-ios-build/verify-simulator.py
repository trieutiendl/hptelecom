"""Verify actual simulator signatures/entitlements before exercising Keychain."""
import argparse, json, os, pathlib, plistlib, subprocess

APP_ID='DX247Z5GQ8.vn.hptelecom.admin.ios'
def validate(info, entitlements):
    if info.get('CFBundleIdentifier')!='vn.hptelecom.admin.ios' or info.get('CFBundleShortVersionString')!='1.0.0' or info.get('CFBundleVersion')!='1':
        raise ValueError('Wrong simulator app identity')
    if not str(info.get('DTSDKName','')).startswith('iphonesimulator'):raise ValueError('Simulator app required')
    if entitlements.get('application-identifier')!=APP_ID or entitlements.get('com.apple.developer.team-identifier')!='DX247Z5GQ8':
        raise ValueError('Simulator application entitlement missing')
    if entitlements.get('keychain-access-groups')!=[APP_ID] or entitlements.get('get-task-allow') is not True:
        raise ValueError('Expected isolated simulator Keychain group/debug entitlement')
    return {'appIdentifier':APP_ID,'keychainGroups':[APP_ID],'simulatorOnly':True,'getTaskAllow':True}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--family',choices=['iPhone','iPad'],required=True);args=parser.parse_args()
    work=pathlib.Path(os.environ['BUILD_WORK'])
    app=work/'SimulatorDerivedData/Build/Products/Debug-iphonesimulator/HPTelecomAdmin.app'
    info=plistlib.loads((app/'Info.plist').read_bytes())
    subprocess.run(['codesign','--verify','--deep','--strict',str(app)],check=True)
    entitlements=plistlib.loads(subprocess.check_output(['codesign','-d','--entitlements',':-',str(app)],stderr=subprocess.DEVNULL))
    signature=subprocess.run(['codesign','-d','--verbose=2',str(app)],capture_output=True,text=True,check=True)
    if 'Signature=adhoc' not in signature.stderr:raise ValueError('Expected ad-hoc simulator signature')
    result=validate(info,entitlements)
    result.update(passed=True,family=args.family,signature='verified ad-hoc')
    (work/('simulator-signing-'+args.family+'.json')).write_text(json.dumps(result,indent=2)+'\n')
    print(args.family+': simulator signature and isolated app Keychain entitlements verified')

if __name__=='__main__':main()
