#!/usr/bin/env python3
"""Validate a pinned CI cache and extract its unchanged simulator application.

run.json and artifact.json must be the authenticated GitHub API responses for
the downloaded artifact, not hand-written assertions. The caller downloads and
decrypts the encrypted artifact; this helper has no network or credential code.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import pathlib
import posixpath
import stat
import zipfile

RUN = 43428673497
COMMIT = 'c9ce102d011aebecb2c88e0e5a2ba867414fa248'
REPOSITORY = 'trieutiendl/hptelecom'
WORKFLOW = '.github/workflows/hptelecom-public-ios-screenshots.yml'
ARTIFACT = 'hptelecom-public-ios-simulator-encrypted'
ARTIFACT_ID = 14039033922
ARTIFACT_DIGEST = 'sha256:fbd5a7700571b123ebf9f7849d9b7197bced30c214669fe4bc32965286586bc9'
SOURCE_SHA = '3e7baac3e5984854d03b1a601d3eaa36be5858271d50d1713141afa73d6ed684'
IDENTITY = {'bundleIdentifier': 'vn.hptelecom.fieldwork', 'version': '1.0.69', 'build': '1'}
MAX_BYTES = 2 * 1024 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def verify_provenance(run, artifact, release, source_zip, source_directory=None):
    require(run.get('id') == RUN and run.get('head_sha') == COMMIT,
            'Cache run or commit is not the reviewed capture.')
    require(run.get('repository', {}).get('full_name') == REPOSITORY and run.get('path') == WORKFLOW,
            'Unexpected cache repository or workflow.')
    require(run.get('status') == 'completed' and run.get('event') == 'workflow_dispatch',
            'Capture run must be completed and manually dispatched.')
    require(artifact.get('name') == ARTIFACT and artifact.get('id') == ARTIFACT_ID
            and artifact.get('digest') == ARTIFACT_DIGEST and artifact.get('expired') is False,
            'Expected an unexpired simulator cache artifact.')
    owner = artifact.get('workflow_run', {})
    require(owner.get('id') == RUN and owner.get('head_sha') == COMMIT,
            'Artifact does not belong to the reviewed capture run.')
    require(all(release.get(key) == value for key, value in IDENTITY.items())
            and release.get('sourceSha256') == SOURCE_SHA,
            'Frozen release identity or source hash differs.')
    require(sha256(source_zip) == SOURCE_SHA, 'Frozen source ZIP SHA256 differs.')
    if source_directory is not None:
        source_directory = pathlib.Path(source_directory)
        inventory = release.get('sourceFiles', {})
        require(isinstance(inventory, dict) and len(inventory) > 10, 'Frozen source inventory is missing.')
        for name, digest in inventory.items():
            member = pathlib.PurePosixPath(name)
            require(not member.is_absolute() and '..' not in member.parts and '\\' not in name,
                    'Unsafe frozen source inventory path.')
            file = source_directory.joinpath(*member.parts)
            require(file.is_file() and not file.is_symlink() and sha256(file) == digest,
                    'Frozen source file differs: ' + name)


def archive_layout(archive):
    entries = archive.infolist()
    require(0 < len(entries) <= 30000, 'Unexpected cache archive entry count.')
    require(sum(entry.file_size for entry in entries) <= MAX_BYTES, 'Cache expands beyond the size limit.')
    names, roots, result = set(), set(), []
    for entry in entries:
        # orig_filename retains unsafe separators/NULs that ZipInfo may
        # normalize for the host platform before exposing filename.
        name = entry.orig_filename
        path = pathlib.PurePosixPath(name)
        normalized = name.rstrip('/')
        require(normalized and not path.is_absolute() and '\\' not in name and ':' not in name
                and '\x00' not in name and not any(p in ('', '.', '..') for p in normalized.split('/')),
                'Unsafe cache archive path.')
        require(normalized.casefold() not in names, 'Duplicate cache archive path.')
        names.add(normalized.casefold())
        require(not entry.flag_bits & 1, 'Unexpected encrypted ZIP member.')
        mode = entry.external_attr >> 16
        require(stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR, stat.S_IFLNK),
                'Unsupported cache archive file type.')
        if path.parts[0] == '__MACOSX':
            # ditto stores Finder/resource metadata here. It is not app content.
            continue
        root = path.parts[0]
        require(root.endswith('.app'), 'Only one simulator app may be extracted.')
        roots.add(root)
        target = None
        if stat.S_ISLNK(mode):
            require(entry.file_size <= 4096, 'Oversized symbolic link.')
            target = archive.read(entry).decode('utf-8')
            require(target and not target.startswith('/') and '\\' not in target and ':' not in target
                    and '\x00' not in target,
                    'Unsafe cache symbolic link.')
            resolved = posixpath.normpath(posixpath.join(str(path.parent), target))
            require(resolved.startswith(root + '/'), 'Cache symbolic link leaves the app.')
        result.append((entry, mode, target))
    require(len(roots) == 1, 'Expected exactly one simulator app root.')
    root = next(iter(roots))
    required = {root + '/Info.plist', root + '/main.jsbundle'}
    require(required.issubset({e.filename for e, _, _ in result}), 'Simulator app metadata or JavaScript is missing.')
    links = {e.filename.rstrip('/') for e, _, target in result if target is not None}
    require(all(not any(str(parent) in links for parent in pathlib.PurePosixPath(e.filename).parents)
                for e, _, _ in result), 'A ZIP member is nested beneath a symbolic link.')
    return result


def extract_cache(cache_zip, destination):
    destination = pathlib.Path(destination)
    require(not destination.exists(), 'Cache extraction destination must be new.')
    with zipfile.ZipFile(cache_zip) as archive:
        layout = archive_layout(archive)
        require(archive.testzip() is None, 'Cache ZIP checksum failed.')
        destination.mkdir(parents=True)
        for entry, mode, target in layout:
            output = destination.joinpath(*pathlib.PurePosixPath(entry.filename).parts)
            if target is not None:
                continue
            if entry.is_dir():
                output.mkdir(parents=True, exist_ok=True)
            else:
                output.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, output.open('xb') as out:
                    for chunk in iter(lambda: source.read(1024 * 1024), b''):
                        out.write(chunk)
                os.chmod(output, stat.S_IMODE(mode) or 0o644)
        for entry, _, target in layout:
            if target is not None:
                output = destination.joinpath(*pathlib.PurePosixPath(entry.filename).parts)
                output.parent.mkdir(parents=True, exist_ok=True)
                output.symlink_to(target)


def main():
    parser = argparse.ArgumentParser()
    for name in ('run', 'artifact', 'release', 'source-zip', 'cache-zip', 'destination', 'receipt'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--source-directory', required=True)
    args = parser.parse_args()
    read = lambda path: json.loads(pathlib.Path(path).read_text(encoding='utf-8-sig'))
    run, artifact, release = read(args.run), read(args.artifact), read(args.release)
    verify_provenance(run, artifact, release, args.source_zip, args.source_directory)
    cache_hash = sha256(args.cache_zip)
    extract_cache(args.cache_zip, args.destination)
    verifier = pathlib.Path(__file__).resolve().parent.parent / 'verify-simulator.py'
    spec = importlib.util.spec_from_file_location('verify_simulator', verifier)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    app = module.verify_product(args.destination)
    receipt = dict(IDENTITY, sourceSha256=SOURCE_SHA, cacheRun=RUN, cacheCommit=COMMIT,
                   artifactId=artifact['id'], artifactDigest=ARTIFACT_DIGEST, cacheSha256=cache_hash,
                   executableSha256=sha256(app / read_plist_executable(app)),
                   javascriptSha256=sha256(app / 'main.jsbundle'))
    pathlib.Path(args.receipt).write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(app)


def read_plist_executable(app):
    import plistlib
    with (app / 'Info.plist').open('rb') as stream:
        return plistlib.load(stream)['CFBundleExecutable']


if __name__ == '__main__':
    main()
