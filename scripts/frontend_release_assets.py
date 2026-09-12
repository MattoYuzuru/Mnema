#!/usr/bin/env python3
"""Validate the Angular application builder's immutable entry assets, without fetching URLs."""

from html.parser import HTMLParser
from pathlib import Path
import json
import re
import sys


class EntryParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.assets = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "script" and "src" in values:
            source = values["src"] or ""
            if source != "/app-config.js":
                self.assets.append((source, "js"))
        elif tag == "link" and values.get("rel") in ("stylesheet", "modulepreload"):
            source = values.get("href") or ""
            # Existing third-party font CSS is not served by our immutable asset location.
            if values.get("rel") == "stylesheet" and source.startswith("https://"):
                return
            extension = "css" if values.get("rel") == "stylesheet" else "js"
            self.assets.append((source, extension))


def hashed_assets(html, *, require_zone_polyfills=False):
    parser = EntryParser()
    parser.feed(html)
    assets = []
    for source, extension in parser.assets:
        if not re.fullmatch(r"[A-Za-z0-9_-]+-[A-Za-z0-9_-]{8}\." + extension, source):
            raise ValueError("Frontend entry asset is not a local content-hashed application-builder file")
        assets.append(source)
    required = [("main", "js"), ("styles", "css")]
    if require_zone_polyfills:
        required.append(("polyfills", "js"))
    for name, extension in required:
        if sum(bool(re.fullmatch(name + r"-[A-Za-z0-9_-]{8}\." + extension, source)) for source in assets) != 1:
            raise ValueError("Expected exactly one content-hashed " + name + " entry")
    if require_zone_polyfills:
        polyfills_index = next(i for i, source in enumerate(assets) if source.startswith("polyfills-"))
        main_index = next(i for i, source in enumerate(assets) if source.startswith("main-"))
        if polyfills_index > main_index:
            raise ValueError("Zone polyfills entry must precede the main bootstrap")
    return assets


def verify_zone_build_config(workspace, bootstrap):
    if not re.search(r"\bprovideZoneChangeDetection\s*\(", bootstrap):
        return False
    for project in workspace.get("projects", {}).values():
        build = project.get("architect", {}).get("build", {})
        if build.get("builder") == "@angular/build:application" and "zone.js" not in build.get("options", {}).get("polyfills", []):
            raise ValueError("Zone-based bootstrap must declare zone.js in application-builder polyfills")
    return True


def main():
    dist = Path(sys.argv[1]).resolve()
    try:
        frontend = Path(__file__).resolve().parent.parent / "frontend"
        zone_based = verify_zone_build_config(json.loads((frontend / "angular.json").read_text(encoding="utf-8")),
                                              (frontend / "src/main.ts").read_text(encoding="utf-8"))
        for asset in hashed_assets((dist / "index.html").read_text(encoding="utf-8"),
                                   require_zone_polyfills=zone_based):
            path = dist / asset
            if not path.is_file() or path.resolve().parent != dist:
                raise ValueError("Referenced frontend entry asset is missing or outside the build root")
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
