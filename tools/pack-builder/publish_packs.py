#!/usr/bin/env python3
"""
Stage built packs for hosting on a GitHub release.

Release assets are a flat namespace, so every pack's `detector.onnx` would collide. Assets are
therefore named by content hash, which also deduplicates: packs that share a detector and
recogniser - as every pack using the same OCR model does - are uploaded once and referenced
twice. Only the URL changes; `name` stays the plain filename the app writes to disk.

    python publish_packs.py out/ja-en-base out/zh-en-base --tag packs-v1

Writes <staging>/manifest.json plus the deduplicated assets, ready for:

    gh release create packs-v1 <staging>/* --repo <owner>/<repo>
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROLES = [
    "detector",
    "recognizer_encoder",
    "recognizer_decoder",
    "recognizer_vocab",
    "translator_encoder",
    "translator_decoder",
    "translator_vocab",
]


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("packs", type=Path, nargs="+", help="built pack directories")
    parser.add_argument("--repo", default="Lonsol0007/yaku-manga")
    parser.add_argument("--tag", default="packs-v1")
    parser.add_argument("--staging", type=Path, default=Path("out/publish"))
    args = parser.parse_args()

    base = f"https://github.com/{args.repo}/releases/download/{args.tag}"
    staging: Path = args.staging
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    uploaded: dict[str, str] = {}  # sha256 -> asset name
    manifest_packs = []
    total_bytes = 0
    saved_bytes = 0

    for pack_dir in args.packs:
        meta = json.loads((pack_dir / "pack.json").read_text(encoding="utf-8"))
        print(f"\n{meta['id']}  ({meta['name']})")

        for role in ROLES:
            entry = meta[role]
            source = pack_dir / entry["name"]
            digest = entry.get("sha256") or sha256_of(source)
            size = source.stat().st_size

            if digest in uploaded:
                saved_bytes += size
                print(f"  {entry['name']:<26} reuses {uploaded[digest]}")
            else:
                # Content-addressed, with the original name kept as a readable suffix.
                asset = f"{digest[:12]}-{entry['name']}"
                shutil.copyfile(source, staging / asset)
                uploaded[digest] = asset
                total_bytes += size
                print(f"  {entry['name']:<26} -> {asset}  ({size / 1e6:.1f} MB)")

            entry["url"] = f"{base}/{uploaded[digest]}"
            entry["sha256"] = digest
            entry["size_bytes"] = size

        # `source` is stamped in by the app at fetch time; a value here would be ignored anyway.
        meta.pop("source", None)
        manifest_packs.append(meta)

    manifest = {"version": 1, "packs": manifest_packs}
    (staging / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    print(f"\nstaged {len(uploaded)} assets, {total_bytes / 1e6:.0f} MB")
    if saved_bytes:
        print(f"deduplicated {saved_bytes / 1e6:.0f} MB of shared detector/recogniser files")
    print(f"manifest -> {staging / 'manifest.json'}")
    print(f"\ngh release create {args.tag} {staging}/* --repo {args.repo}")


if __name__ == "__main__":
    main()
