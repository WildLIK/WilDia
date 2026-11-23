#!/usr/bin/env python3
"""
Generates GitHub Releases manifests for OBD2:
  1) release-manifest.json (for ApkUpdateManager)
  2) db-manifest.json (for DiagnosticDbUpdater, optionally signed with ECDSA P-256 private key)
Private keys are NEVER stored in the repository; pass --ec-private-pem externally.
"""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser(description="Build OBD2 release-manifest.json")
    p.add_argument("--apk", required=True, type=Path)
    p.add_argument("--version-code", required=True, type=int)
    p.add_argument("--version-name", required=True)
    p.add_argument("--apk-url", required=True)
    p.add_argument("--changelog", default="")
    p.add_argument("--out", default=Path("release-manifest.json"), type=Path)
    args = p.parse_args()

    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "changelog": args.changelog,
        "apkUrl": args.apk_url,
        "sizeBytes": args.apk.stat().st_size,
        "sha256": sha256_file(args.apk),
        "minAndroidSdk": 26,
        "releasedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    args.out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {args.out} (sha256={manifest['sha256']})")


if __name__ == "__main__":
    main()
