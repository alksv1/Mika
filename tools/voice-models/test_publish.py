"""Tests for the voice-pack publish script; network and gh are faked, so nothing is downloaded or uploaded."""

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("publish", HERE / "publish.py")
publish = importlib.util.module_from_spec(spec)
sys.modules["publish"] = publish
spec.loader.exec_module(publish)

REPO = HERE.parent.parent
PACKS = HERE / "packs.json"
CATALOG = REPO / "app" / "src" / "main" / "assets" / "voice-models" / "catalog.json"
APP_LANGUAGES = {"zh-Hans", "zh-Hant", "ar", "fa", "en", "ja", "ko", "es", "pt", "ru", "id", "tr", "vi"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


MODEL = b"model bytes"
TOKENS = b"tokens"
LICENSE = b"license text"


def sample_pack():
    return {
        "id": "demo-int8",
        "version": 1,
        "backend": "sherpa-onnx",
        "family": "whisper",
        "displayName": "Demo",
        "languages": ["en"],
        "recommendedFor": ["en"],
        "hfRepo": "someone/demo",
        "commit": "abc123",
        "files": [
            {"name": "model.onnx", "role": "model", "size": len(MODEL), "sha256": sha(MODEL)},
            {"name": "tokens.txt", "role": "tokens", "size": len(TOKENS), "sha256": sha(TOKENS)},
            {
                "name": "LICENSE",
                "role": "license",
                "size": len(LICENSE),
                "sha256": sha(LICENSE),
                "url": "https://raw.githubusercontent.com/someone/demo/abc123/LICENSE",
            },
        ],
        "license": {"name": "MIT", "file": "LICENSE", "attribution": "Demo by someone"},
        "note": "hand-written note, not shipped",
    }


class FakeWeb:
    """Serve bytes by URL, as urllib.request.urlopen would; remembers what was asked for."""

    def __init__(self, bodies):
        self.bodies = bodies
        self.requested = []

    def __call__(self, url):
        self.requested.append(url)
        if url not in self.bodies:
            raise AssertionError(f"unexpected download {url}")
        import io

        return io.BytesIO(self.bodies[url])


def good_web():
    up = "https://huggingface.co/someone/demo/resolve/abc123/"
    return FakeWeb(
        {
            up + "model.onnx": MODEL,
            up + "tokens.txt": TOKENS,
            "https://raw.githubusercontent.com/someone/demo/abc123/LICENSE": LICENSE,
        }
    )


class AssetNameTest(unittest.TestCase):
    def test_asset_name_joins_pack_and_file(self):
        self.assertEqual(
            publish.asset_name("sensevoice-small-int8", "model.int8.onnx"),
            "sensevoice-small-int8--model.int8.onnx",
        )


class FetchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cache = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_fetch_downloads_from_upstream_or_file_url(self):
        web = good_web()
        publish.fetch([sample_pack()], self.cache, opener=web)
        self.assertEqual(
            web.requested,
            [
                "https://huggingface.co/someone/demo/resolve/abc123/model.onnx",
                "https://huggingface.co/someone/demo/resolve/abc123/tokens.txt",
                "https://raw.githubusercontent.com/someone/demo/abc123/LICENSE",
            ],
        )
        self.assertEqual((self.cache / "demo-int8" / "model.onnx").read_bytes(), MODEL)
        self.assertEqual((self.cache / "demo-int8" / "LICENSE").read_bytes(), LICENSE)

    def test_fetch_rejects_hash_mismatch(self):
        web = good_web()
        web.bodies["https://huggingface.co/someone/demo/resolve/abc123/tokens.txt"] = b"tampered"
        with self.assertRaises(publish.HashMismatch) as caught:
            publish.fetch([sample_pack()], self.cache, opener=web)
        self.assertIn("tokens.txt", str(caught.exception))
        self.assertFalse((self.cache / "demo-int8" / "tokens.txt").exists())
        self.assertEqual(list((self.cache / "demo-int8").glob("*.part")), [])

    def test_fetch_keeps_a_cached_file_that_verifies(self):
        publish.fetch([sample_pack()], self.cache, opener=good_web())
        again = good_web()
        publish.fetch([sample_pack()], self.cache, opener=again)
        self.assertEqual(again.requested, [])

    def test_fetch_replaces_a_corrupt_cached_file(self):
        target = self.cache / "demo-int8" / "model.onnx"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"corrupt")
        web = good_web()
        publish.fetch([sample_pack()], self.cache, opener=web)
        self.assertEqual(target.read_bytes(), MODEL)
        self.assertIn("https://huggingface.co/someone/demo/resolve/abc123/model.onnx", web.requested)


