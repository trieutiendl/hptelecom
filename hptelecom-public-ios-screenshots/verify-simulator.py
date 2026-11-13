#!/usr/bin/env python3
"""Verify the frozen public app's simulator product without changing it.

Xcode puts simulator permissions in the executable's __TEXT,__entitlements
section. Its ad-hoc code signature can legitimately have an empty entitlement
dictionary, so codesign -d --entitlements alone cannot verify these permissions.
"""
import pathlib
import plistlib
import struct
import subprocess
import sys

BUNDLE = 'vn.hptelecom.fieldwork'
NAMESPACE = 'FAKETEAMID.' + BUNDLE
LC_SEGMENT_64 = 0x19
LC_BUILD_VERSION = 0x32
PLATFORM_IOSSIMULATOR = 7
CPU_TYPES = {0x0100000C, 0x01000007}  # arm64, x86_64


class VerificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def _thin_entitlements(data):
    magic = data[:4]
    require(magic in (b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf'),
            'Expected a 64-bit simulator Mach-O executable.')
    endian = '<' if magic == b'\xcf\xfa\xed\xfe' else '>'
    require(len(data) >= 32, 'Truncated Mach-O header.')
    _, cpu, _, filetype, ncmds, command_size, _, _ = struct.unpack_from(endian + 'IiiIIIII', data)
    require(cpu in CPU_TYPES and filetype == 2, 'Unexpected simulator architecture or Mach-O type.')
    require(0 < ncmds <= 4096 and 32 + command_size <= len(data), 'Invalid Mach-O load commands.')
    cursor, end = 32, 32 + command_size
    entitlements, platforms = [], []
    for _ in range(ncmds):
        require(cursor + 8 <= end, 'Truncated Mach-O load command.')
        command, size = struct.unpack_from(endian + 'II', data, cursor)
        require(size >= 8 and cursor + size <= end, 'Invalid Mach-O load command size.')
        if command == LC_BUILD_VERSION:
            require(size >= 24, 'Truncated platform load command.')
            platforms.append(struct.unpack_from(endian + 'I', data, cursor + 8)[0])
        elif command == LC_SEGMENT_64:
            require(size >= 72, 'Truncated Mach-O segment.')
            segment = struct.unpack_from(endian + 'II16sQQQQiiII', data, cursor)
            segment_name, segment_offset, segment_size, sections = segment[2], segment[5], segment[6], segment[9]
            require(72 + sections * 80 <= size, 'Truncated Mach-O section table.')
            for index in range(sections):
                section = struct.unpack_from(endian + '16s16sQQIIIIIIII', data, cursor + 72 + index * 80)
                name, owner, _, length, offset = section[:5]
                if name.rstrip(b'\0') == b'__entitlements' and owner.rstrip(b'\0') == b'__TEXT':
                    require(segment_name.rstrip(b'\0') == b'__TEXT', 'Entitlements are in the wrong segment.')
                    require(0 < length <= 1024 * 1024 and offset >= end and offset + length <= len(data),
                            'Invalid embedded entitlement bounds.')
                    require(segment_offset <= offset and offset + length <= segment_offset + segment_size,
                            'Entitlement section lies outside its segment.')
                    try:
                        # Linkers may NUL-pad the XML section; no content is otherwise changed.
                        value = plistlib.loads(data[offset:offset + length].rstrip(b'\0'))
                    except Exception as exc:
                        raise VerificationError('Embedded simulator entitlements are not a valid plist.') from exc
                    require(isinstance(value, dict), 'Embedded simulator entitlements are not a dictionary.')
                    entitlements.append(value)
        cursor += size
    require(cursor == end, 'Mach-O load command lengths disagree.')
    require(platforms == [PLATFORM_IOSSIMULATOR], 'Executable is not an iOS Simulator product.')
    require(len(entitlements) == 1, 'Expected one embedded simulator entitlement section.')
    return entitlements[0]


def simulator_entitlements(data):
    """Return checked entitlement dictionaries for every thin/fat slice."""
    fat_formats = {b'\xca\xfe\xba\xbe': ('>', False), b'\xbe\xba\xfe\xca': ('<', False),
                   b'\xca\xfe\xba\xbf': ('>', True), b'\xbf\xba\xfe\xca': ('<', True)}
    if data[:4] not in fat_formats:
        values = [_thin_entitlements(data)]
    else:
        endian, wide = fat_formats[data[:4]]
        require(len(data) >= 8, 'Truncated universal Mach-O header.')
        count = struct.unpack_from(endian + 'I', data, 4)[0]
        entry_size = 32 if wide else 20
        require(0 < count <= 32 and 8 + count * entry_size <= len(data), 'Invalid universal slice table.')
        values, ranges = [], []
        for index in range(count):
            entry = struct.unpack_from(endian + ('iiQQII' if wide else 'iiIII'), data, 8 + index * entry_size)
            cpu, _, offset, length = entry[:4]
            require(cpu in CPU_TYPES and offset >= 8 + count * entry_size and length >= 32 and offset + length <= len(data),
                    'Invalid universal simulator slice.')
            require(all(offset + length <= a or offset >= b for a, b in ranges), 'Overlapping universal slices.')
            ranges.append((offset, offset + length))
            values.append(_thin_entitlements(data[offset:offset + length]))
    for value in values:
        require(value.get('application-identifier') == NAMESPACE,
                'Simulator application identifier does not match the private namespace.')
        require(value.get('keychain-access-groups') == [NAMESPACE],
                'Simulator private SecureStore access group is missing or unexpected.')
    return values


def verify_product(products, runner=subprocess.run):
    matches = []
    for app in pathlib.Path(products).glob('*.app'):
        require(not app.is_symlink(), 'Simulator product must not be a symbolic link.')
        try:
            with (app / 'Info.plist').open('rb') as stream:
                info = plistlib.load(stream)
        except Exception as exc:
            raise VerificationError('Unable to read simulator product metadata.') from exc
        if info.get('CFBundleIdentifier') == BUNDLE:
            matches.append((app, info))
    require(len(matches) == 1, 'Expected exactly one public app in simulator products.')
    app, info = matches[0]
    require((info.get('CFBundleShortVersionString'), info.get('CFBundleVersion')) == ('1.0.69', '1'),
            'Simulator app has an unexpected version or build.')
    require(info.get('UIDeviceFamily') == [1], 'Simulator app is not iPhone-only.')
    require(info.get('CFBundleSupportedPlatforms') == ['iPhoneSimulator'], 'Unexpected simulator supported platform.')
    executable = info.get('CFBundleExecutable')
    require(isinstance(executable, str) and executable not in ('', '.', '..') and '/' not in executable and '\\' not in executable,
            'Simulator executable name is invalid.')
    binary, bundle = app / executable, app / 'main.jsbundle'
    require(binary.is_file() and not binary.is_symlink(), 'Simulator executable is missing or linked.')
    require(bundle.is_file() and not bundle.is_symlink() and bundle.stat().st_size > 0,
            'Release simulator app is missing its embedded JavaScript.')
    simulator_entitlements(binary.read_bytes())
    checked = runner(['codesign', '--verify', '--deep', '--strict', str(app)], capture_output=True)
    require(checked.returncode == 0, 'Simulator app code signature did not verify.')
    return app


def main():
    if len(sys.argv) != 2:
        sys.exit('Usage: verify-simulator.py SIMULATOR_PRODUCTS_DIRECTORY')
    try:
        verify_product(sys.argv[1])
    except (VerificationError, OSError) as exc:
        sys.exit(str(exc))
    print('Verified simulator app identity, embedded private Keychain namespace, release bundle, and code signature.')


if __name__ == '__main__':
    main()
