import json, os, pathlib, zipfile
work=pathlib.Path(os.environ['BUILD_WORK'])
status=int(os.environ.get('HP_ADMIN_BUILD_EXIT','1'))
(work/'build-result.json').write_text(json.dumps({'exitCode':status,'successful':status==0,'runId':os.environ.get('GITHUB_RUN_ID'),'deviceTesting':'not run','productionAuthenticatedTesting':'not run'},indent=2)+'\n')
paths=['build-result.json','build.log','javascript-tests.log','browser-bridge-result.json','browser-upload-result.json','native-tests.log','simulator-choices.json','verification.json','iPhone-tests-passed.txt','iPad-tests-passed.txt']
with zipfile.ZipFile(work/'hp-admin-ios-result.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for relative in paths:
        path=work/relative
        if path.is_file(): archive.write(path,relative)
    for path in (work/'screenshots').rglob('*'):
        if path.is_file() and not path.is_symlink(): archive.write(path,path.relative_to(work).as_posix())
    if status==0 and (work/'verification.json').is_file():
        ipas=list((work/'export').glob('*.ipa'))
        if len(ipas)!=1: raise ValueError('Expected one verified admin IPA')
        archive.write(ipas[0],'HP-Telecom-Quan-Tri-iOS-1.0.0-1.ipa')
print('Result package prepared for encryption.')
