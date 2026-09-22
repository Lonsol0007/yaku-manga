"""Command line for the pipeline: manage packs, translate a page or a whole volume.

This is what the Android app's Settings -> Translation screen and reader do, addressed from
a terminal instead. Same packs, same manifests, same models directory layout.
"""

from __future__ import annotations

import argparse
import json
import logging
import re
import sys
import zipfile
from collections.abc import Sequence
from pathlib import Path

from PIL import Image

from .engine import OnnxTranslationEngine
from .manifest import ModelPack
from .model import PageTranslation, TranslationLanguage, TranslationProgress
from .render import RenderStyle, TranslationRenderer
from .repository import DownloadProgress, ModelRepository

PACK_SOURCE_V1 = "https://github.com/Lonsol0007/yaku-manga/releases/download/packs-v1/manifest.json"
"""Packs published alongside the app's own releases.

A release tag rather than a branch path: the manifest records a SHA-256 per file, so it has
to stay pinned to the exact files it was generated against.
"""

PACK_SOURCE_V2 = "https://github.com/Lonsol0007/yaku-manga/releases/download/packs-v2/manifest.json"
"""Packs that need a comic detector, which older app versions cannot run."""

DEFAULT_PACK_SOURCES = (PACK_SOURCE_V1, PACK_SOURCE_V2)

IMAGE_SUFFIXES = frozenset({".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif"})


# --------------------------------------------------------------------------------------
# Argument parsing
# --------------------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="yaku-translate",
        description="On-device manga translation: detect, read, translate and letter a page.",
    )
    parser.add_argument("--models-root", type=Path, default=None, help="Where packs are stored.")
    parser.add_argument("-v", "--verbose", action="store_true", help="Log what each stage does.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    packs = subparsers.add_parser("packs", help="List, install and remove translation packs.")
    pack_actions = packs.add_subparsers(dest="pack_command", required=True)

    listing = pack_actions.add_parser("list", help="Show the packs a manifest offers.")
    listing.add_argument("--source", action="append", default=None, help="Manifest URL; repeatable.")

    install = pack_actions.add_parser("install", help="Download a pack and verify it.")
    install.add_argument("pack_id")
    install.add_argument("--source", action="append", default=None, help="Manifest URL; repeatable.")

    pack_actions.add_parser("installed", help="Show the packs already on disk.")

    remove = pack_actions.add_parser("remove", help="Delete an installed pack.")
    remove.add_argument("pack_id")

    page = subparsers.add_parser("page", help="Translate one page image.")
    _add_translate_arguments(page)
    page.add_argument("input", type=Path)
    page.add_argument("-o", "--output", type=Path, default=None, help="Where to write the page.")
    page.add_argument("--json", type=Path, default=None, help="Also write the blocks as JSON.")

    batch = subparsers.add_parser("batch", help="Translate a folder of pages, or a .cbz.")
    _add_translate_arguments(batch)
    batch.add_argument("input", type=Path, help="A directory of images, or a .cbz/.zip.")
    batch.add_argument("-o", "--output", type=Path, required=True, help="Output directory.")
    batch.add_argument("--cbz", type=Path, default=None, help="Also pack the result into this .cbz.")

    return parser


def _add_translate_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--pack", default=None, help="Pack id; the only installed one by default.")
    parser.add_argument("--from", dest="source_language", default="ja", help="Source language code.")
    parser.add_argument("--to", dest="target_language", default="en", help="Target language code.")
    parser.add_argument("--threads", type=int, default=None, help="ONNX intra-op threads.")
    parser.add_argument("--font", type=Path, default=None, help="Lettering font (.ttf).")
    parser.add_argument("--no-uppercase", action="store_true", help="Leave the lettering as cased.")
    parser.add_argument(
        "--no-render",
        action="store_true",
        help="Run the pipeline but write no image; useful with --json.",
    )


# --------------------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------------------


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.INFO if args.verbose else logging.WARNING,
        format="%(levelname)s %(name)s: %(message)s",
    )
    repository = ModelRepository(args.models_root)

    try:
        if args.command == "packs":
            return _packs_command(args, repository)
        if args.command == "page":
            return _page_command(args, repository)
        if args.command == "batch":
            return _batch_command(args, repository)
    except KeyboardInterrupt:
        print("\nInterrupted.", file=sys.stderr)
        return 130
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        if args.verbose:
            raise
        return 1
    return 2


