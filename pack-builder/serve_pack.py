#!/usr/bin/env python3
"""
Serve built packs over the local network so a phone can install them.

    python serve_pack.py ./out

Prints the manifest URL to paste into Settings -> Translation -> Manifest URL. Read-only, and
bound to the LAN rather than the internet: the weights never leave the network they were built
on, which is the point of doing translation on-device in the first place.
"""

from __future__ import annotations

import argparse
import http.server
import socket
import socketserver
from pathlib import Path


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".onnx": "application/octet-stream",
        ".json": "application/json",
    }

    def end_headers(self) -> None:
        # The app fetches the manifest and the weights from the same origin; no browser is
        # involved, but CORS costs nothing and makes the URL testable from one.
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def log_message(self, fmt: str, *args) -> None:
        print(f"  {self.address_string()} - {fmt % args}", flush=True)


def lan_addresses() -> list[str]:
    """Shared with the builder so both agree on which address the phone should be given."""
    from build_pack import lan_addresses as candidates

    return candidates()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, nargs="?", default=Path("out"))
    parser.add_argument("--port", type=int, default=8770)
    args = parser.parse_args()

    directory = args.directory.resolve()
    if not directory.exists():
        raise SystemExit(f"{directory} does not exist - build a pack first")

    manifest = directory / "manifest.json"
    if not manifest.exists():
        print(f"WARNING: no manifest.json in {directory}")

    addresses = lan_addresses()

    class Rooted(Handler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=str(directory), **kw)

    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("0.0.0.0", args.port), Rooted) as httpd:
        print(f"Serving {directory}")
        print()
        print("  Paste this into Settings -> Translation -> Manifest URL:")
        print(f"      http://{addresses[0]}:{args.port}/manifest.json")
        if len(addresses) > 1:
            print()
            print("  This machine has more than one address. If the phone cannot reach the")
            print("  one above (a connected VPN is the usual reason), try:")
            for other in addresses[1:]:
                print(f"      http://{other}:{args.port}/manifest.json")
        print()
        print("  Phone and PC must be on the same Wi-Fi. Ctrl+C to stop.")
        print()
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nstopped")


if __name__ == "__main__":
    main()
