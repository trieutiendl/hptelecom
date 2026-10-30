import hashlib, json, os, pathlib, plistlib, stat, zipfile

EXPECTED = {'version':'1.0.0','build':'3','bundleIdentifier':'vn.hptelecom.map','teamId':'DX247Z5GQ8'}

def extract_checked(source, destination, release):
    for key,value in EXPECTED.items():
        if release.get(key) != value: raise ValueError('HP Map release identity mismatch')
    digest = release.get('sourceSha256')
    if not isinstance(digest,str) or len(digest)!=64 or hashlib.sha256(source.read_bytes()).hexdigest()!=digest:
        raise ValueError('HP Map source integrity failed')
    total=0; seen=set()
    with zipfile.ZipFile(source) as archive:
        for item in archive.infolist():
            name=item.orig_filename
            path=pathlib.PurePosixPath(name)
            mode=item.external_attr>>16
            if not name or '\\' in name or ':' in name or path.is_absolute() or '..' in path.parts or any(p.startswith('.') for p in path.parts):
                raise ValueError('Unsafe source archive path')
            if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0,stat.S_IFREG,stat.S_IFDIR)):
                raise ValueError('Unsafe archive entry type')
            if name.lower() in seen: raise ValueError('Duplicate source archive path')
            seen.add(name.lower()); total+=item.file_size
            if total>100*1024*1024 or item.file_size>40*1024*1024: raise ValueError('Source archive too large')
        archive.extractall(destination)
    if not (destination/'HPMap.xcodeproj/project.pbxproj').is_file(): raise ValueError('Missing HP Map Xcode project')
    info=plistlib.loads((destination/'HPMap/Info.plist').read_bytes())
    if info['CFBundleShortVersionString']!=release['version'] or info['CFBundleVersion']!=release['build']:
        raise ValueError('Info.plist release mismatch')
    if info.get('CFBundleDisplayName')!='HP Map': raise ValueError('Wrong app display name')

if __name__ == '__main__':
    work=pathlib.Path(os.environ['BUILD_WORK'])
    release=json.loads((pathlib.Path(os.environ['BUILD_HELPERS'])/'release.json').read_text())
    destination=work/'app'; destination.mkdir()
    extract_checked(work/'source.zip',destination,release)
    print('Separate HP Map source identity and encrypted package verified.')
