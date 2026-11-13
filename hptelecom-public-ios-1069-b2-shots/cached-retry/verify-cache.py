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
import re
import stat
import zipfile

RUN = 43456092482
COMMIT = '0439b5437b637a6e243c6102ccee29f295bbc28e'
REPOSITORY = 'trieutiendl/hptelecom'
WORKFLOW = '.github/workflows/hptelecom-public-ios-1069-b2.yml'
ARTIFACT = 'hptelecom-public-ios-1069-b2-simulator-encrypted'
SOURCE_SHA = 'de8f6eaea8ea62b5e6d24e346707605ca887bdbbc55db861d30d983da159fa1b'
IDENTITY = {'bundleIdentifier': 'vn.hptelecom.fieldwork', 'version': '1.0.69', 'build': '2'}
PIN_FIXED = dict(IDENTITY, schemaVersion=1, runId=RUN, commit=COMMIT,
                 repository=REPOSITORY, workflow=WORKFLOW, artifactName=ARTIFACT,
                 sourceSha256=SOURCE_SHA)
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


def validate_pin(pin):
    require(isinstance(pin, dict), 'A verified cache pin is required.')
    require(set(pin) == set(PIN_FIXED) | {'artifactId', 'artifactDigest'},
            'Cache pin fields are missing or unexpected.')
    require(all(type(pin.get(key)) is type(value) and pin.get(key) == value
                for key, value in PIN_FIXED.items()), 'Cache pin is not for the reviewed build 2 source.')
    require(type(pin.get('artifactId')) is int and pin['artifactId'] > 0,
            'Cache pin artifactId must be a positive integer.')
    digest = pin.get('artifactDigest')
    require(isinstance(digest, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', digest)
            and len(set(digest[7:])) > 1,
            'Cache pin requires a real SHA256 artifact digest; placeholders are not accepted.')
    return pin


def verify_metadata(run, artifact, pin=None):
    require(isinstance(run, dict) and isinstance(artifact, dict), 'Authenticated GitHub metadata is required.')
    require(run.get('id') == RUN and run.get('head_sha') == COMMIT,
            'Cache run or commit is not the reviewed capture.')
    require(run.get('repository', {}).get('full_name') == REPOSITORY and run.get('path') == WORKFLOW,
            'Unexpected cache repository or workflow.')
    require(run.get('status') == 'completed' and run.get('event') == 'workflow_dispatch',
            'Capture run must be completed and manually dispatched.')
    candidate = dict(PIN_FIXED, artifactId=artifact.get('id'), artifactDigest=artifact.get('digest'))
    validate_pin(candidate)
    require(artifact.get('name') == ARTIFACT and artifact.get('expired') is False,
            'Expected an unexpired simulator cache artifact.')
    if pin is not None:
        require(candidate == validate_pin(pin), 'Authenticated artifact differs from the reviewed pin.')
    owner = artifact.get('workflow_run', {})
    require(owner.get('id') == RUN and owner.get('head_sha') == COMMIT,
            'Artifact does not belong to the reviewed capture run.')
    return candidate


def verify_artifact_archive(archive_path, pin):
    pin = validate_pin(pin)
    archive_path = pathlib.Path(archive_path)
    require(archive_path.is_file() and not archive_path.is_symlink(), 'Artifact ZIP must be a regular file.')
    require(archive_path.stat().st_size <= MAX_BYTES, 'Artifact ZIP exceeds the size limit.')
    require('sha256:' + sha256(archive_path) == pin['artifactDigest'],
            'Downloaded artifact ZIP SHA256 differs from the authenticated pin.')
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        require(len(entries) == 1 and entries[0].orig_filename == 'simulator-app.zip.enc',
                'Artifact ZIP must contain only simulator-app.zip.enc.')
        entry = entries[0]
        mode = entry.external_attr >> 16
        require(not entry.is_dir() and not entry.flag_bits & 1
                and stat.S_IFMT(mode) in (0, stat.S_IFREG)
                and 0 < entry.file_size <= MAX_BYTES,
                'Encrypted simulator cache must be a nonempty regular file.')
        require(archive.testzip() is None, 'Encrypted artifact ZIP checksum failed.')


def verify_provenance(run, artifact, release, source_zip, source_directory, pin):
    verify_metadata(run, artifact, validate_pin(pin))
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
    names, roots, validated, result = set(), set(), [], []
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
        roots.add(path.parts[0])
        validated.append((entry, mode))
    # The pinned capture's ditto archive contains the complete products folder:
    # find encountered its loose main.jsbundle before the application's copy.
    # Select only the expected application, never the included old test runner
    # or dependency products. The signed bundle is still verified after extract.
    wrapped = roots == {'Release-iphonesimulator'}
    if wrapped:
        source_root = 'Release-iphonesimulator/hptelecom.app'
        root = 'hptelecom.app'
        strip_prefix = 'Release-iphonesimulator/'
    else:
        require(len(roots) == 1 and next(iter(roots)).endswith('.app'),
                'Expected exactly one simulator app root.')
        root = source_root = next(iter(roots))
        strip_prefix = ''
    for entry, mode in validated:
        name = entry.orig_filename
        if name.rstrip('/') != source_root and not name.startswith(source_root + '/'):
            continue
        output_name = name[len(strip_prefix):]
        path = pathlib.PurePosixPath(output_name)
        target = None
        if stat.S_ISLNK(mode):
            require(entry.file_size <= 4096, 'Oversized symbolic link.')
            target = archive.read(entry).decode('utf-8')
            require(target and not target.startswith('/') and '\\' not in target and ':' not in target
                    and '\x00' not in target,
                    'Unsafe cache symbolic link.')
            resolved = posixpath.normpath(posixpath.join(str(path.parent), target))
            require(resolved.startswith(root + '/'), 'Cache symbolic link leaves the app.')
        result.append((entry, mode, target, output_name))
    required = {root + '/Info.plist', root + '/main.jsbundle'}
    require(required.issubset({name for _, _, _, name in result}), 'Simulator app metadata or JavaScript is missing.')
    links = {name.rstrip('/') for _, _, target, name in result if target is not None}
    require(all(not any(str(parent) in links for parent in pathlib.PurePosixPath(name).parents)
                for _, _, _, name in result), 'A ZIP member is nested beneath a symbolic link.')
    return result


def extract_cache(cache_zip, destination):
    destination = pathlib.Path(destination)
    require(not destination.exists(), 'Cache extraction destination must be new.')
    with zipfile.ZipFile(cache_zip) as archive:
        layout = archive_layout(archive)
        require(archive.testzip() is None, 'Cache ZIP checksum failed.')
        destination.mkdir(parents=True)
        for entry, mode, target, name in layout:
            output = destination.joinpath(*pathlib.PurePosixPath(name).parts)
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
        for entry, _, target, name in layout:
            if target is not None:
                output = destination.joinpath(*pathlib.PurePosixPath(name).parts)
                output.parent.mkdir(parents=True, exist_ok=True)
                output.symlink_to(target)


def main():
    parser = argparse.ArgumentParser()
    for name in ('run', 'artifact', 'release', 'source-zip', 'cache-zip', 'destination', 'receipt', 'pin', 'artifact-archive'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--source-directory', required=True)
    args = parser.parse_args()
    read = lambda path: json.loads(pathlib.Path(path).read_text(encoding='utf-8-sig'))
    run, artifact, release = read(args.run), read(args.artifact), read(args.release)
    pin = validate_pin(read(args.pin))
    verify_provenance(run, artifact, release, args.source_zip, args.source_directory, pin)
    verify_artifact_archive(args.artifact_archive, pin)
    cache_hash = sha256(args.cache_zip)
    extract_cache(args.cache_zip, args.destination)
    verifier = pathlib.Path(__file__).resolve().parent.parent / 'verify-simulator.py'
    spec = importlib.util.spec_from_file_location('verify_simulator', verifier)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    app = module.verify_product(args.destination)
    receipt = dict(IDENTITY, sourceSha256=SOURCE_SHA, cacheRun=RUN, cacheCommit=COMMIT,
                   artifactId=pin['artifactId'], artifactDigest=pin['artifactDigest'], cacheSha256=cache_hash,
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
