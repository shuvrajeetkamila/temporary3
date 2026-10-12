import gzip
import hashlib
import json
import pathlib
import tempfile
import unittest

from build_geodata import (
    AIRPORT_INNER_M,
    AIRPORT_OUTER_M,
    GLOBAL_SCALE,
    TARGET_RUNWAY_M,
    point_in_polygon,
    read_srtm,
    sample_srtm,
    stitch_coastline,
    stitch_coastline_with_report,
    world_transform,
    build,
    unproject,
)


class WorldTransformTests(unittest.TestCase):
    def setUp(self):
        self.center = (0.0, 0.0)
        self.axis = (1.0, 0.0)
        self.airport_scale = TARGET_RUNWAY_M / 2_000.0

    def test_global_locations_use_one_to_twenty_five_scale(self):
        x, z = world_transform(2_000.0, 1_500.0, self.center, self.axis, self.airport_scale,
                               inner=100.0, outer=200.0)
        self.assertAlmostEqual(x, 80.0)
        self.assertAlmostEqual(z, 60.0)

    def test_airport_axis_expands_runway_to_target(self):
        a = world_transform(-1_000.0, 0.0, self.center, self.axis, self.airport_scale)
        b = world_transform(1_000.0, 0.0, self.center, self.axis, self.airport_scale)
        self.assertAlmostEqual(b[0] - a[0], TARGET_RUNWAY_M, delta=0.01)
        self.assertAlmostEqual(b[1] - a[1], 0.0, delta=0.01)

    def test_airport_lateral_falloff_is_continuous(self):
        before = world_transform(300.0, AIRPORT_OUTER_M - 0.01, self.center, self.axis,
                                 self.airport_scale)
        at_edge = world_transform(300.0, AIRPORT_OUTER_M, self.center, self.axis,
                                  self.airport_scale)
        outside = world_transform(300.0, AIRPORT_OUTER_M + 0.01, self.center, self.axis,
                                  self.airport_scale)
        self.assertLess(abs(before[0] - at_edge[0]), 0.02)
        self.assertLess(abs(at_edge[0] - outside[0]), 0.02)
        inner = world_transform(300.0, AIRPORT_INNER_M, self.center, self.axis,
                                self.airport_scale)
        self.assertAlmostEqual(inner[0], 300.0 * self.airport_scale, places=4)

    def test_point_in_polygon(self):
        ring = [(-1.0, -1.0), (1.0, -1.0), (1.0, 1.0), (-1.0, 1.0)]
        self.assertTrue(point_in_polygon((0.0, 0.0), ring))
        self.assertFalse(point_in_polygon((2.0, 0.0), ring))

    def test_coastline_segments_stitch_in_reverse_order(self):
        segments = [
            [(0.0, 0.0), (0.0, 1.0)],
            [(1.0, 1.0), (0.0, 1.0)],
            [(1.0, 1.0), (1.0, 0.0)],
            [(0.0, 0.0), (1.0, 0.0)],
        ]
        ring = stitch_coastline(segments)
        self.assertGreaterEqual(len(ring), 4)
        self.assertLessEqual(abs(ring[0][0] - ring[-1][0]) + abs(ring[0][1] - ring[-1][1]), 2e-6)

    def test_coastline_stitch_bridges_and_reports_small_osm_endpoint_gap(self):
        # OSM sometimes has distinct endpoints a few metres apart on a mostly continuous coast.
        segments = [
            [(0.0, 0.0), (0.0, 0.01)],
            [(0.01, 0.01), (0.0, 0.01)],
            [(0.01, 0.01), (0.01, 0.0)],
            [(0.0101, 0.0), (0.0, 0.0)],
        ]
        ring, gaps = stitch_coastline_with_report(segments)
        self.assertAlmostEqual(ring[0][0], ring[-1][0], places=7)
        self.assertAlmostEqual(ring[0][1], ring[-1][1], places=7)
        self.assertEqual(len(gaps), 1)
        self.assertGreater(gaps[0], 10.0)
        self.assertLess(gaps[0], 12.0)

    def test_srtm_hgt_sample_uses_north_to_south_rows(self):
        # A 1201x1201 synthetic tile exercises HGT endianness and the N-to-S row convention.
        side = 1201
        values = bytearray()
        for row in range(side):
            values.extend((row % 256).to_bytes(2, 'big', signed=False))
        # Repeat the row value across each longitude sample.
        packed = bytearray()
        for row in range(side):
            packed.extend((row % 256).to_bytes(2, 'big') * side)
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp) / 'sample.hgt.gz'
            with gzip.open(path, 'wb') as stream:
                stream.write(packed)
            grid, actual_side = read_srtm(path)
        self.assertEqual(actual_side, side)
        north = sample_srtm(grid, side, -0.001, 166.5)
        south = sample_srtm(grid, side, -0.999, 166.5)
        self.assertLess(north, south)


