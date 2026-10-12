# Nauru: The Countryside

An offline Android 3D driving-and-flight prototype set on an original, human-scale interpretation of Nauru. It uses libGDX/OpenGL ES 3, Bullet, locally packaged OpenStreetMap geometry, and SRTM elevation. Runtime gameplay makes no network requests. No satellite imagery or third-party vehicle models are shipped.

## What changed in 0.2.0

The previous build had a serious world-state bug: while walking, parked vehicles were not rendered, the car and aircraft could start inside one another, and the workboat lost buoyancy whenever it was not the selected vehicle. This rebuild keeps every craft visible and physically supported, verifies spawn-clearance envelopes in both Python and JVM tests, spaces the 4x4 away from the runway aircraft, and only allows disembarking from a stopped craft at ground level (or from the boat near shore). The scene now uses original composite meshes for the 4x4, workboat, helicopter, STOL aircraft, person, tropical palms, island houses, and marked runway rather than one-box placeholder vehicles.

## Gameplay and controls

- Walk around the island; approach one of the visible, parked vehicles and tap **USE** to board.
- On foot: left stick walks, **GO** runs, **AUX** jumps, and dragging the right side looks around.
- In a vehicle: the stick steers or controls cyclic/pitch/bank; **GO** accelerates or raises helicopter collective; **BRK** brakes, reverses, or lowers collective; **AUX** toggles aircraft engines; **USE** exits only when a safe exit is available.
- **REC** returns the current player/vehicle to its spawn. **CAM**, **MAP**, **DBG**, and the quality-profile button change view, map, diagnostics, and performance profile.
- Keyboard and gamepad inputs are also available. See the on-screen mode-specific control hints.

## Offline world build

CI fetches and checksums the Nauru OpenStreetMap and SRTM source snapshots, compiles them to the versioned `NCRW` binary, and packages the data and attribution notices in the APK. The world uses one tested 1:25 transform, with a smooth airport influence field expanding the measured runway to 780 game metres. Spawn clearances and the walk-to-car interaction distance are included in the compiler audit manifest.

For a local data build:

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

## Build and test

Requirements: JDK 17, Android SDK platform/build-tools 36, and Python 3.

```bash
./gradlew --no-daemon --stacktrace :core:test :android:testDebugUnitTest :android:assembleDebug
```

The APK is emitted at `android/build/outputs/apk/debug/android-debug.apk`. CI validates the package name, version, minimum SDK, native ABI libraries, embedded NCRW checksum, APK ZIP/signature, and public download/checksum URLs. It also starts the built APK in an Android API 26 emulator, checks that the offline world loaded without a startup crash, and captures a startup screenshot.

## Provenance and licenses

- `DATA_SOURCES.md` documents OSM/SRTM sources, snapshot checksums, and the gameplay transform.
- `ASSET_LICENSES.md` lists third-party dependency licenses and original procedural content.
- The game HUD displays **© OpenStreetMap contributors**.

## QA limits

Host-side tests cover the transform, packaged-world parser, force-model math, spawn separation, and geodata compiler. The API 26 emulator gate checks installation, startup, and offline-world loading. Neither gate substitutes for hands-on testing on a physical Android phone; measured phone performance, touch feel, battery use, thermal behaviour, and full vehicle handling are not claimed without device measurements.
