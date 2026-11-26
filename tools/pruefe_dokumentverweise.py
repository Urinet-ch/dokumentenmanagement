#!/usr/bin/env python3
"""Prüft lokale Markdown-Links und Dateiverweise in docs/ und deploy/."""

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

REPO = Path(__file__).resolve().parents[1]
LINK = re.compile(r"!?\[[^\]]*\]\(\s*(?P<ziel><[^>]+>|(?:\\.|[^)\s])+)")
LINK_DEFINITION = re.compile(r"^\s*\[[^\]]+\]:\s*(?:<([^>]+)>|(\S+))")
INLINE_CODE = re.compile(r"(?<!`)`([^`\n]+)`(?!`)")
REPO_PFAD = re.compile(
    r"(?<![A-Za-z0-9_./-])"
    r"(?P<ziel>(?:(?:\.\.?/)+)?(?:docs|deploy|tools|backend|frontend|wordpress|\.kiro|\.github)"
    r"(?:/[A-Za-z0-9_.@+-]+)*/?)"
)
ZEILENANGABE = re.compile(r":\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*$")
GENERIERTE_PFADE = {"target", "node_modules", "coverage"}


def _zielpfad(root, quelle, ziel, repo_pfad=False, repo_root_fallback=False):
    ziel = ziel.strip().strip("`'\"<>").rstrip(".,;:!?)]}")
    if not ziel or any(mark in ziel for mark in ("$", "<", ">", "{", "}", "…", "...", "*")):
        return None
    teile = urlsplit(ziel)
    if teile.scheme or teile.netloc or not teile.path:
        return None
    pfad = unquote(teile.path)
    if pfad.startswith("/"):
        kandidat = root / pfad.lstrip("/")
    elif repo_pfad:
        kandidat = root / pfad
    else:
        kandidat = quelle.parent / pfad
        if repo_root_fallback and pfad.startswith("./"):
            root_kandidat = root / pfad[2:]
            if not kandidat.exists() and root_kandidat.exists():
                kandidat = root_kandidat
    if set(Path(pfad).parts) & GENERIERTE_PFADE:
        return None
    return kandidat.resolve(strict=False)


def _konkreter_pfad(root, quelle, ziel, repo_pfad=False, repo_root_fallback=False):
    ziel = ZEILENANGABE.sub("", ziel)
    extension = re.search(r"\.[A-Za-z][A-Za-z0-9]{0,7}$", Path(ziel).name)
    if ziel.endswith("/") or extension or Path(ziel).name == "Dockerfile":
        return True
    pfad = _zielpfad(root, quelle, ziel, repo_pfad, repo_root_fallback)
    return pfad is not None and pfad.exists()


def _fehler(root, quelle, zeile, ziel, repo_pfad=False, repo_root_fallback=False):
    ziel = ZEILENANGABE.sub("", ziel)
    pfad = _zielpfad(root, quelle, ziel, repo_pfad, repo_root_fallback)
    if pfad is None:
        return None
    try:
        pfad.relative_to(root.resolve())
    except ValueError:
        return {
            "datei": quelle.relative_to(root).as_posix(),
            "zeile": zeile,
            "ziel": ziel,
            "grund": "Ziel liegt ausserhalb des Repositorys.",
        }
    if not pfad.exists():
        return {
            "datei": quelle.relative_to(root).as_posix(),
            "zeile": zeile,
            "ziel": ziel,
            "grund": "Datei oder Verzeichnis fehlt.",
        }
    return None


def pruefe_verweise(root=REPO):
    root = Path(root).resolve()
    befunde = []
    for verzeichnis in ("docs", "deploy"):
        basis = root / verzeichnis
        if not basis.is_dir():
            return [{
                "datei": verzeichnis,
                "zeile": 1,
                "ziel": verzeichnis,
                "grund": "Verzeichnis fehlt.",
            }]
        for quelle in sorted(basis.rglob("*.md")):
            text = quelle.read_text(encoding="utf-8")
            for nummer, zeile in enumerate(text.splitlines(), 1):
                for treffer in LINK.finditer(zeile):
                    ziel = treffer.group("ziel")
                    if ziel.startswith("<") and ziel.endswith(">"):
                        ziel = ziel[1:-1]
                    befunde.append(_fehler(root, quelle, nummer, ziel))
                treffer = LINK_DEFINITION.match(zeile)
                if treffer:
                    befunde.append(_fehler(root, quelle, nummer, treffer.group(1) or treffer.group(2)))
                for treffer in REPO_PFAD.finditer(zeile):
                    ziel = treffer.group("ziel")
                    if zeile[treffer.end():].startswith(("<", "*", "…")):
                        continue
                    repo_pfad = not ziel.startswith(("./", "../"))
                    if not _konkreter_pfad(root, quelle, ziel, repo_pfad=repo_pfad):
                        continue
                    befunde.append(_fehler(root, quelle, nummer, ziel,
                                           repo_pfad=repo_pfad))
                for span in INLINE_CODE.finditer(zeile):
                    for ziel in re.findall(r"[^\s]+", span.group(1)):
                        ziel = ziel.strip("`'\"<>").rstrip(".,;:!?)]}")
                        if any(mark in ziel for mark in ("$", "<", ">", "…", "...", "*", "://")):
                            continue
                        if ziel.startswith("/") or not ziel.startswith((
                                "./", "../", "docs/", "deploy/", "tools/", "backend/", "frontend/",
                                "wordpress/", ".kiro/", ".github/")):
                            continue
                        repo_pfad = ziel.startswith((
                            "docs/", "deploy/", "tools/", "backend/", "frontend/",
                            "wordpress/", ".kiro/", ".github/"))
                        repo_root_fallback = ziel.startswith("./")
                        if not _konkreter_pfad(root, quelle, ziel, repo_pfad, repo_root_fallback):
                            continue
                        if ziel:
                            befunde.append(_fehler(root, quelle, nummer, ziel,
                                                   repo_pfad=repo_pfad,
                                                   repo_root_fallback=repo_root_fallback))
    eindeutig = {
        (b["datei"], b["zeile"], b["ziel"], b["grund"]): b
        for b in befunde if b is not None
    }
    return [eindeutig[schluessel] for schluessel in sorted(eindeutig)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="Befunde als JSON ausgeben")
    args = parser.parse_args()
    try:
        befunde = pruefe_verweise()
    except OSError as fehler:
        print(f"Dokumentverweise konnten nicht geprüft werden: {fehler}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps({"befunde": befunde}, ensure_ascii=False, indent=2))
    elif befunde:
        for befund in befunde:
            print(f"{befund['datei']}:{befund['zeile']}: {befund['ziel']} — {befund['grund']}")
    else:
        print("Keine ungültigen Markdown-Links oder Dateiverweise in docs/ und deploy/.")
    return 1 if befunde else 0


if __name__ == "__main__":
    raise SystemExit(main())
