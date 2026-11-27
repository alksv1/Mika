#!/usr/bin/env python3
"""Fetch, describe and publish Mika's offline voice-recognition packs.

packs.json (next to this script) is the hand-edited source of truth: which model files, pinned to
which HuggingFace commit, with their expected size and SHA-256. From it this script:

  fetch    downloads every file into a cache dir and checks size and SHA-256 (and, unless told not
           to, that the pinned hashes agree with HuggingFace's own LFS records for that commit);
  catalog  writes app/src/main/assets/voice-models/catalog.json, the manifest shipped in the APK;
  upload   puts the verified files on the voice-models-v1 GitHub pre-release (maintainer only).

Standard library only. Release assets are flat, so each is named <packId>--<fileName>.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent
PACKS_FILE = HERE / "packs.json"
CATALOG_FILE = REPO_ROOT / "app" / "src" / "main" / "assets" / "voice-models" / "catalog.json"
DEFAULT_CACHE = Path.home() / ".cache" / "mika-voice-models"

SCHEMA = 1
GITHUB_REPO = "wangzan101/Mika"
RELEASE_TAG = "voice-models-v1"
RELEASE_URL = f"https://github.com/{GITHUB_REPO}/releases/download/{RELEASE_TAG}/"
TAG_PATTERN = re.compile(r"voice-models-v[0-9]+")
USER_AGENT = "mika-voice-models-publish"
CHUNK = 1 << 20

PACK_FIELDS = ["id", "version", "backend", "family", "displayName", "languages", "recommendedFor"]


class HashMismatch(Exception):
    """A file's size or SHA-256 is not what packs.json pins."""

    def __init__(self, name, expected, actual):
        super().__init__(f"{name}: expected {expected}, got {actual}")
        self.name = name


class RefuseUpload(Exception):
    """Uploading would publish somewhere other than the voice-models pre-release."""


def asset_name(pack_id, file_name):
    return f"{pack_id}--{file_name}"


def upstream_of(pack):
    return f"https://huggingface.co/{pack['hfRepo']}/resolve/{pack['commit']}/"


def file_url(pack, entry):
    return entry.get("url") or upstream_of(pack) + entry["name"]


def load_packs(path=PACKS_FILE):
    return json.loads(Path(path).read_text(encoding="utf-8"))["packs"]


