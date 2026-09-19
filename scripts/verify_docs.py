#!/usr/bin/env python3
"""Validate repository-local links and canonical documentation statuses."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


IGNORED_DIRECTORIES = {
    ".git",
    ".gradle",
    ".angular",
    ".idea",
    ".mnema",
    ".release-state",
    ".vscode",
    "build",
    "dist",
    "node_modules",
}
EXTERNAL_SCHEMES = {"data", "http", "https", "mailto", "tel"}
CANONICAL_STATUSES = {
    Path("docs/README.md"): "current",
    Path("docs/system-overview.md"): "current",
    Path("docs/engineering/repository-guide.md"): "current",
}
ALLOWED_STATUSES = {
    "accepted",
    "current",
    "historical",
    "legacy",
    "proposed",
    "superseded",
}
HTML_LINK = re.compile(r"\b(?:href|src)\s*=\s*(['\"])(.*?)\1", re.IGNORECASE)
REFERENCE_DEFINITION = re.compile(r"^\s{0,3}\[([^]]+)]\s*:\s*(.*)$")
HEADING = re.compile(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$")
EXPLICIT_ID = re.compile(r"\bid\s*=\s*(['\"])(.*?)\1", re.IGNORECASE)
STATUS = re.compile(r"^\s*status:\s*['\"]?([^'\"\s]+)", re.MULTILINE)
ESCAPED_PUNCTUATION = re.compile(r"\\([!\"#$%&'()*+,./:;<=>?@\[\\\]^_`{|}~-])")


def markdown_files(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("*.md")
        if not any(part in IGNORED_DIRECTORIES for part in path.relative_to(root).parts)
    )


def visible_markdown(text: str) -> str:
    lines: list[str] = []
    fence_character: str | None = None
    fence_length = 0
    indented_code = False
    previous_blank = True
    for line in text.splitlines():
        stripped = line.lstrip(" ")
        indent = len(line) - len(stripped)
        marker_match = re.match(r"(`{3,}|~{3,})", stripped) if indent <= 3 else None
        if fence_character is None and marker_match:
            marker = marker_match.group(1)
            fence_character = marker[0]
            fence_length = len(marker)
            lines.append("")
            previous_blank = True
            continue
        if fence_character is not None:
            if re.fullmatch(
                rf"\s{{0,3}}{re.escape(fence_character)}{{{fence_length},}}\s*", line
            ):
                fence_character = None
                fence_length = 0
            lines.append("")
            previous_blank = True
            continue

        is_indented = line.startswith("\t") or line.startswith("    ")
        if is_indented and (previous_blank or indented_code):
            indented_code = True
            lines.append("")
            previous_blank = False
            continue
        indented_code = False
        lines.append(line)
        previous_blank = not line.strip()
    return "\n".join(lines)


def without_inline_code(text: str) -> str:
    result: list[str] = []
    cursor = 0
    while cursor < len(text):
        if text[cursor] != "`":
            result.append(text[cursor])
            cursor += 1
            continue
        end_of_marker = cursor
        while end_of_marker < len(text) and text[end_of_marker] == "`":
            end_of_marker += 1
        marker = text[cursor:end_of_marker]
        closing = text.find(marker, end_of_marker)
        if closing < 0:
            result.append(marker)
            cursor = end_of_marker
            continue
        result.append(" " * (closing + len(marker) - cursor))
        cursor = closing + len(marker)
    return "".join(result)


def closing_bracket(text: str, start: int) -> int | None:
    depth = 0
    cursor = start
    while cursor < len(text):
        character = text[cursor]
        if character == "\\":
            cursor += 2
            continue
        if character == "[":
            depth += 1
        elif character == "]":
            if depth == 0:
                return cursor
            depth -= 1
        cursor += 1
    return None


def destination(text: str, start: int) -> tuple[str | None, int]:
    cursor = start
    while cursor < len(text) and text[cursor].isspace():
        cursor += 1
    if cursor >= len(text):
        return None, cursor
    if text[cursor] == "<":
        closing = cursor + 1
        while closing < len(text):
            if text[closing] == ">" and text[closing - 1] != "\\":
                return text[cursor + 1 : closing], closing + 1
            closing += 1
        return None, cursor

    beginning = cursor
    depth = 0
    while cursor < len(text):
        character = text[cursor]
        if character == "\\":
            cursor += 2
            continue
        if character == "(":
            depth += 1
        elif character == ")":
            if depth == 0:
                break
            depth -= 1
        elif character.isspace() and depth == 0:
            break
        cursor += 1
    if cursor == beginning or depth != 0:
        return None, cursor
    return text[beginning:cursor], cursor


def normalize_reference(label: str) -> str:
    return " ".join(label.split()).casefold()


def link_targets(text: str) -> tuple[list[str], list[str]]:
    visible = without_inline_code(visible_markdown(text))
    definitions: dict[str, str] = {}
    targets: list[str] = []
    for line in visible.splitlines():
        match = REFERENCE_DEFINITION.match(line)
        if not match:
            continue
        target, _ = destination(match.group(2), 0)
        if target is not None:
            definitions[normalize_reference(match.group(1))] = target
            targets.append(target)

    missing_references: list[str] = []
    cursor = 0
    while cursor < len(visible):
        label_start = cursor + 1 if visible[cursor] == "!" else cursor
        if label_start >= len(visible) or visible[label_start] != "[":
            cursor += 1
            continue
        label_end = closing_bracket(visible, label_start + 1)
        if label_end is None:
            cursor = label_start + 1
            continue
        label = visible[label_start + 1 : label_end]
        following = label_end + 1
        if following < len(visible) and visible[following] == "(":
            target, end = destination(visible, following + 1)
            if target is not None:
                targets.append(target)
            cursor = max(end, following + 1)
            continue
        if following < len(visible) and visible[following] == "[":
            reference_end = closing_bracket(visible, following + 1)
            if reference_end is not None:
                reference = visible[following + 1 : reference_end] or label
                normalized = normalize_reference(reference)
                if normalized in definitions:
                    targets.append(definitions[normalized])
                else:
                    missing_references.append(reference)
                cursor = reference_end + 1
                continue
        normalized = normalize_reference(label)
        if normalized in definitions:
            targets.append(definitions[normalized])
        cursor = label_end + 1

    targets.extend(match.group(2) for match in HTML_LINK.finditer(visible))
    return [ESCAPED_PUNCTUATION.sub(r"\1", target) for target in targets], missing_references


def github_slug(text: str) -> str:
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"!?\[([^\]]+)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"[`*_~]", "", text).strip().lower()
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)
    return re.sub(r"\s", "-", text)


def anchors(path: Path) -> set[str]:
    visible = visible_markdown(path.read_text(encoding="utf-8"))
    found = {match.group(2) for match in EXPLICIT_ID.finditer(visible)}
    used: set[str] = set(found)
    next_suffix: dict[str, int] = {}
    for line in visible.splitlines():
        match = HEADING.match(line)
        if not match:
            continue
        base = github_slug(match.group(1))
        if not base:
            continue
        candidate = base
        suffix = next_suffix.get(base, 1)
        while candidate in used:
            candidate = f"{base}-{suffix}"
            suffix += 1
        next_suffix[base] = suffix
        used.add(candidate)
        found.add(candidate)
    return found


def canonical_status_errors(root: Path) -> list[str]:
    errors: list[str] = []
    for relative, expected in CANONICAL_STATUSES.items():
        path = root / relative
        if not path.is_file():
            errors.append(f"{relative}: canonical document is missing")
            continue
        text = path.read_text(encoding="utf-8")
        if not text.startswith("---\n"):
            errors.append(f"{relative}: canonical document has no YAML front matter")
            continue
        end = text.find("\n---", 4)
        front_matter = text[4:end] if end >= 0 else ""
        match = STATUS.search(front_matter)
        actual = match.group(1) if match else None
        if actual != expected:
            errors.append(f"{relative}: expected status {expected!r}, found {actual!r}")
    return errors


def declared_status_errors(root: Path) -> list[str]:
    errors: list[str] = []
    for path in markdown_files(root):
        text = path.read_text(encoding="utf-8")
        if not text.startswith("---\n"):
            continue
        end = text.find("\n---", 4)
        front_matter = text[4:end] if end >= 0 else ""
        match = STATUS.search(front_matter)
        if match and match.group(1) not in ALLOWED_STATUSES:
            errors.append(
                f"{path.relative_to(root)}: unsupported documentation status {match.group(1)!r}"
            )
    return errors


def validate(root: Path) -> list[str]:
    root = root.resolve()
    errors = canonical_status_errors(root) + declared_status_errors(root)
    anchor_cache: dict[Path, set[str]] = {}
    for source in markdown_files(root):
        text = source.read_text(encoding="utf-8")
        targets, missing_references = link_targets(text)
        errors.extend(
            f"{source.relative_to(root)}: missing reference definition [{reference}]"
            for reference in missing_references
        )
        for raw_target in targets:
            if not raw_target or raw_target.startswith("#"):
                path_part = ""
                fragment = unquote(raw_target[1:]) if raw_target.startswith("#") else ""
            else:
                parsed = urlsplit(raw_target)
                if parsed.scheme.lower() in EXTERNAL_SCHEMES or raw_target.startswith("//"):
                    continue
                if parsed.scheme or parsed.netloc or "${{" in raw_target:
                    continue
                path_part = unquote(parsed.path)
                fragment = unquote(parsed.fragment)

            target = source if not path_part else (
                root / path_part.lstrip("/") if path_part.startswith("/") else source.parent / path_part
            )
            target = target.resolve()
            try:
                target.relative_to(root)
            except ValueError:
                errors.append(f"{source.relative_to(root)}: link escapes repository: {raw_target}")
                continue
            if not target.exists():
                errors.append(f"{source.relative_to(root)}: missing target {raw_target}")
                continue
            if fragment and target.is_file() and target.suffix.lower() == ".md":
                target_anchors = anchor_cache.setdefault(target, anchors(target))
                if fragment not in target_anchors:
                    errors.append(f"{source.relative_to(root)}: missing anchor {raw_target}")
    return sorted(set(errors))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    errors = validate(args.root)
    if errors:
        for error in errors:
            print(error)
        print(f"documentation validation failed: {len(errors)} error(s)", file=sys.stderr)
        return 1
    print(f"documentation validation passed: {len(markdown_files(args.root.resolve()))} Markdown files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
