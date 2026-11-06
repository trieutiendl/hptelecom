import hashlib,json,os,pathlib,stat,zipfile
work=pathlib.Path(os.environ['BUILD_WORK'])
release=json.loads((pathlib.Path(os.environ['BUILD_HELPERS'])/'release.json').read_text())
expected=release['sourceFiles']
assert len(expected)>10
with zipfile.ZipFile(work/'source.zip') as z:
    entries=z.infolist()
    names=[p.filename for p in entries]
    assert len(names)==len(set(names)) and set(names)==set(expected)
    assert z.testzip() is None
    for entry in entries:
        p=pathlib.PurePosixPath(entry.filename)
        assert not p.is_absolute() and '..' not in p.parts and '\\' not in entry.filename
        assert not stat.S_ISLNK(entry.external_attr >> 16)
        assert hashlib.sha256(z.read(entry)).hexdigest()==expected[entry.filename]
    z.extractall(work/'app')
print('Exact frozen source inventory and hashes verified.')