def default_opener(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    return urllib.request.urlopen(request, timeout=60)


def digest(path):
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as stream:
        while chunk := stream.read(CHUNK):
            h.update(chunk)
            size += len(chunk)
    return size, h.hexdigest()


def verify_file(path, entry, label):
    """Raise HashMismatch unless path has exactly the pinned size and SHA-256."""
    size, sha256 = digest(path)
    if size != entry["size"]:
        raise HashMismatch(label, f"{entry['size']} bytes", f"{size} bytes")
    if sha256 != entry["sha256"]:
        raise HashMismatch(label, entry["sha256"], sha256)


def cached_path(cache, pack, entry):
    return Path(cache) / pack["id"] / entry["name"]


def fetch(packs, cache, opener=default_opener, log=lambda line: None):
    """Download each pinned file into cache/<packId>/<name>, keeping already verified copies."""
    for pack in packs:
        for entry in pack["files"]:
            target = cached_path(cache, pack, entry)
            label = f"{pack['id']}/{entry['name']}"
            if target.exists():
                try:
                    verify_file(target, entry, label)
                    log(f"ok      {label}")
                    continue
                except HashMismatch:
                    target.unlink()
            target.parent.mkdir(parents=True, exist_ok=True)
            part = target.with_name(target.name + ".part")
            url = file_url(pack, entry)
            log(f"fetch   {label} <- {url}")
            try:
                with opener(url) as response, open(part, "wb") as out:
                    while chunk := response.read(CHUNK):
                        out.write(chunk)
                verify_file(part, entry, label)
            except BaseException:
                part.unlink(missing_ok=True)
                raise
            os.replace(part, target)


def check_upstream(packs, opener=default_opener, log=lambda line: None):
    """Check pinned sizes/hashes against HuggingFace's tree listing for the pinned commit.

    LFS files carry their SHA-256 there, so both are compared; small non-LFS files only by size.
    Files with an explicit url (license texts from the original GitHub repo) are not on HF.
    """
    for pack in packs:
        url = f"https://huggingface.co/api/models/{pack['hfRepo']}/tree/{pack['commit']}"
        with opener(url) as response:
            tree = {item["path"]: item for item in json.loads(response.read()) if item.get("type") == "file"}
        for entry in pack["files"]:
            if "url" in entry:
                continue
            label = f"{pack['id']}/{entry['name']}"
            item = tree.get(entry["name"])
            if item is None:
                raise ValueError(f"{label}: not in {pack['hfRepo']} at {pack['commit']}")
            lfs = item.get("lfs")
            size = lfs["size"] if lfs else item.get("size")
            if size != entry["size"]:
                raise HashMismatch(label, f"{entry['size']} bytes", f"{size} bytes on HuggingFace")
            if lfs:
                if lfs["oid"] != entry["sha256"]:
                    raise HashMismatch(label, entry["sha256"], f"{lfs['oid']} on HuggingFace")
                log(f"lfs ok  {label}")
            else:
                log(f"size ok {label} (not LFS, hash checked after download)")


def render_catalog(packs):
    """The shipped manifest: pack metadata plus pinned sizes and hashes, nothing hand-only."""
    ids = set()
    out = []
    for pack in packs:
        if pack["id"] in ids:
            raise ValueError(f"duplicate pack id {pack['id']}")
        ids.add(pack["id"])
        upstream = upstream_of(pack)
        files = []
        for entry in pack["files"]:
            item = {"name": entry["name"], "role": entry["role"], "size": entry["size"], "sha256": entry["sha256"]}
            if not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
                raise ValueError(f"{pack['id']}/{entry['name']}: sha256 is not 64 lowercase hex digits")
            if "url" in entry:
                if entry["url"] == upstream + entry["name"]:
                    raise ValueError(f"{pack['id']}/{entry['name']}: url is just upstream + name; omit it")
                if not entry["url"].startswith("https://"):
                    raise ValueError(f"{pack['id']}/{entry['name']}: url must be https")
                item["url"] = entry["url"]
            files.append(item)
        names = [f["name"] for f in files]
        if len(names) != len(set(names)):
            raise ValueError(f"{pack['id']}: duplicate file names")
        licenses = [f["name"] for f in files if f["role"] == "license"]
        if licenses != [pack["license"]["file"]]:
            raise ValueError(f"{pack['id']}: needs exactly one license file named {pack['license']['file']}")
        item = {field: pack[field] for field in PACK_FIELDS}
        item["files"] = files
        item["license"] = {key: pack["license"][key] for key in ("name", "file", "attribution")}
        item["upstream"] = upstream
        out.append(item)
    return {"schema": SCHEMA, "release": RELEASE_URL, "packs": out}


def verify_cache(packs, cache):
    for pack in packs:
        for entry in pack["files"]:
            path = cached_path(cache, pack, entry)
            label = f"{pack['id']}/{entry['name']}"
            if not path.exists():
                raise FileNotFoundError(f"{label}: not in cache {cache}; run fetch first")
            verify_file(path, entry, label)


def build_catalog(packs, cache):
    """render_catalog, but only after every cached file matches what it pins."""
    verify_cache(packs, cache)
    return render_catalog(packs)


def dump_json(value):
    """Indented JSON with lists of plain values kept on one line, ending in a newline."""

    def emit(node, depth):
        pad = "  " * depth
        inner = "  " * (depth + 1)
        if isinstance(node, dict):
            if not node:
                return "{}"
            body = ",\n".join(f"{inner}{json.dumps(k, ensure_ascii=False)}: {emit(v, depth + 1)}" for k, v in node.items())
            return "{\n" + body + "\n" + pad + "}"
        if isinstance(node, list):
            if all(not isinstance(v, (dict, list)) for v in node):
                return json.dumps(node, ensure_ascii=False, separators=(", ", ": "))
            body = ",\n".join(inner + emit(v, depth + 1) for v in node)
            return "[\n" + body + "\n" + pad + "]"
        return json.dumps(node, ensure_ascii=False)

    return emit(value, 0) + "\n"


def upload_args(tag):
    """gh command that creates the release: always a pre-release, never marked latest."""
    if not isinstance(tag, str) or not TAG_PATTERN.fullmatch(tag):
        raise ValueError(f"not a voice-models tag: {tag!r}")
    argv = [
        "gh", "release", "create", tag,
        "--repo", GITHUB_REPO,
        "--prerelease",
        "--latest=false",
        "--title", f"Voice models ({tag})",
        "--notes", "Offline speech-recognition model files for Mika. Not an app release; "
        "the app downloads these by the hashes in its bundled catalog.json.",
    ]
    return argv


def asset_upload_args(tag, paths, clobber=False):
    if not TAG_PATTERN.fullmatch(tag):
        raise ValueError(f"not a voice-models tag: {tag!r}")
    argv = ["gh", "release", "upload", tag, "--repo", GITHUB_REPO]
    if clobber:
        argv.append("--clobber")
    return argv + [str(p) for p in paths]


def release_state(tag, run):
    """None if the release does not exist yet, else gh's JSON view of it."""
    view = run(
        ["gh", "release", "view", tag, "--repo", GITHUB_REPO, "--json", "isPrerelease,tagName"],
        capture_output=True,
        text=True,
    )
    if view.returncode != 0:
        if "not found" in (view.stderr or "").lower():
            return None
        raise RuntimeError(f"gh release view failed: {view.stderr}")
    return json.loads(view.stdout)


def upload(packs, cache, tag, run=subprocess.run, clobber=False, log=lambda line: None):
    """Upload verified cached files as <packId>--<name> to the tag's pre-release."""
    expected_tag = RELEASE_URL.rstrip("/").rsplit("/", 1)[-1]
    if tag != expected_tag:
        raise RefuseUpload(f"catalog.json points at {expected_tag}, not {tag}")
    create = upload_args(tag)
    verify_cache(packs, cache)
    state = release_state(tag, run)
    if state is None:
        log(f"create  {tag} (pre-release)")
        done = run(create)
        if done.returncode != 0:
            raise RuntimeError("gh release create failed")
    elif not state.get("isPrerelease"):
        raise RefuseUpload(f"release {tag} exists and is not a pre-release; fix it on GitHub first")
    with tempfile.TemporaryDirectory(prefix="voice-models-") as staging:
        staged = []
        for pack in packs:
            for entry in pack["files"]:
                link = Path(staging) / asset_name(pack["id"], entry["name"])
                os.symlink(cached_path(cache, pack, entry).resolve(), link)
                staged.append(link)
        log(f"upload  {len(staged)} files to {tag}")
        done = run(asset_upload_args(tag, staged, clobber))
        if done.returncode != 0:
            raise RuntimeError("gh release upload failed")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--packs", type=Path, default=PACKS_FILE, help="pack list (default: packs.json here)")
    sub = parser.add_subparsers(dest="command", required=True)

    p_fetch = sub.add_parser("fetch", help="download and verify every pack file")
    p_fetch.add_argument("--cache", type=Path, default=DEFAULT_CACHE, help=f"cache dir (default: {DEFAULT_CACHE})")
    p_fetch.add_argument("--no-upstream-check", action="store_true", help="skip comparing pins with HuggingFace's LFS records")

    p_catalog = sub.add_parser("catalog", help="write the bundled catalog.json")
    p_catalog.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    p_catalog.add_argument("--no-verify", action="store_true", help="render from packs.json without hashing the cache")
    p_catalog.add_argument("--out", type=Path, default=CATALOG_FILE)

    p_upload = sub.add_parser("upload", help="maintainer only: upload to the voice-models pre-release")
    p_upload.add_argument("--tag", required=True)
    p_upload.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    p_upload.add_argument("--clobber", action="store_true", help="replace assets that already exist")

    args = parser.parse_args(argv)
    packs = load_packs(args.packs)
    log = lambda line: print(line, flush=True)
    try:
        if args.command == "fetch":
            if not args.no_upstream_check:
                check_upstream(packs, log=log)
            fetch(packs, args.cache, log=log)
            log(f"all files verified in {args.cache}")
        elif args.command == "catalog":
            catalog = render_catalog(packs) if args.no_verify else build_catalog(packs, args.cache)
            args.out.parent.mkdir(parents=True, exist_ok=True)
            args.out.write_text(dump_json(catalog), encoding="utf-8")
            log(f"wrote {args.out}")
        elif args.command == "upload":
            upload(packs, args.cache, args.tag, clobber=args.clobber, log=log)
    except (HashMismatch, RefuseUpload, ValueError, FileNotFoundError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
