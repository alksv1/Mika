# Voice packs

Offline speech-recognition model files for the app, pinned to fixed HuggingFace commits.

- `packs.json` is the hand-edited source: packs, pinned repo and commit, every file with its role,
  size and SHA-256. A file not at `upstream + name` (the license texts, taken from each model's
  original GitHub repo at a pinned commit) carries its own `url`. `note` stays here and is not shipped.
- `app/src/main/assets/voice-models/catalog.json` is generated from it and committed. The app
  trusts only this bundled copy; it never reads a catalog from the network.

## Fetch and verify

    python3 tools/voice-models/publish.py fetch --cache ~/.cache/mika-voice-models

Checks the pins against HuggingFace's LFS records for the commit, then downloads anything missing
into `<cache>/<packId>/<fileName>` (via `.part`, renamed only when size and SHA-256 match). Files
already in the cache are re-hashed and kept if they match. About 900 MB.

## Regenerate the catalog

    python3 tools/voice-models/publish.py catalog --cache ~/.cache/mika-voice-models

Hashes the cached files again and writes `catalog.json`. After editing `packs.json`, run `fetch`
then `catalog`, and commit both. Tests (`python3 -m unittest discover -s tools/voice-models`)
fail if the committed catalog is out of date.

## Upload (maintainer, once after merge)

    python3 tools/voice-models/publish.py upload --tag voice-models-v1

Creates the `voice-models-v1` GitHub release as a **pre-release, never latest**
(`gh release create --prerelease --latest=false`) and uploads every file as `<packId>--<fileName>`.
It refuses a tag other than the one in the catalog and refuses if that release exists and is not a
pre-release. Needs `gh` signed in with write access. Add `--clobber` to replace existing assets.
