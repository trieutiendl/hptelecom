"""Verify that the IPA belongs to the public iPhone app in this directory."""
import argparse
import hashlib
import json
import plistlib
import sys
import zipfile
from pathlib import Path


def verify(ipa, config, expected_bundle, expected_version, expected_build):
    expo = json.loads(config.read_text(encoding="utf-8-sig"))["expo"]
    ios = expo["ios"]
    if expo["name"] != "hptelecom":
        raise ValueError("The public product must use its own store display name.")
    target = (expected_bundle, expected_version, expected_build)
    if target != ("vn.hptelecom.fieldwork", "1.0.69", "4"):
        raise ValueError("This release only accepts the public iPhone product vn.hptelecom.fieldwork 1.0.69 (4).")
    if (ios["bundleIdentifier"], expo["version"], str(ios["buildNumber"])) != target:
        raise ValueError("The release identity differs from the app configuration.")
    with zipfile.ZipFile(ipa) as bundle:
        infos = [entry for entry in bundle.namelist()
                 if entry.startswith("Payload/") and entry.count("/") == 2
                 and entry.endswith(".app/Info.plist")]
        if len(infos) != 1:
            raise ValueError("Expected exactly one application in the IPA.")
        info = plistlib.loads(bundle.read(infos[0]))
        identity = (info["CFBundleIdentifier"], info["CFBundleShortVersionString"],
                    str(info["CFBundleVersion"]))
        if identity != target or info.get("CFBundleDisplayName") != expo["name"]:
            raise ValueError("The binary does not match the public iPhone app.")
        if info.get("UIDeviceFamily") != [1] or info.get("CFBundleSupportedPlatforms") != ["iPhoneOS"]:
            raise ValueError("Expected a release build for physical iPhones.")
        prefix = infos[0].removesuffix("Info.plist")
        if (prefix + "embedded.mobileprovision" not in bundle.namelist() or
                prefix + "_CodeSignature/CodeResources" not in bundle.namelist()):
            raise ValueError("Signing material is absent from the application archive.")
        error = bundle.testzip()
        if error:
            raise ValueError("IPA checksum validation failed for " + error)
    with ipa.open("rb") as binary:
        digest = hashlib.sha256()
        for chunk in iter(lambda: binary.read(1024 * 1024), b""):
            digest.update(chunk)
    return {
        "name": expo["name"], "bundleIdentifier": target[0], "version": target[1],
        "build": target[2], "ipaBytes": ipa.stat().st_size, "ipaSha256": digest.hexdigest(),
        "sdkName": info.get("DTSDKName"), "xcodeVersion": info.get("DTXcode"),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ipa", type=Path)
    parser.add_argument("--app-config", type=Path,
                        default=Path(__file__).resolve().parents[1] / "app" / "app.json")
    parser.add_argument("--bundle-id", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--build", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.ipa, args.app_config, args.bundle_id, args.version, args.build)))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
