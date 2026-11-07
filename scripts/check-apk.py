"""Check the built APK against the reviewed resource manifest."""
import hashlib
import json
import runpy
from pathlib import Path
import sys
import zipfile


def verify_current_ranges(catalog, index):
    records = json.loads(catalog)
    lines = index.decode("utf-8").splitlines()
    if len(lines) != len(records):
        raise ValueError("Current index row count differs from catalog")
    for record, line in zip(records, lines):
        station_id, offset, size, subordinate, signal = line.split("\t")
        start, length = int(offset), int(size)
        if start < 0 or length <= 0 or start + length > len(catalog):
            raise ValueError(f"Invalid current byte range: {station_id}")
        expected_signal = abs(record["meanFlow"]) + sum(c["amplitude"] for c in record["constituents"])
        if int(subordinate) != int("reference" in record) or float(signal) != expected_signal:
            raise ValueError(f"Current index rank differs from catalog: {station_id}")
        selected = json.loads(catalog[start:start + length])
        if record["id"] != station_id or selected != record:
            raise ValueError(f"Current index points to the wrong record: {station_id}")


def verify(apk):
    root = Path(__file__).resolve().parent.parent
    lock = json.loads((root / "upstream.lock.json").read_text())
    expected = {"assets/slackwater/" + entry["name"]: entry["sha256"] for entry in lock["resources"]["assets"]}
    index = runpy.run_path(str(root / "scripts/index-currents.py"))["index_currents"]
    catalog = (root / "app/src/main/assets/slackwater/currents.json").read_bytes()
    expected["assets/slackwater/current-index.tsv"] = hashlib.sha256(index(catalog)).hexdigest()
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
        if archive.getinfo("assets/slackwater/currents.json").compress_type != zipfile.ZIP_STORED:
            raise ValueError("currents.json must be uncompressed for indexed reads")
        verify_current_ranges(archive.read("assets/slackwater/currents.json"),
                              archive.read("assets/slackwater/current-index.tsv"))
        for name in archive.namelist():
            if name.endswith(".dex") and b"Lio/openwaters/slackwater/android/core/FixtureSmoke;" in archive.read(name):
                raise ValueError("Test fixture code entered the app APK")
    print(f"Verified {len(expected)} APK assets, uncompressed TCDB, and fixture exclusion.")


if __name__ == "__main__":
    verify(sys.argv[1])
