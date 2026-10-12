#!/usr/bin/env python3
"""Inspect the built APK, its offline world payload, native ABIs, and release checksum."""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile

EXPECTED_PACKAGE = "ai.nauru.countryside"
EXPECTED_VERSION = "0.1.0"
EXPECTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    parser.add_argument("--aapt", type=pathlib.Path, required=True)
    parser.add_argument("--out-dir", type=pathlib.Path, required=True)
    args = parser.parse_args()
    apk = args.apk.resolve()
    if not apk.is_file() or apk.stat().st_size < 1_000_000:
        raise SystemExit(f"APK is missing or implausibly small: {apk}")
    result = subprocess.run([str(args.aapt), "dump", "badging", str(apk)],
                            check=True, text=True, capture_output=True)
    text = result.stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text, re.M)
    sdk = re.search(r"^sdkVersion:'([^']+)'", text, re.M)
    if not package or package.group(1) != EXPECTED_PACKAGE:
        raise SystemExit(f"Unexpected or unreadable Android package: {package.group(1) if package else 'missing'}")
    if package.group(3) != EXPECTED_VERSION:
        raise SystemExit(f"Unexpected version name: {package.group(3)}")
    if not sdk or int(sdk.group(1)) != 26:
        raise SystemExit(f"APK minSdkVersion must be 26, got {sdk.group(1) if sdk else 'missing'}")

    with zipfile.ZipFile(apk) as archive:
        bad_member = archive.testzip()
        if bad_member:
            raise SystemExit(f"Corrupt ZIP entry in APK: {bad_member}")
        names = set(archive.namelist())
        world_path = "assets/compiled/world.bin"
        if world_path not in names:
            raise SystemExit("Offline world payload is missing from APK")
        required_notices = {"assets/compiled/manifest.json", "assets/compiled/source-manifest.json",
                            "assets/licenses/ASSET_LICENSES.txt", "assets/licenses/DATA_SOURCES.txt"}
        missing_notices = required_notices - names
        if missing_notices:
            raise SystemExit(f"Offline provenance/license notices missing from APK: {sorted(missing_notices)}")
        source_manifest = json.loads(archive.read("assets/compiled/source-manifest.json"))
        if not source_manifest.get("sources") or len(source_manifest["sources"]) < 2:
            raise SystemExit("Packaged source manifest lacks OSM/SRTM provenance records")
        world = archive.read(world_path)
        if len(world) < 160 or world[:4] != b"NCRW":
            raise SystemExit("Packaged NCRW world header is invalid")
        if hashlib.sha256(world[:-32]).digest() != world[-32:]:
            raise SystemExit("Packaged NCRW world checksum is invalid")
        abis = {name.split("/")[1] for name in names if name.startswith("lib/") and len(name.split("/")) > 2}
        if not EXPECTED_ABIS.issubset(abis):
            raise SystemExit(f"APK is missing required native ABIs: {sorted(EXPECTED_ABIS - abis)}")
        for abi in EXPECTED_ABIS:
            if f"lib/{abi}/libgdx.so" not in names:
                raise SystemExit(f"libgdx native library missing for {abi}")
            if f"lib/{abi}/libgdx-bullet.so" not in names:
                raise SystemExit(f"Bullet native library missing for {abi}")

    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    sidecar = args.out_dir / (apk.name + ".sha256")
    sidecar.write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
    metadata = {
        "filename": apk.name,
        "bytes": apk.stat().st_size,
        "sha256": digest,
        "package": package.group(1),
        "version_code": int(package.group(2)),
        "version_name": package.group(3),
        "min_sdk": int(sdk.group(1)),
        "abis": sorted(abis & EXPECTED_ABIS),
        "offline_world_bytes": len(world),
        "offline_world_sha256": hashlib.sha256(world).hexdigest(),
        "validation": "ZIP integrity, manifest badging, expected ABIs/native libraries, NCRW header and SHA-256",
        "physical_device_tested": False,
    }
    (args.out_dir / "build-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
