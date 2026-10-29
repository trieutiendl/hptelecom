import contextlib, hashlib, importlib.util, json, os, pathlib, plistlib, subprocess, sys, tempfile, unittest, zipfile
HERE=pathlib.Path(__file__).parent
TEST_TEMP=(HERE.parent/'checks/ci-tests').resolve()
TEST_TEMP.mkdir(parents=True,exist_ok=True)
@contextlib.contextmanager
def workspace_temp():
    with tempfile.TemporaryDirectory(dir=TEST_TEMP) as temp:
        assert pathlib.Path(temp).resolve().is_relative_to(TEST_TEMP)
        yield temp
spec=importlib.util.spec_from_file_location('verify_source',HERE/'verify-source.py')
source=importlib.util.module_from_spec(spec); spec.loader.exec_module(source)

class PipelineTests(unittest.TestCase):
    def archive(self,root,extra=None):
        target=root/'source.zip'
        with zipfile.ZipFile(target,'w') as out:
            out.writestr('HPMap.xcodeproj/project.pbxproj','fixture')
            out.writestr('HPMap/Info.plist',plistlib.dumps({'CFBundleShortVersionString':'1.0.0','CFBundleVersion':'2','CFBundleDisplayName':'HP Map'}))
            if extra:
                entry=zipfile.ZipInfo('entry'); entry.filename=extra[0]
                out.writestr(entry,extra[1])
        return target,{**source.EXPECTED,'sourceSha256':hashlib.sha256(target.read_bytes()).hexdigest()}

    def test_source_identity_and_digest(self):
        with workspace_temp() as temp:
            root=pathlib.Path(temp); archive,release=self.archive(root)
            source.extract_checked(archive,root/'valid',release)
            self.assertTrue((root/'valid/HPMap/Info.plist').is_file())
            with self.assertRaises(ValueError): source.extract_checked(archive,root/'wrongapp',{**release,'bundleIdentifier':'vn.hptelecom.fieldwork.server'})
            with self.assertRaises(ValueError): source.extract_checked(archive,root/'digest',{**release,'sourceSha256':'0'*64})

    def test_source_traversal_and_hidden_data_blocked(self):
        for name in ['../outside','/etc/passwd','.private/signing.json','HPMap/../../outside','HPMap\\file.swift','C:/file.swift']:
            with workspace_temp() as temp:
                root=pathlib.Path(temp); archive,release=self.archive(root,(name,'private'))
                with self.assertRaises(ValueError): source.extract_checked(archive,root/'out',release)
                self.assertFalse((root/'outside').exists())

    def test_failure_package_never_contains_ipa_or_signing_material(self):
        with workspace_temp() as temp:
            root=pathlib.Path(temp); (root/'export').mkdir(); (root/'export/app.ipa').write_text('not verified')
            (root/'distribution.p12').write_text('private'); (root/'app.mobileprovision').write_text('private')
            (root/'build.log').write_text('private diagnostic'); (root/'verification.json').write_text('{}')
            subprocess.run([sys.executable,str(HERE/'package-result.py')],check=True,env={**os.environ,'BUILD_WORK':str(root),'HP_MAP_BUILD_EXIT':'1'},stdout=subprocess.DEVNULL)
            with zipfile.ZipFile(root/'hp-map-ios-result.zip') as archive:
                self.assertIn('build.log',archive.namelist())
                self.assertFalse(any(n.endswith(('.ipa','.p12','.mobileprovision')) for n in archive.namelist()))
                self.assertFalse(json.loads(archive.read('build-result.json'))['successful'])

    def test_success_package_only_verified_ipa_and_allowlisted_outputs(self):
        with workspace_temp() as temp:
            root=pathlib.Path(temp); (root/'export').mkdir(); (root/'export/app.ipa').write_text('verified fixture')
            (root/'verification.json').write_text('{}'); (root/'signing-identity.txt').write_text('private')
            subprocess.run([sys.executable,str(HERE/'package-result.py')],check=True,env={**os.environ,'BUILD_WORK':str(root),'HP_MAP_BUILD_EXIT':'0'},stdout=subprocess.DEVNULL)
            with zipfile.ZipFile(root/'hp-map-ios-result.zip') as archive:
                self.assertIn('HP-Map-iOS-1.0.0-2.ipa',archive.namelist())
                self.assertNotIn('signing-identity.txt',archive.namelist())

if __name__=='__main__': unittest.main()