def _packs_command(args: argparse.Namespace, repository: ModelRepository) -> int:
    if args.pack_command == "installed":
        packs = repository.installed_packs()
        if not packs:
            print(f"No packs installed under {repository.root}")
            return 0
        for pack in packs:
            print(f"{pack.id}\t{pack.name}\t{_human_bytes(pack.total_bytes)}\t{pack.source or '-'}")
        print(f"\n{len(packs)} pack(s), {_human_bytes(repository.installed_bytes())} on disk.")
        return 0

    if args.pack_command == "remove":
        repository.delete(args.pack_id)
        print(f"Removed {args.pack_id}")
        return 0

    sources = tuple(args.source) if args.source else DEFAULT_PACK_SOURCES
    results = repository.fetch_all(sources)
    for source, failure in results.failures.items():
        print(f"warning: {source}: {failure}", file=sys.stderr)

    if args.pack_command == "list":
        if not results.packs:
            print("No packs offered by any source that answered.")
            return 1 if results.failures else 0
        for pack in results.packs:
            languages = (
                f"{'/'.join(pack.source_languages) or '?'} -> {'/'.join(pack.target_languages) or '?'}"
            )
            installed = " [installed]" if repository.is_installed(pack) else ""
            print(f"{pack.id}\t{_human_bytes(pack.total_bytes)}\t{languages}\t{pack.name}{installed}")
            if pack.description:
                print(f"\t{pack.description}")
        return 0

    # install
    pack = next((p for p in results.packs if p.id == args.pack_id), None)
    if pack is None:
        offered = ", ".join(p.id for p in results.packs) or "nothing"
        print(f"error: no pack '{args.pack_id}'. Sources offered: {offered}", file=sys.stderr)
        return 1

    print(f"Installing {pack.id} ({_human_bytes(pack.total_bytes)}) into {repository.pack_dir(pack.id)}")
    repository.download(pack, _print_download_progress)
    print(f"\rInstalled {pack.id}{' ' * 30}")
    return 0


def _page_command(args: argparse.Namespace, repository: ModelRepository) -> int:
    pack = _select_pack(args.pack, repository)
    source, target = _languages(args)
    output = args.output or args.input.with_name(f"{args.input.stem}.translated.png")

    with OnnxTranslationEngine(pack, repository, args.threads) as engine:
        page = Image.open(args.input)
        result = _run_page(engine, page, source, target, args, output)

    if args.json:
        args.json.write_text(json.dumps(result.to_dict(), indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"Wrote {args.json}")
    return 0


def _batch_command(args: argparse.Namespace, repository: ModelRepository) -> int:
    pack = _select_pack(args.pack, repository)
    source, target = _languages(args)
    args.output.mkdir(parents=True, exist_ok=True)

    pages = list(_collect_pages(args.input))
    if not pages:
        print(f"error: no images found in {args.input}", file=sys.stderr)
        return 1

    written: list[Path] = []
    with OnnxTranslationEngine(pack, repository, args.threads) as engine:
        for index, (name, opener) in enumerate(pages, start=1):
            destination = args.output / f"{Path(name).stem}.png"
            print(f"[{index}/{len(pages)}] {name}")
            with opener() as page:
                _run_page(engine, page, source, target, args, destination)
            written.append(destination)

    if args.cbz:
        args.cbz.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.cbz, "w", zipfile.ZIP_STORED) as archive:
            for path in written:
                archive.write(path, path.name)
        print(f"Wrote {args.cbz}")
    return 0


# --------------------------------------------------------------------------------------
# Shared plumbing
# --------------------------------------------------------------------------------------


