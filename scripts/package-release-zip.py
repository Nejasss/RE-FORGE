#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import shutil
import sys
import zipfile
from datetime import datetime, timezone

def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(131072), b""):
            h.update(chunk)
    return h.hexdigest()

def main():
    if len(sys.argv) != 5:
        print("Usage: package-release-zip.py <repo_root> <verified_apk_path> <version> <output_zip_path>")
        sys.exit(2)

    repo_root = pathlib.Path(sys.argv[1]).resolve()
    apk_path = pathlib.Path(sys.argv[2]).resolve()
    version = sys.argv[3]
    out_zip = pathlib.Path(sys.argv[4]).resolve()

    if not apk_path.is_file() or apk_path.stat().st_size == 0:
        raise FileNotFoundError(f"Verified APK not found or empty: {apk_path}")

    staging = repo_root / "build" / "release_bundle_staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    target_apk = staging / "RE-FORGE.apk"
    shutil.copy2(apk_path, target_apk)

    shutil.copytree(repo_root / "DOCUMENTATION", staging / "DOCUMENTATION")
    shutil.copytree(repo_root / "LICENSES", staging / "LICENSES")

    ws_template = staging / "WORKSPACE-TEMPLATE"
    ws_template.mkdir(parents=True)
    (ws_template / "README.txt").write_text(
        "RE FORGE Isolated Workspace Layout\n"
        "workspaces/<projectId>/original/  -> Read-only imported target\n"
        "workspaces/<projectId>/working/   -> Mutable working copy\n"
        "workspaces/<projectId>/staging/   -> Staged Smali & overlay edits\n"
        "workspaces/<projectId>/build/     -> Aligned & signed APK artifacts\n",
        encoding="utf-8"
    )

    checksums = []
    manifest_files = []
    for p in sorted(staging.rglob("*")):
        if p.is_file():
            rel = p.relative_to(staging).as_posix()
            digest = sha256_file(p)
            checksums.append(f"{digest}  {rel}")
            manifest_files.append({
                "path": rel,
                "sizeBytes": p.stat().st_size,
                "sha256": digest
            })

    (staging / "SHA256SUMS.txt").write_text("\n".join(checksums) + "\n", encoding="utf-8")

    manifest = {
        "name": "RE-FORGE-FULL-ARM64",
        "applicationId": "org.bimz.reforge",
        "version": version,
        "targetAbi": "arm64-v8a",
        "minSdk": 26,
        "targetSdk": 35,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "apkSha256": sha256_file(target_apk),
        "files": manifest_files
    }
    (staging / "MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    out_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for p in sorted(staging.rglob("*")):
            if p.is_file():
                zf.write(p, p.relative_to(staging).as_posix())

    print(f"Packaged {out_zip} ({out_zip.stat().st_size} bytes)")

if __name__ == "__main__":
    main()
