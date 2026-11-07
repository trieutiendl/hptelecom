"""Select ten unedited screenshots from the authenticated XCTest for each device family."""
import argparse, hashlib, json, os, pathlib, re, shutil, struct
def verify_family(work,family):
    root=work/'screenshots'/family
    manifest=json.loads((root/'manifest.json').read_text())
    selected={}
    for group in manifest:
        if group.get('testIdentifier')!='AuthenticatedScreenshots/testTenAuthenticatedAppStoreScreens()': continue
        for entry in group.get('attachments',[]):
            title=entry.get('suggestedHumanReadableName','')
            match=re.match(r'AppStore-auth-(0[1-9]|10)-([a-z0-9-]+)_',title)
            if not match: continue
            number=int(match.group(1)); filename=entry['exportedFileName']
            if entry.get('isAssociatedWithFailure') or number in selected: raise ValueError('Failed or duplicate authenticated screenshot')
            if pathlib.PurePosixPath(filename).name!=filename or not filename.lower().endswith('.png'): raise ValueError('Invalid screenshot attachment path')
            source=root/filename; data=source.read_bytes()
            if data[:8]!=b'\x89PNG\r\n\x1a\n' or data[12:16]!=b'IHDR': raise ValueError('Screenshot must be original simulator PNG')
            width,height=struct.unpack('>II',data[16:24])
            if width<1000 or height<2000: raise ValueError('Screenshot is too small for its simulator')
            if not entry.get('deviceName','').startswith(family): raise ValueError('Screenshot device family mismatch')
            target=work/'appstore'/family/(match.group(1)+'-'+match.group(2)+'.png')
            selected[number]={'number':number,'name':title,'device':entry['deviceName'],'width':width,'height':height,'sha256':hashlib.sha256(data).hexdigest(),'source':str(source.relative_to(work)),'file':str(target.relative_to(work)),'capturedAt':entry.get('timestamp')}
    if set(selected)!=set(range(1,11)): raise ValueError('Exactly ten distinct authenticated screenshots are required for '+family)
    for entry in selected.values():
        target=work/entry['file'];target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(work/entry['source'],target)
        if hashlib.sha256(target.read_bytes()).hexdigest()!=entry['sha256']: raise ValueError('Copied screenshot differs from the original')
    return [selected[n] for n in sorted(selected)]
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--family',choices=['iPhone','iPad'],required=True);args=parser.parse_args()
    work=pathlib.Path(os.environ['BUILD_WORK'])
    selected=verify_family(work,args.family)
    receipt={'passed':True,'family':args.family,'count':len(selected),'mode':'real authenticated app; native simulator screenshots; unedited','files':selected}
    (work/('screenshots-'+args.family+'.json')).write_text(json.dumps(receipt,indent=2)+'\n')
    print(args.family+': ten authenticated screenshots verified and copied without editing')
