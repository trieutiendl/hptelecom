"""Validate actual simulator captures and tie their hashes to the frozen app source."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zlib


NAMES = ("01-home.png", "02-incident.png", "03-cable-materials.png", "04-route-list.png",
         "05-account-access.png")
SIZE = (1320, 2868)


def inspect_png(path):
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError(f"{path.name}: expected PNG")
    offset = 8
    image_data = False
    while offset + 12 <= len(data):
        size, kind = struct.unpack_from(">I4s", data, offset)
        end = offset + 8 + size
        if end + 4 > len(data):
            raise ValueError(f"{path.name}: truncated chunk")
        if zlib.crc32(data[offset + 4:end]) != struct.unpack_from(">I", data, end)[0]:
            raise ValueError(f"{path.name}: invalid chunk checksum")
        if offset == 8:
            if kind != b"IHDR" or size != 13 or struct.unpack_from(">II", data, offset + 8) != SIZE:
                raise ValueError(f"{path.name}: expected a {SIZE[0]} x {SIZE[1]} screenshot")
        if kind == b"IDAT" and size:
            image_data = True
        offset = end + 4
        if kind == b"IEND" and size == 0 and offset == len(data) and image_data:
            return hashlib.sha256(data).hexdigest()
    raise ValueError(f"{path.name}: incomplete PNG")


def verify(directory, release_path, partial=False):
    entries = list(directory.iterdir())
    actual = {file.name for file in entries}
    if (not actual or not actual <= set(NAMES) or
            (not partial and actual != set(NAMES)) or
            any(file.is_symlink() or not file.is_file() for file in entries)):
        raise ValueError("Screenshot folder must contain only the expected regular PNG captures.")
    release = json.loads(release_path.read_text(encoding="utf-8-sig"))
    hashes = {name: inspect_png(directory / name) for name in NAMES if name in actual}
    if len(set(hashes.values())) != len(hashes):
        raise ValueError("Duplicate screenshot images.")
    receipt = {**{key: release[key] for key in ("bundleIdentifier", "version", "build", "sourceSha256")},
               "width": SIZE[0], "height": SIZE[1], "files": hashes}
    if partial:
        receipt["complete"] = False
    return receipt


if __name__ == "__main__":
    if sys.argv[3:] not in ([], ["--partial"]):
        sys.exit("Expected only optional --partial after the two paths.")
    print(json.dumps(verify(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3:] == ["--partial"]), indent=2))
