# Nauru: The Countryside

An offline-first Android 3D game prototype and open-geodata pipeline for an original, human-scale interpretation of Nauru. The project uses libGDX/OpenGL ES 3 and Bullet on Android, custom original geometry, OpenStreetMap data, and SRTM elevation. No commercial map imagery or vehicle assets are used.

## Current implementation status

The repository now contains an end-to-end game implementation: NCRW world loading and one shared map transform; a procedural 3D terrain/feature renderer; a walkable character; an integrated Bullet collision world with car suspension/tyre forces, distributed boat buoyancy, helicopter rotor forces, and STOL aircraft lift/landing gear; third-person camera, touch/keyboard/gamepad controls, and battery/thermal quality policy. The real OSM/SRTM data pipeline has passed CI and the Java core has compiled; CI is continuing through core/JVM tests and Android APK packaging. A post-stall curve assertion was corrected after the latest test run, so a successful Android APK is not yet claimed. No physical Android-device QA or measured performance is claimed; continue to report every failed or unrun gate plainly.

## Build the offline world data

The map and elevation snapshots are fetched by CI and compiled into a small versioned `NCRW` binary. The installed game does not access the network.

```bash
python3 tools/fetch_sources.py
python3 tools/build_geodata.py \
  --osm data/geodata/downloads/nauru_overpass.json \
  --hgt data/geodata/downloads/S01E166.hgt.gz \
  --out android/assets/compiled/world.bin \
  --manifest android/assets/compiled/manifest.json
mkdir -p android/assets/compiled android/assets/licenses
cp data/geodata/downloads/source-manifest.json android/assets/compiled/source-manifest.json
cp ASSET_LICENSES.md android/assets/licenses/ASSET_LICENSES.txt
cp DATA_SOURCES.md android/assets/licenses/DATA_SOURCES.txt
python3 -m unittest discover -s tools -p 'test*.py' -v
```

## Run tests and assemble an Android APK

Requirements: JDK 17, Android SDK platform/build-tools 36, Python 3, and internet access for Gradle/Maven dependencies and the two open-data source files.

```bash
./gradlew --no-daemon --stacktrace :core:test :android:testDebugUnitTest :android:assembleDebug
```

The APK is emitted to `android/build/outputs/apk/debug/android-debug.apk`. CI additionally checks APK ZIP integrity, manifest/package/min-SDK metadata, native ABI contents, and debug signature, then computes a SHA-256 sidecar.

## World scale

Most geographic locations are transformed at 1:25 into local gameplay space. A narrow smooth influence corridor expands the OSM runway along its measured center axis to 780 game metres for a light STOL aircraft. Runway width, building footprints, road widths, and vehicle dimensions remain at human scale; ordinary structures are deterministically culled when transformed footprints overlap. The same runtime `WorldTransform` is used for terrain, map features, the minimap, and source-anchored spawns. A large procedural ocean/airspace around the compact island supplies room for boat and flight testing.

## Data and licensing

- `DATA_SOURCES.md` — upstream sources, retrieval/checksum process, attribution, and transform notes.
- `ASSET_LICENSES.md` — original and third-party content/dependency licenses.
- OSM attribution (© OpenStreetMap contributors) is also visible in the game HUD/minimap.

## CI and downloads

`.github/workflows/android-apk.yml` fetches the current open-data snapshot, runs Python and JVM tests, builds the debug APK, validates its embedded offline world and native libraries, produces a SHA-256 sidecar, uploads a CI artifact, and publishes a prerelease on the Arena working branch. The generated OSM/SRTM provenance and world-build audit manifests accompany the APK.

## Honest QA note

CI compilation and host-side packaged-world/force-model tests do not substitute for a physical Android phone. The project does not claim measured thermal behavior, battery life, controls, or rendering performance on physical hardware unless those device measurements are explicitly added to the release notes.
