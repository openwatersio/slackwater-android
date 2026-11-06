"""Check the built APK against the reviewed resource manifest."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def verify(apk):
    root = Path(__file__).resolve().parent.parent
    lock = json.loads((root / "upstream.lock.json").read_text())
    expected = {"assets/slackwater/" + entry["name"]: entry["sha256"] for entry in lock["resources"]["assets"]}
    with zipfile.ZipFile(apk) as archive:
        # AGP generates these two ART profiles; neither is a product-data input.
        profiles = {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}
        actual = [item.filename for item in archive.infolist() if item.filename.startswith("assets/") and item.filename not in profiles]
        if sorted(actual) != sorted(expected):
            raise ValueError("APK asset manifest differs from the reviewed allowlist")
        for name, digest in expected.items():
            if hashlib.sha256(archive.read(name)).hexdigest() != digest:
                raise ValueError(f"APK asset hash mismatch: {name}")
        if archive.getinfo("assets/slackwater/stations.tcdb").compress_type != zipfile.ZIP_STORED:
            raise ValueError("stations.tcdb must be uncompressed")
        for name in archive.namelist():
            if name.endswith(".dex") and b"Lio/openwaters/slackwater/android/core/FixtureSmoke;" in archive.read(name):
                raise ValueError("Test fixture code entered the app APK")
    print(f"Verified {len(expected)} APK assets, uncompressed TCDB, and fixture exclusion.")


if __name__ == "__main__":
    verify(sys.argv[1])