class UpstreamCheckTest(unittest.TestCase):
    def tree(self, oid):
        return json.dumps(
            [
                {"type": "file", "path": "model.onnx", "size": len(MODEL), "lfs": {"oid": oid, "size": len(MODEL)}},
                {"type": "file", "path": "tokens.txt", "size": len(TOKENS)},
            ]
        ).encode()

    def test_pinned_hash_matches_huggingface_lfs(self):
        url = "https://huggingface.co/api/models/someone/demo/tree/abc123"
        publish.check_upstream([sample_pack()], opener=FakeWeb({url: self.tree(sha(MODEL))}))

    def test_pinned_hash_differs_from_huggingface_lfs(self):
        url = "https://huggingface.co/api/models/someone/demo/tree/abc123"
        with self.assertRaises(publish.HashMismatch) as caught:
            publish.check_upstream([sample_pack()], opener=FakeWeb({url: self.tree("0" * 64)}))
        self.assertIn("model.onnx", str(caught.exception))


class CatalogTest(unittest.TestCase):
    def test_catalog_lists_license_as_file(self):
        catalog = publish.render_catalog(publish.load_packs(PACKS))
        self.assertTrue(catalog["packs"])
        for pack in catalog["packs"]:
            licenses = [f for f in pack["files"] if f["role"] == "license"]
            self.assertEqual([f["name"] for f in licenses], [pack["license"]["file"]], pack["id"])

    def test_catalog_shape(self):
        catalog = publish.render_catalog([sample_pack()])
        self.assertEqual(catalog["schema"], 1)
        self.assertEqual(catalog["release"], "https://github.com/wangzan101/Mika/releases/download/voice-models-v1/")
        pack = catalog["packs"][0]
        self.assertEqual(
            list(pack),
            ["id", "version", "backend", "family", "displayName", "languages", "recommendedFor", "files", "license", "upstream"],
        )
        self.assertEqual(pack["upstream"], "https://huggingface.co/someone/demo/resolve/abc123/")
        model, _, license_file = pack["files"]
        self.assertEqual(model, {"name": "model.onnx", "role": "model", "size": len(MODEL), "sha256": sha(MODEL)})
        self.assertEqual(license_file["url"], "https://raw.githubusercontent.com/someone/demo/abc123/LICENSE")

    def test_catalog_rejects_pack_without_license_file(self):
        pack = sample_pack()
        pack["files"] = pack["files"][:2]
        with self.assertRaises(ValueError):
            publish.render_catalog([pack])

    def test_catalog_rejects_redundant_url(self):
        pack = sample_pack()
        pack["files"][0]["url"] = "https://huggingface.co/someone/demo/resolve/abc123/model.onnx"
        with self.assertRaises(ValueError):
            publish.render_catalog([pack])

    def test_build_catalog_hashes_cached_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            publish.fetch([sample_pack()], cache, opener=good_web())
            self.assertEqual(publish.build_catalog([sample_pack()], cache), publish.render_catalog([sample_pack()]))
            (cache / "demo-int8" / "LICENSE").write_bytes(b"swapped")
            with self.assertRaises(publish.HashMismatch):
                publish.build_catalog([sample_pack()], cache)


class CommittedCatalogTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads(CATALOG.read_text(encoding="utf-8"))

    def test_committed_catalog_matches_packs(self):
        expected = publish.dump_json(publish.render_catalog(publish.load_packs(PACKS)))
        self.assertEqual(CATALOG.read_text(encoding="utf-8"), expected)

    def test_every_app_language_has_a_recommended_pack(self):
        covered = {tag for pack in self.catalog["packs"] for tag in pack["recommendedFor"]}
        self.assertEqual(covered, APP_LANGUAGES)

    def test_each_app_language_is_recommended_once(self):
        tags = [tag for pack in self.catalog["packs"] for tag in pack["recommendedFor"]]
        self.assertEqual(len(tags), len(set(tags)))

    def test_four_packs_with_optional_whisper_small(self):
        ids = [pack["id"] for pack in self.catalog["packs"]]
        self.assertEqual(ids, ["sensevoice-small-int8", "dolphin-base-ctc-int8", "whisper-base-int8", "whisper-small-int8"])
        small = self.catalog["packs"][3]
        self.assertEqual(small["recommendedFor"], [])

    def test_asset_names_are_unique(self):
        names = [publish.asset_name(p["id"], f["name"]) for p in self.catalog["packs"] for f in p["files"]]
        self.assertEqual(len(names), len(set(names)))


class FakeGh:
    def __init__(self, view=None):
        self.view = view
        self.calls = []

    def __call__(self, argv, **kwargs):
        self.calls.append(argv)
        if argv[:3] == ["gh", "release", "view"]:
            if self.view is None:
                return subprocess.CompletedProcess(argv, 1, stdout="", stderr="release not found")
            return subprocess.CompletedProcess(argv, 0, stdout=json.dumps(self.view), stderr="")
        return subprocess.CompletedProcess(argv, 0, stdout="", stderr="")


class UploadTest(unittest.TestCase):
    def test_upload_refuses_latest(self):
        argv = publish.upload_args("voice-models-v1")
        self.assertEqual(argv[:4], ["gh", "release", "create", "voice-models-v1"])
        self.assertIn("--prerelease", argv)
        self.assertIn("--latest=false", argv)
        self.assertIn("--repo", argv)

    def test_upload_args_only_for_voice_model_tags(self):
        for tag in ["v1.2.0", "latest", "voice-models-v1; rm", ""]:
            with self.assertRaises(ValueError, msg=tag):
                publish.upload_args(tag)

    def test_asset_upload_args_name_files_by_asset(self):
        argv = publish.asset_upload_args("voice-models-v1", [Path("/s/demo-int8--model.onnx")])
        self.assertEqual(argv[:4], ["gh", "release", "upload", "voice-models-v1"])
        self.assertIn("/s/demo-int8--model.onnx", argv)

    def test_upload_refuses_existing_release_that_is_not_prerelease(self):
        gh = FakeGh(view={"isPrerelease": False, "tagName": "voice-models-v1"})
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            publish.fetch([sample_pack()], cache, opener=good_web())
            with self.assertRaises(publish.RefuseUpload):
                publish.upload([sample_pack()], cache, "voice-models-v1", run=gh)
        self.assertFalse(any(call[:3] == ["gh", "release", "upload"] for call in gh.calls))

    def test_upload_creates_prerelease_then_uploads_verified_assets(self):
        gh = FakeGh(view=None)
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp)
            publish.fetch([sample_pack()], cache, opener=good_web())
            publish.upload([sample_pack()], cache, "voice-models-v1", run=gh)
        create = [c for c in gh.calls if c[:3] == ["gh", "release", "create"]]
        uploads = [c for c in gh.calls if c[:3] == ["gh", "release", "upload"]]
        self.assertEqual(len(create), 1)
        self.assertIn("--prerelease", create[0])
        self.assertEqual(len(uploads), 1)
        names = sorted(Path(a).name for a in uploads[0] if "--" in Path(a).name and not a.startswith("-"))
        self.assertEqual(names, ["demo-int8--LICENSE", "demo-int8--model.onnx", "demo-int8--tokens.txt"])

    def test_upload_refuses_tag_that_differs_from_catalog_release(self):
        with self.assertRaises(publish.RefuseUpload):
            publish.upload([sample_pack()], Path("/nonexistent"), "voice-models-v2", run=FakeGh())


if __name__ == "__main__":
    unittest.main()
