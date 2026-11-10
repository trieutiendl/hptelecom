import json, os, pathlib, zipfile
work=pathlib.Path(os.environ['BUILD_WORK'])
status=int(os.environ.get('HP_ADMIN_BUILD_EXIT','1'))
capture_passed=status==0 and all((work/('screenshots-'+family+'.json')).is_file() for family in ['iPhone','iPad'])
capture_requested=os.environ.get('HP_ADMIN_CAPTURE_REQUESTED')=='1'
(work/'build-result.json').write_text(json.dumps({'exitCode':status,'successful':status==0,'runId':os.environ.get('GITHUB_RUN_ID'),'deviceTesting':'physical device not run','productionAuthenticatedTesting':'passed using isolated reviewer account' if capture_passed else 'not completed or not verified' if capture_requested else 'not requested','simulatorAuthenticatedCapture':{'iPhone':'passed' if capture_passed else 'see diagnostics' if capture_requested else 'not requested','iPad':'passed' if capture_passed else 'see diagnostics' if capture_requested else 'not requested'}},indent=2)+'\n')
paths=['build-result.json','build.log','javascript-tests.log','browser-bridge-production.json','browser-upload-production.json','native-tests.log','privacy-source.json','privacy-packaged.json','privacy-tests.log','capture-context.json','capture-pipeline-tests.log','screenshots-iPhone.json','screenshots-iPad.json','simulator-signing-iPhone.json','simulator-signing-iPad.json','simulator-choices.json','verification.json','iPhone-tests-passed.txt','iPad-tests-passed.txt']
with zipfile.ZipFile(work/'hp-admin-ios-result.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for relative in paths:
        path=work/relative
        if path.is_file(): archive.write(path,relative)
    for folder in ['screenshots','smoke-screenshots','appstore','crash-diagnostics']:
        for path in (work/folder).rglob('*'):
            if path.is_file() and not path.is_symlink(): archive.write(path,path.relative_to(work).as_posix())
    if status==0 and (work/'verification.json').is_file():
        ipas=list((work/'export').glob('*.ipa'))
        if len(ipas)!=1: raise ValueError('Expected one verified admin IPA')
        archive.write(ipas[0],'HP-Telecom-Quan-Tri-iOS-1.0.1-2.ipa')
print('Result package prepared for encryption.')
