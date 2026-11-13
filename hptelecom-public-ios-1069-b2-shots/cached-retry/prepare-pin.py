#!/usr/bin/env python3
"""Pin build 2's cache after checking authenticated API metadata and its ZIP.

The caller must obtain --run and --artifact from authenticated GitHub API GETs.
This helper has no credentials or network access and never changes app binaries.
The output is created exclusively; an existing reviewed pin is not overwritten.
"""
import argparse
import importlib.util
import json
import pathlib

VERIFIER = pathlib.Path(__file__).resolve().with_name('verify-cache.py')
spec = importlib.util.spec_from_file_location('verify_cache', VERIFIER)
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


def prepare_pin(run, artifact, archive):
    pin = cache.verify_metadata(run, artifact)
    cache.verify_artifact_archive(archive, pin)
    return pin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('run', 'artifact', 'archive', 'output'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    read = lambda path: json.loads(pathlib.Path(path).read_text(encoding='utf-8-sig'))
    pin = prepare_pin(read(args.run), read(args.artifact), args.archive)
    with pathlib.Path(args.output).open('x', encoding='utf-8') as output:
        output.write(json.dumps(pin, indent=2, sort_keys=True) + '\n')
    print('Verified build 2 simulator artifact; cache pin created.')


if __name__ == '__main__':
    main()