class GeodataCompilationTests(unittest.TestCase):
    def test_synthetic_fixture_compiles_to_checksummed_ncrw(self):
        # This is a compiler fixture only. Production CI fetches real OSM/SRTM; no synthetic
        # geography is used as the shipped world.
        center_lat, center_lon = -0.525, 166.93

        def geo(x, z):
            return dict(zip(("lat", "lon"), unproject(x, z, center_lat, center_lon)))

        def way(osm_id, points, tags):
            return {"type": "way", "id": osm_id, "tags": tags,
                    "geometry": [geo(x, z) for x, z in points]}

        coastline = [(-2_400, -1_850), (2_400, -1_850), (2_400, 1_850),
                     (-2_400, 1_850), (-2_400, -1_850)]
        runway = [(-770, -17), (770, -17), (770, 17), (-770, 17), (-770, -17)]
        building = [(-90, -40), (-70, -40), (-70, -20), (-90, -20), (-90, -40)]
        water = [(-350, 450), (-120, 450), (-120, 620), (-350, 620), (-350, 450)]
        elements = [
            way(100, coastline, {"natural": "coastline"}),
            way(101, runway, {"aeroway": "runway", "name": "Fixture runway"}),
            way(102, [(-500, 0), (-300, 0), (-100, 30)], {"highway": "primary", "name": "Fixture Road"}),
            way(103, building, {"building": "yes"}),
            way(104, water, {"natural": "water", "name": "Fixture Lagoon"}),
            {"type": "node", "id": 105, "lat": center_lat, "lon": center_lon,
             "tags": {"place": "town", "name": "Fixture Settlement"}},
        ]
        with tempfile.TemporaryDirectory() as temp:
            temp = pathlib.Path(temp)
            osm_path = temp / "osm.json"
            hgt_path = temp / "S01E166.hgt.gz"
            out_path = temp / "world.bin"
            manifest_path = temp / "manifest.json"
            osm_path.write_text(json.dumps({"elements": elements}), encoding="utf-8")
            with gzip.open(hgt_path, "wb") as stream:
                stream.write(bytes(1201 * 1201 * 2))
            audit = build(osm_path, hgt_path, out_path, manifest_path)
            payload = out_path.read_bytes()
            self.assertEqual(payload[:4], b"NCRW")
            self.assertEqual(hashlib.sha256(payload[:-32]).digest(), payload[-32:])
            self.assertEqual(audit["terrain"]["grid_width"], 97)
            self.assertAlmostEqual(audit["airport_hero_zone"]["runway_transformed_m"], 780.0, delta=1.0)
            clearances = {tuple(row["types"]): row["separation_m"]
                          for row in audit["spawn_clearances"]}
            self.assertAlmostEqual(clearances[(1, 2)], 5.0, delta=0.02,
                                   msg="walker must begin within USE range without intersecting the 4x4")
            self.assertGreater(clearances[(2, 5)], 13.0,
                               "parked 4x4 must not spawn in the aircraft wing envelope")
            self.assertGreater(clearances[(2, 4)], 12.0,
                               "the car and helicopter must start with non-overlapping clearance")
            self.assertTrue(manifest_path.is_file())
            self.assertIn("source_hashes", json.loads(manifest_path.read_text(encoding="utf-8")))


if __name__ == '__main__':
    unittest.main()
