import json, os, pathlib, zipfile
work=pathlib.Path(os.environ['BUILD_WORK'])
status=int(os.environ.get('HP_MAP_BUILD_EXIT','1'))
(work/'build-result.json').write_text(json.dumps({'exitCode':status,'successful':status==0,'runId':os.environ.get('GITHUB_RUN_ID'),'deviceTesting':'not run'},indent=2)+'\n')
paths=['build-result.json','build.log','javascript-tests.log','native-tests.log','simulator-choice.json','verification.json','simulator-tests-passed.txt']
with zipfile.ZipFile(work/'hp-map-ios-result.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for relative in paths:
        path=work/relative
        if path.is_file(): archive.write(path,relative)
    # Exported xcresult attachments retain screenshots, recordings and AX diagnostics
    # without duplicating the full simulator result bundle in the download.
    for folder in ['screenshots']:
        base=work/folder
        if base.is_dir():
            for path in base.rglob('*'):
                if path.is_file() and not path.is_symlink(): archive.write(path,path.relative_to(work).as_posix())
    # Failed or unverified builds must not distribute an installation package.
    if status==0 and (work/'verification.json').is_file():
        ipas=list((work/'export').glob('*.ipa'))
        if len(ipas)!=1: raise ValueError('Expected one verified HP Map IPA')
        archive.write(ipas[0],'HP-Map-iOS-1.0.0-2.ipa')
print('Encrypted-result package prepared.')
