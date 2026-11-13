import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import zipfile

BASE_SHA = 'de8f6eaea8ea62b5e6d24e346707605ca887bdbbc55db861d30d983da159fa1b'
def digest(data): return hashlib.sha256(data).hexdigest()

def validate(manifest):
    assert manifest['schema'] == 1 and manifest['purpose'] == 'DIAGNOSTIC_ONLY_NOT_FOR_STORE'
    assert manifest['namespace'] == 'hptelecom-input-diag-1069-b2-20261114'
    assert (manifest['bundleIdentifier'], manifest['version'], manifest['build'], manifest['deviceFamily']) == ('vn.hptelecom.fieldwork', '1.0.69', '2', [1])
    assert manifest['baseSourceSha256'] == BASE_SHA
    assert set(manifest['overlays']) == {'src/PersonalAccount.tsx', 'src/InputProbe.ts'}
    assert manifest['baselineFiles']['src/PersonalAccount.tsx'] == 'c2274ac9a700a05806e7c2fd55f3fa52e376113a55c094b92e6812bb211c41a4'
    assert manifest['overlays']['src/PersonalAccount.tsx'] == '76ffaab41a0e9dd1981cb653f0b65fd454e6317eaaa0ed649c0a14dbc3bac01d'
    expected = dict(manifest['baselineFiles']); expected.update(manifest['overlays'])
    assert expected == manifest['sourceFiles'] and len(expected) > 10
    assert manifest['sourceSha256'] != BASE_SHA
    return expected

def main():
    helpers = Path(os.environ['BUILD_HELPERS']).resolve()
    work = Path(os.environ['BUILD_WORK']).resolve()
    manifest = json.loads((helpers / 'release.json').read_text())
    expected = validate(manifest)
    for name, checksum in manifest['helperFiles'].items():
        assert '/' not in name and '\\' not in name and name not in ('.', '..')
        assert digest((helpers / name).read_bytes()) == checksum
    source = work / 'source.zip'
    assert digest(source.read_bytes()) == manifest['sourceSha256']
    with zipfile.ZipFile(source) as archive:
        entries = archive.infolist(); names = [item.filename for item in entries]
        assert len(names) == len(set(names)) and set(names) == set(expected)
        assert archive.testzip() is None
        for entry in entries:
            name = PurePosixPath(entry.filename)
            assert not name.is_absolute() and '..' not in name.parts and '\\' not in entry.filename and ':' not in entry.filename
            assert not stat.S_ISLNK(entry.external_attr >> 16)
            assert digest(archive.read(entry)) == expected[entry.filename]
        assert not (work / 'app').exists(), 'Refusing existing extraction directory'
        archive.extractall(work / 'app')
    print('Verified isolated simulator diagnostic source and exact two-file instrumentation.')

if __name__ == '__main__': main()