def _run_page(
    engine: OnnxTranslationEngine,
    page: Image.Image,
    source: TranslationLanguage,
    target: TranslationLanguage,
    args: argparse.Namespace,
    output: Path,
) -> PageTranslation:
    result = engine.translate_page(page, source, target, _print_stage_progress)
    translated = sum(1 for block in result.blocks if block.is_translated)
    print(f"\r{len(result.blocks)} block(s), {translated} translated, {result.elapsed_millis} ms")

    if not args.no_render:
        style = RenderStyle(font_path=args.font, uppercase=not args.no_uppercase)
        rendered = TranslationRenderer(style).render(page.convert("RGB"), result)
        output.parent.mkdir(parents=True, exist_ok=True)
        rendered.save(output)
        print(f"Wrote {output}")
    return result


def _select_pack(pack_id: str | None, repository: ModelRepository) -> ModelPack:
    if pack_id:
        pack = repository.installed_pack(pack_id)
        if pack is None:
            raise RuntimeError(
                f"Pack '{pack_id}' is not installed. Run: yaku-translate packs install {pack_id}"
            )
        return pack

    installed = repository.installed_packs()
    if not installed:
        raise RuntimeError(
            f"No packs installed under {repository.root}. Run: yaku-translate packs list, then packs install <id>"
        )
    if len(installed) > 1:
        names = ", ".join(p.id for p in installed)
        raise RuntimeError(f"Several packs are installed ({names}); pick one with --pack.")
    return installed[0]


def _languages(args: argparse.Namespace) -> tuple[TranslationLanguage, TranslationLanguage]:
    source = TranslationLanguage.from_code(args.source_language)
    target = TranslationLanguage.from_code(args.target_language)
    known = ", ".join(language.code for language in TranslationLanguage)
    if source is None:
        raise RuntimeError(f"Unknown source language '{args.source_language}'. Known: {known}")
    if target is None:
        raise RuntimeError(f"Unknown target language '{args.target_language}'. Known: {known}")
    return source, target


def _collect_pages(source: Path):
    """Yields `(name, opener)` for every page in a directory or a .cbz, in reading order."""
    if source.is_dir():
        for path in sorted(source.iterdir(), key=lambda p: _natural_key(p.name)):
            if path.suffix.lower() in IMAGE_SUFFIXES:
                yield path.name, (lambda p=path: Image.open(p))
        return

    if source.suffix.lower() in {".cbz", ".zip"}:
        archive = zipfile.ZipFile(source)
        names = sorted(
            (n for n in archive.namelist() if Path(n).suffix.lower() in IMAGE_SUFFIXES),
            key=_natural_key,
        )
        for name in names:
            yield name, (lambda n=name: Image.open(archive.open(n)))
        return

    if source.suffix.lower() in IMAGE_SUFFIXES:
        yield source.name, (lambda: Image.open(source))


def _natural_key(name: str) -> tuple:
    """Sorts `page2` before `page10`, which plain string order does not."""
    return tuple(int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", name))


def _print_stage_progress(progress: object) -> None:
    if isinstance(progress, TranslationProgress.Detecting):
        _status("detecting text")
    elif isinstance(progress, TranslationProgress.LoadingModels):
        _status("loading models")
    elif isinstance(progress, TranslationProgress.Recognizing):
        _status(f"reading {progress.done + 1}/{progress.total}")
    elif isinstance(progress, TranslationProgress.Translating):
        _status(f"translating {progress.done + 1}/{progress.total}")


def _print_download_progress(progress: DownloadProgress) -> None:
    name = progress.current_file or "done"
    _status(
        f"{progress.fraction * 100:5.1f}%  "
        f"{_human_bytes(progress.bytes_done)}/{_human_bytes(progress.bytes_total)}  {name}"
    )


def _status(message: str) -> None:
    sys.stdout.write(f"\r{message:<60}")
    sys.stdout.flush()


def _human_bytes(count: int) -> str:
    size = float(count)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f}{unit}" if unit == "B" else f"{size:.1f}{unit}"
        size /= 1024
    return f"{size:.1f}GB"


if __name__ == "__main__":
    raise SystemExit(main())
