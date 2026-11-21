"""Copy original, full-resolution XCTest screenshots of the personal iPhone app."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import sys


NAMES = ("01-home", "02-incident", "03-cable-materials", "04-route-list", "05-account-access")


def collect(source, destination, partial=False):
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    selected = {}
    digests = set()
    for group in manifest:
        if group.get("testIdentifier") != "PublicScreenshots/testStoreScreenshots()":
            continue
        for item in group["attachments"]:
            title = item.get("suggestedHumanReadableName", "")
            match = re.match(r"^(\d{2}-[a-z-]+)(?:_|\.png$|$)", title)
            if not match or match[1] not in NAMES:
                continue
            name = match[1]
            filename = item["exportedFileName"]
            if Path(filename).name != filename or not filename.lower().endswith(".png"):
                raise ValueError("Invalid XCTest screenshot path.")
            if (name in selected or item.get("isAssociatedWithFailure") or
                    item.get("deviceName") not in {"iPhone 17 Pro Max", "iPhone 16 Pro Max"}):
                raise ValueError("Duplicate, failed, or unexpected simulator screenshot.")
            path = source / filename
            raw = path.read_bytes()
            if (raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR" or
                    struct.unpack(">II", raw[16:24]) != (1320, 2868)):
                raise ValueError("Expected an unedited 1320 x 2868 portrait PNG.")
            digest = hashlib.sha256(raw).digest()
            if digest in digests:
                raise ValueError("Two screenshots show identical pixels.")
            digests.add(digest)
            selected[name] = path
    if not partial and set(selected) != set(NAMES):
        raise ValueError(f"Missing expected screenshots: {sorted(set(NAMES) - selected.keys())}")
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        raise ValueError("Output directory must be empty.")
    for name, path in selected.items():
        shutil.copyfile(path, destination / (name + ".png"))


if __name__ == "__main__":
    collect(Path(sys.argv[1]), Path(sys.argv[2]), partial=sys.argv[3:] == ["--partial"])
