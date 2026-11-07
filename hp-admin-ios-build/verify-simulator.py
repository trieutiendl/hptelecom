"""Verify actual simulator signatures/entitlements before exercising Keychain."""
import argparse, json, os, pathlib, plistlib, struct, subprocess

APP_ID='DX247Z5GQ8.vn.hptelecom.admin.ios'

def simulated_entitlements(binary):
    """Read linked Mach-O sections, not the empty simulator codesign plist.

    Xcode embeds simulated entitlements in __TEXT. ONLY_ACTIVE_ARCH makes our
    build a single 64-bit arm64/x86_64 executable. Reject other layouts instead
    of accepting an intermediate .xcent that might not match the linked app.
    """
    if len(binary)<32 or binary[:4]!=b'\xcf\xfa\xed\xfe':
        raise ValueError('Expected thin little-endian 64-bit simulator Mach-O')
    _,cpu,_,kind,count,command_bytes,_,_=struct.unpack_from('<8I',binary)
    if cpu not in (0x01000007,0x0100000c) or kind!=2:
        raise ValueError('Expected arm64/x86_64 simulator executable')
    command_end=32+command_bytes
    if command_end>len(binary) or count>command_bytes//8:
        raise ValueError('Truncated Mach-O load commands')
    offset=32; sections={}
    for _ in range(count):
        if offset+8>command_end:raise ValueError('Truncated Mach-O load command')
        command,size=struct.unpack_from('<II',binary,offset)
        if size<8 or size%8 or offset+size>command_end:
            raise ValueError('Invalid Mach-O load command bounds')
        if command==0x19: # LC_SEGMENT_64
            if size<72:raise ValueError('Truncated Mach-O segment')
            segment=binary[offset+8:offset+24].rstrip(b'\0')
            nsects=struct.unpack_from('<I',binary,offset+64)[0]
            if size!=72+nsects*80:raise ValueError('Invalid Mach-O section count')
            for i in range(nsects):
                start=offset+72+i*80
                name=binary[start:start+16].rstrip(b'\0')
                parent=binary[start+16:start+32].rstrip(b'\0')
                if segment==parent==b'__TEXT' and name in (b'__entitlements',b'__ents_der'):
                    length,file_offset=struct.unpack_from('<QI',binary,start+40)
                    if not length or length>1024*1024 or file_offset<command_end or file_offset+length>len(binary):
                        raise ValueError('Invalid simulated entitlement section bounds')
                    if name in sections:raise ValueError('Duplicate simulated entitlement section')
                    sections[name]=binary[file_offset:file_offset+length]
        offset+=size
    if offset!=command_end:raise ValueError('Mach-O command size mismatch')
    if set(sections)!={b'__entitlements',b'__ents_der'}:
        raise ValueError('Both XML and DER simulated entitlement sections required')
    entitlements=plistlib.loads(sections[b'__entitlements'])
    if not isinstance(entitlements,dict):raise ValueError('Entitlements must be a dictionary')
    return entitlements,sections[b'__ents_der']

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
    app=work/('SimulatorDerivedData-'+args.family)/'Build/Products/Debug-iphonesimulator/HPTelecomAdmin.app'
    info=plistlib.loads((app/'Info.plist').read_bytes())
    subprocess.run(['codesign','--verify','--deep','--strict',str(app)],check=True)
    signature=subprocess.run(['codesign','-d','--verbose=2',str(app)],capture_output=True,text=True,check=True)
    if 'Signature=adhoc' not in signature.stderr:raise ValueError('Expected ad-hoc simulator signature')
    if info.get('CFBundleExecutable')!='HPTelecomAdmin':raise ValueError('Unexpected simulator executable')
    entitlements,der=simulated_entitlements((app/'HPTelecomAdmin').read_bytes())
    # Confirm the XML and DER sections used by the simulator agree. This is the
    # same check used by Apple's SwiftBuild qualification tests.
    der_path=work/('simulator-entitlements-'+args.family+'.der')
    xml_path=work/('simulator-entitlements-'+args.family+'.plist')
    der_path.write_bytes(der)
    subprocess.run(['/usr/bin/derq','query','-i',str(der_path),'-o',str(xml_path),'--xml'],check=True)
    if plistlib.loads(xml_path.read_bytes())!=entitlements:raise ValueError('Simulator XML/DER entitlements differ')
    result=validate(info,entitlements)
    result.update(passed=True,family=args.family,signature='verified ad-hoc',entitlementSource='linked Mach-O __TEXT XML and DER')
    (work/('simulator-signing-'+args.family+'.json')).write_text(json.dumps(result,indent=2)+'\n')
    print(args.family+': simulator signature and isolated app Keychain entitlements verified')

if __name__=='__main__':main()
