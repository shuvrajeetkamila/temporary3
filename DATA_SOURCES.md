# Data provenance and attribution

## OpenStreetMap

- **Source:** OpenStreetMap via Overpass API; default endpoint `https://overpass-api.de/api/interpreter` (the fetch tool has a public fallback endpoint).
- **Query area:** Nauru, bbox `south=-0.575, west=166.87, north=-0.47, east=166.99` WGS84. The query requests coastline, roads, buildings, aeroways, land use, natural/water features, named places, piers, utilities, and administrative boundaries with geometry.
- **License/credit:** © OpenStreetMap contributors, ODbL 1.0. Attribution is displayed in-game. OSM data is a factual geodatabase; rendered geometry is regenerated for this project.
- **Retrieval/checksum:** `tools/fetch_sources.py` records the UTC retrieval time, response size, endpoint, OSM element count, and SHA-256 in `data/geodata/downloads/source-manifest.json`; CI copies that exact source manifest into the APK as `assets/compiled/source-manifest.json`. The compiler writes the world-build audit to `assets/compiled/manifest.json`.

## Elevation

- **Source:** NASA/USGS Shuttle Radar Topography Mission (SRTM) v3, one-arc-second HGT tile `S01E166.hgt.gz`, fetched from `https://s3.amazonaws.com/elevation-tiles-prod/skadi/S01/S01E166.hgt.gz`.
- **Coverage:** S01E166 (1°S–0°, 166°E–167°E), covering Nauru.
- **License/credit:** SRTM elevation is US Government public-domain data; the mirror is used only as a transport source. The fetch manifest records the exact mirror URL, retrieval time, byte count, and SHA-256 of the compressed input.
- **Runtime processing:** The Python compiler samples SRTM to a bounded 97×97 terrain grid and packages it in the checksummed `NCRW` binary. No network is needed by the installed game.

## Game-world transform

All source coordinates are projected from WGS84 into a local East-North-Up tangent approximation centered on the coastline. Runtime `WorldTransform` applies the 1:25 island compression and the documented smooth airport hero-zone expansion. The original elevation samples remain in metres vertically. The transformed runway target is 780 m, and the compiler rejects a result outside 600–900 m. The source and transformed runway measurements appear in the generated audit manifest.

Spawn positions are derived from the measured runway/coast and packaged as source-anchored locations. The audit records pairwise gameplay-space separations using conservative hull radii; compilation fails if the walker cannot reach the 4x4 or if two parked vehicle envelopes overlap. The 4x4 is offset from the runway aircraft to avoid the overlapping startup layout in the rejected 0.1.0 build.

OSM coastline ways are stitched by shared endpoints. Where the source has a small endpoint mismatch, the compiler permits a straight join only up to 50 m and records the join count and lengths in `assets/compiled/manifest.json`; larger gaps fail the build rather than inventing an island outline. The land outline still follows the fetched OSM coastline geometry (this is not a generic oval or raster substitute).

The APK contains only the compact compiled world, not raw SRTM or unfiltered OSM files. Build outputs include a per-input checksum manifest so the exact upstream snapshot used for a release is auditable.
