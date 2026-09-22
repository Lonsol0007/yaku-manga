"""Owns the on-disk model cache.

A port of `yaku.translation.store.ModelRepository`. On Android everything lives under the
app's private files directory; here it lives under a user data directory, and the layout
inside - one directory per pack id, holding the ONNX files and a `pack.json` descriptor - is
identical, so a pack copied off a phone works unchanged and vice versa.

OkHttp and Kotlin Flow become `urllib` and a progress callback. Nothing is fetched unless
the caller explicitly asks for a pack.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path

from .manifest import ModelManifest, ModelPack, PackConfig

log = logging.getLogger(__name__)

PACK_DESCRIPTOR = "pack.json"
MODELS_DIR = "translation-models"
_DOWNLOAD_CHUNK = 64 * 1024
_PROGRESS_INTERVAL_S = 0.15
"""Progress is reported on this cadence rather than per chunk: a 263MB pack read in 64KB
chunks is thousands of callbacks, and a caller drawing a progress bar does not need them."""


def default_models_root() -> Path:
    """Where packs live when the caller does not say.

    Mirrors the Android app's `filesDir/translation-models` with the platform's usual data
    directory; `YAKU_TRANSLATE_HOME` overrides it, which is what the tests and CI use.
    """
    override = os.environ.get("YAKU_TRANSLATE_HOME")
    if override:
        return Path(override).expanduser() / MODELS_DIR
    base = os.environ.get("XDG_DATA_HOME")
    root = Path(base).expanduser() if base else Path.home() / ".local" / "share"
    return root / "yaku-translate" / MODELS_DIR


@dataclass(frozen=True)
class DownloadProgress:
    pack_id: str
    current_file: str | None
    """None once every file is done."""
    bytes_done: int
    bytes_total: int

    @property
    def fraction(self) -> float:
        if self.bytes_total <= 0:
            return 0.0
        return min(max(self.bytes_done / self.bytes_total, 0.0), 1.0)


@dataclass(frozen=True)
class SourceResults:
    packs: list[ModelPack]
    failures: dict[str, str]


class ModelRepository:
    def __init__(
        self,
        root: Path | None = None,
        *,
        timeout: float = 30.0,
        user_agent: str = "yaku-translate",
    ) -> None:
        self.root = Path(root) if root is not None else default_models_root()
        self._timeout = timeout
        self._user_agent = user_agent

    # -- layout ------------------------------------------------------------------------

    def pack_dir(self, pack_id: str) -> Path:
        return self.root / pack_id

    def installed_file(self, pack_id: str, file_name: str) -> Path:
        return self.pack_dir(pack_id) / file_name

    def is_installed(self, pack: ModelPack) -> bool:
        """Whether every file in the pack is present and non-empty.

        Hashes are verified at download time and not re-checked here - re-hashing a few
        hundred megabytes on every open would be far more expensive than the failure it
        guards against.
        """
        directory = self.pack_dir(pack.id)
        return all(
            (directory / f.name).exists() and (directory / f.name).stat().st_size > 0 for f in pack.files
        )

    def installed_pack(self, pack_id: str) -> ModelPack | None:
        """The descriptor written at download time, or None if the pack was never installed.

        Reading it back rather than re-fetching the manifest is what lets a downloaded pack
        work with no network access, which is the whole point of the feature.
        """
        descriptor = self.pack_dir(pack_id) / PACK_DESCRIPTOR
        if not descriptor.exists():
            return None
        try:
            pack = ModelPack.from_dict(json.loads(descriptor.read_text(encoding="utf-8")))
        except Exception:
            # Without this a malformed descriptor makes the pack silently invisible, which is
            # indistinguishable from never having downloaded it.
            log.exception("Unreadable pack descriptor for %s", pack_id)
            return None
        return _retuned(pack)

    def installed_packs(self) -> list[ModelPack]:
        if not self.root.exists():
            return []
        found = [self.installed_pack(d.name) for d in sorted(self.root.iterdir()) if d.is_dir()]
        return [p for p in found if p is not None]

    def installed_bytes(self) -> int:
        if not self.root.exists():
            return 0
        return sum(f.stat().st_size for f in self.root.rglob("*") if f.is_file())

    def delete(self, pack_id: str) -> None:
        shutil.rmtree(self.pack_dir(pack_id), ignore_errors=True)

    # -- manifests ---------------------------------------------------------------------

    def fetch_manifest(self, manifest_url: str) -> ModelManifest:
        body = self._get(manifest_url).decode("utf-8", errors="replace")

        # Check the shape before parsing. Handing HTML to the JSON parser produces a message
        # about an unexpected token at some offset, which describes the symptom and hides the
        # cause: something answered instead of the server.
        head = body.lstrip()
        if not head.startswith("{"):
            if head.startswith("<"):
                raise RuntimeError(
                    "Got a web page instead of the manifest. A captive portal, ISP filter, "
                    "DNS blocker or proxy is intercepting this request - the server itself "
                    "answered successfully."
                )
            raise RuntimeError(f"Manifest was not JSON ({len(body)} bytes)")

        manifest = ModelManifest.from_dict(json.loads(body))
        # Stamp the origin onto every pack so an install can tell two same-named packs apart.
        return ModelManifest(
            version=manifest.version,
            packs=tuple(p.replace(source=manifest_url) for p in manifest.packs),
        )

    def fetch_all(self, sources: Iterable[str]) -> SourceResults:
        """Fetches every configured source, keeping whichever ones answer.

        One unreachable third-party source must not hide the packs the others offer, so
        failures are collected and returned alongside the results instead of raised.
        """
        packs: list[ModelPack] = []
        failures: dict[str, str] = {}

        seen: set[str] = set()
        for source in (s.strip() for s in sources):
            if not source or source in seen:
                continue
            seen.add(source)
            try:
                packs.extend(self.fetch_manifest(source).packs)
            except Exception as error:
                # Both the type and the message. Network exceptions frequently carry no
                # message at all, and an empty string on screen tells the user nothing they
                # can act on or report.
                detail = str(error).strip()
                failures[source] = f"{type(error).__name__}: {detail}" if detail else type(error).__name__
                log.error("Pack source failed: %s", source, exc_info=True)
        return SourceResults(packs=packs, failures=failures)

    # -- download ----------------------------------------------------------------------

    def download(
        self,
        pack: ModelPack,
        on_progress: Callable[[DownloadProgress], None] | None = None,
    ) -> ModelPack:
        """Downloads every file in ``pack`` that is not already present and verified.

        Partial downloads are written to a `.part` file and only moved into place once the
        SHA-256 matches, so an interrupted download can never leave a corrupt model that
        fails at inference time.
        """
        report = on_progress or (lambda _p: None)

        # Packs live in a directory named after their id, so a third-party manifest offering
        # "ja-en-base" would otherwise silently overwrite the installed pack of that name.
        # Refuse instead: replacing someone's working models with an unrelated download,
        # because two authors picked the same string, is not a decision to make for them.
        existing = self.installed_pack(pack.id)
        if existing is not None and existing.source and pack.source and existing.source != pack.source:
            raise RuntimeError(
                f"A pack with id '{pack.id}' is already installed from {existing.source}. "
                f"Remove it before installing the one from {pack.source}."
            )

        directory = self.pack_dir(pack.id)
        directory.mkdir(parents=True, exist_ok=True)
        total = max(pack.total_bytes, 1)
        completed = 0

        for entry in pack.files:
            target = directory / entry.name
            if target.exists() and _sha256(target).lower() == entry.sha256.lower():
                completed += target.stat().st_size
                report(DownloadProgress(pack.id, entry.name, completed, total))
                continue

            part = directory / (entry.name + ".part")
            part.unlink(missing_ok=True)

            digest = hashlib.sha256()
            last_report = 0.0
            with self._open(entry.url) as response, part.open("wb") as sink:
                while True:
                    chunk = response.read(_DOWNLOAD_CHUNK)
                    if not chunk:
                        break
                    sink.write(chunk)
                    digest.update(chunk)
                    completed += len(chunk)
                    now = time.monotonic()
                    if now - last_report >= _PROGRESS_INTERVAL_S:
                        last_report = now
                        report(DownloadProgress(pack.id, entry.name, completed, total))

            actual = digest.hexdigest()
            if actual.lower() != entry.sha256.lower():
                part.unlink(missing_ok=True)
                raise RuntimeError(
                    f"Checksum mismatch for {entry.name}: expected {entry.sha256}, got {actual}"
                )
            part.replace(target)

        # Record what was installed so the pack can be loaded later with no network access.
        (directory / PACK_DESCRIPTOR).write_text(
            json.dumps(pack.to_dict(), indent=2, ensure_ascii=False), encoding="utf-8"
        )
        report(DownloadProgress(pack.id, None, total, total))
        return pack

    # -- http --------------------------------------------------------------------------

    def _request(self, url: str) -> urllib.request.Request:
        return urllib.request.Request(
            url,
            headers={
                "User-Agent": self._user_agent,
                # Never serve this from a cache. A captive portal or filtering middlebox
                # answers with a cacheable success carrying an HTML page, and once that is
                # cached every later attempt replays it even after the network is fixed.
                "Cache-Control": "no-cache",
                "Pragma": "no-cache",
            },
        )

    def _open(self, url: str):
        try:
            return urllib.request.urlopen(self._request(url), timeout=self._timeout)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Request failed for {url}: HTTP {error.code}") from error

    def _get(self, url: str) -> bytes:
        with self._open(url) as response:
            return response.read()


def _retuned(pack: ModelPack) -> ModelPack:
    """Replaces detector settings that predate the current tuning.

    The trio below was measured against a page with known text: the values shipped with the
    first packs found none to two of its four speech bubbles and split those into single
    glyphs. Because descriptors are never rewritten, a pack downloaded then would keep those
    numbers forever - so the only way to deliver three corrected floats would be to make the
    user delete and re-download the whole pack. A pack that sets ``config_version`` is left
    exactly as its author built it.
    """
    if pack.config.config_version >= PackConfig.TUNED_DETECTOR:
        return pack
    tuned = PackConfig()
    from dataclasses import replace as _replace

    return pack.replace(
        config=_replace(
            pack.config,
            config_version=PackConfig.TUNED_DETECTOR,
            detector_threshold=tuned.detector_threshold,
            detector_box_expand=tuned.detector_box_expand,
            detector_merge_slop=tuned.detector_merge_slop,
        )
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1 << 16)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()
