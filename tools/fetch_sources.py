#!/usr/bin/env python3
"""Fetch public source data used by the offline Nauru world build."""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
DOWNLOADS = ROOT / "data" / "geodata" / "downloads"
DOWNLOADS.mkdir(parents=True, exist_ok=True)

# A generous bbox around the island and airport. Overpass order is south,west,north,east.
BBOX = (-0.575, 166.87, -0.47, 166.99)
OVERPASS_QUERY = f"""[out:json][timeout:180];
(
  way[\"natural\"=\"coastline\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"highway\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"building\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"aeroway\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"landuse\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"natural\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"waterway\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"man_made\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"amenity\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"leisure\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  way[\"place\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  node[\"place\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  node[\"name\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
  relation[\"boundary\"=\"administrative\"]({BBOX[0]},{BBOX[1]},{BBOX[2]},{BBOX[3]});
);
out geom;"""

SRTM_URL = "https://s3.amazonaws.com/elevation-tiles-prod/skadi/S01/S01E166.hgt.gz"
SOURCES = [
    {
        "name": "OpenStreetMap Nauru extract (Overpass API)",
        "url": "https://overpass-api.de/api/interpreter",
        "license": "Open Database License (ODbL) 1.0; © OpenStreetMap contributors",
        "query_bbox_south_west_north_east": list(BBOX),
    },
    {
        "name": "SRTM 1 arc-second tile S01E166 (AWS elevation-tiles-prod mirror)",
        "url": SRTM_URL,
        "upstream": "NASA/USGS Shuttle Radar Topography Mission (SRTM) v3",
        "license": "US Government public-domain elevation data; mirror usage terms apply",
    },
]


def fetch(url: str, destination: pathlib.Path, data: bytes | None = None) -> None:
    request = urllib.request.Request(
        url,
        data=data,
        headers={"User-Agent": "Nauru-Countryside-DataCompiler/0.1 (offline game build)",
                 "Content-Type": "application/x-www-form-urlencoded" if data else "application/octet-stream"},
    )
    last_error: Exception | None = None
    for attempt in range(1, 5):
        try:
            with urllib.request.urlopen(request, timeout=240) as response:
                payload = response.read()
                if response.status != 200 or not payload:
                    raise RuntimeError(f"HTTP {response.status} or empty response from {url}")
                destination.write_bytes(payload)
                print(f"Fetched {url} -> {destination} ({len(payload)} bytes)")
                return
        except (urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            last_error = exc
            print(f"Fetch attempt {attempt}/4 failed: {exc}", file=sys.stderr)
            time.sleep(attempt * 2)
    raise RuntimeError(f"Could not fetch {url}: {last_error}")


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    osm_path = DOWNLOADS / "nauru_overpass.json"
    srtm_path = DOWNLOADS / "S01E166.hgt.gz"
    query_bytes = urllib.parse.urlencode({"data": OVERPASS_QUERY}).encode()
    endpoints = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    ]
    errors: list[str] = []
    for endpoint in endpoints:
        try:
            fetch(endpoint, osm_path, query_bytes)
            SOURCES[0]["url"] = endpoint
            break
        except Exception as exc:  # try the next public Overpass instance
            errors.append(f"{endpoint}: {exc}")
    else:
        raise RuntimeError("All Overpass endpoints failed:\n" + "\n".join(errors))

    payload = json.loads(osm_path.read_text(encoding="utf-8"))
    elements = payload.get("elements", [])
    if len(elements) < 20:
        raise RuntimeError(f"Unexpectedly small OSM response: {len(elements)} elements")

    fetch(SRTM_URL, srtm_path)
    if srtm_path.stat().st_size < 100_000:
        raise RuntimeError("SRTM tile looks too small to be valid")

    manifest = {
        "schema": 1,
        "retrieved_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "sources": [
            {**SOURCES[0], "file": osm_path.name, "bytes": osm_path.stat().st_size,
             "sha256": sha256(osm_path), "osm_element_count": len(elements)},
            {**SOURCES[1], "file": srtm_path.name, "bytes": srtm_path.stat().st_size,
             "sha256": sha256(srtm_path)},
        ],
    }
    (DOWNLOADS / "source-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
