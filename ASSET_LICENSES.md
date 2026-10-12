# Asset and dependency licenses

## Original game content

The renderer, models, shaders (if any), UI, procedural building/vegetation geometry, and game source in this repository are original for **Nauru: The Countryside**. Vehicles are unbranded fictional designs. No commercial-game, Google Maps, satellite, Street View, or proprietary visual assets are used.

## Open geospatial data

- OpenStreetMap ways, nodes, and relations are © OpenStreetMap contributors and available under the Open Database License (ODbL) 1.0. Attribution is shown in the game's minimap/HUD and credits. See `DATA_SOURCES.md`.
- Elevation is NASA/USGS SRTM v3 data (public-domain US Government elevation data) distributed through the AWS `elevation-tiles-prod` mirror. See `DATA_SOURCES.md` and the build-generated source manifest for per-build checksums.

## Runtime/library dependencies

- libGDX and gdx-bullet: Apache License 2.0.
- Android Gradle Plugin and Android platform libraries: Apache License 2.0.
- JUnit 4: Eclipse Public License 1.0.

The HUD uses libGDX's bundled 15-point Liberation Sans bitmap font (`lsans-15`), redistributed inside the Apache-2.0-licensed libGDX JAR; Liberation Sans is licensed under the SIL Open Font License 1.1. The runtime package does not fetch assets, fonts, sound, or services from the network. No third-party model, texture, or audio asset is redistributed.
