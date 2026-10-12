#!/usr/bin/env python3
"""Deterministically compile OSM + SRTM source data to the packaged NCRW world format."""
from __future__ import annotations

import argparse
import array
import datetime as dt
import gzip
import hashlib
import json
import math
import pathlib
import struct
import sys
from dataclasses import dataclass
from typing import Iterable

EARTH_RADIUS_M = 6_378_137.0
GLOBAL_SCALE = 0.04
TARGET_RUNWAY_M = 780.0
AIRPORT_INNER_M = 250.0
AIRPORT_OUTER_M = 1_000.0
GRID_SIZE = 97
MAX_FEATURES = 10_000
MAX_POINTS_PER_FEATURE = 20_000

# Binary feature kinds; mirrored in WorldData.FeatureType.
COAST = 1
ROAD = 2
BUILDING = 3
RUNWAY = 4
TAXIWAY = 5
WATER = 6
LANDUSE = 7
PLACE = 8
PIER = 9
UTILITY = 10
OTHER = 11
TYPE_NAMES = {
    COAST: "coastline", ROAD: "road", BUILDING: "building", RUNWAY: "runway",
    TAXIWAY: "taxiway", WATER: "water", LANDUSE: "landuse", PLACE: "place",
    PIER: "pier", UTILITY: "utility", OTHER: "other",
}


@dataclass
class Feature:
    kind: int
    osm_id: int
    name: str
    points: list[tuple[float, float]]
    tags: dict[str, str]


def smoothstep(edge0: float, edge1: float, value: float) -> float:
    t = min(1.0, max(0.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def world_transform(x: float, z: float, runway_center: tuple[float, float],
                    axis: tuple[float, float], airport_scale: float,
                    inner: float = AIRPORT_INNER_M, outer: float = AIRPORT_OUTER_M,
                    global_scale: float = GLOBAL_SCALE) -> tuple[float, float]:
    """The documented 1:25 transform plus a smooth runway-axis hero zone."""
    dx, dz = x - runway_center[0], z - runway_center[1]
    ax, az = axis
    lateral = abs(-az * dx + ax * dz)
    along = ax * dx + az * dz
    influence = 1.0 - smoothstep(inner, outer, lateral)
    extra = along * (airport_scale - global_scale) * influence
    return global_scale * x + ax * extra, global_scale * z + az * extra


def project(lat: float, lon: float, lat0: float, lon0: float) -> tuple[float, float]:
    lat_r, lat0_r = math.radians(lat), math.radians(lat0)
    x = EARTH_RADIUS_M * math.radians(lon - lon0) * math.cos(lat0_r)
    z = EARTH_RADIUS_M * (lat_r - lat0_r)
    return x, z


def unproject(x: float, z: float, lat0: float, lon0: float) -> tuple[float, float]:
    lat = lat0 + math.degrees(z / EARTH_RADIUS_M)
    lon = lon0 + math.degrees(x / (EARTH_RADIUS_M * math.cos(math.radians(lat0))))
    return lat, lon


def polygon_area(points: list[tuple[float, float]]) -> float:
    return 0.5 * sum(points[i][0] * points[(i + 1) % len(points)][1]
                     - points[(i + 1) % len(points)][0] * points[i][1]
                     for i in range(len(points))) if len(points) >= 3 else 0.0


def point_in_polygon(point: tuple[float, float], polygon: list[tuple[float, float]]) -> bool:
    x, z = point
    inside = False
    if len(polygon) < 3:
        return False
    previous = polygon[-1]
    for current in polygon:
        x1, z1 = previous
        x2, z2 = current
        if ((z1 > z) != (z2 > z)) and x < (x2 - x1) * (z - z1) / ((z2 - z1) or 1e-30) + x1:
            inside = not inside
        previous = current
    return inside


def coordinate_tuple(point: dict) -> tuple[float, float]:
    return float(point["lat"]), float(point["lon"])


def stitch_coastline(segments: list[list[tuple[float, float]]]) -> list[tuple[float, float]]:
    """Join OSM coastline ways into the largest closed ring; coordinates are lat/lon."""
    pending = [s[:] for s in segments if len(s) >= 2]
    closed: list[list[tuple[float, float]]] = []
    eps = 2e-6
    while pending:
        ring = pending.pop(0)
        changed = True
        while changed and len(ring) < 100_000:
            changed = False
            if math.dist(ring[0], ring[-1]) <= eps:
                closed.append(ring)
                break
            for i, seg in enumerate(pending):
                if math.dist(ring[-1], seg[0]) <= eps:
                    ring.extend(seg[1:])
                elif math.dist(ring[-1], seg[-1]) <= eps:
                    ring.extend(reversed(seg[:-1]))
                elif math.dist(ring[0], seg[-1]) <= eps:
                    ring = seg[:-1] + ring
                elif math.dist(ring[0], seg[0]) <= eps:
                    ring = list(reversed(seg[1:])) + ring
                else:
                    continue
                pending.pop(i)
                changed = True
                break
        else:
            pass
        if len(ring) >= 4 and math.dist(ring[0], ring[-1]) <= eps:
            closed.append(ring)
    if not closed:
        endpoints = []
        segment_report = []
        for index, segment in enumerate(segments):
            if len(segment) < 2:
                continue
            start, end = segment[0], segment[-1]
            endpoints.extend(((start[0], start[1], index, "start"),
                              (end[0], end[1], index, "end")))
            segment_report.append({"index": index, "points": len(segment),
                                   "start": [round(start[0], 7), round(start[1], 7)],
                                   "end": [round(end[0], 7), round(end[1], 7)]})
        nearest = []
        for i, a in enumerate(endpoints):
            for b in endpoints[i + 1:]:
                if a[2] == b[2]:
                    continue
                mean_lat = math.radians((a[0] + b[0]) * 0.5)
                north_m = (a[0] - b[0]) * 111_320.0
                east_m = (a[1] - b[1]) * 111_320.0 * math.cos(mean_lat)
                nearest.append((math.hypot(east_m, north_m), a, b))
        nearest.sort(key=lambda item: item[0])
        nearest_report = [{"gap_m": round(gap, 2),
                           "a": [round(a[0], 7), round(a[1], 7), a[2], a[3]],
                           "b": [round(b[0], 7), round(b[1], 7), b[2], b[3]]}
                          for gap, a, b in nearest[:12]]
        raise ValueError("OSM coastline did not form a closed ring; "
                         f"segments={len(segment_report)}, points={sum(s['points'] for s in segment_report)}, "
                         f"segments_report={segment_report[:24]}, nearest_endpoint_gaps={nearest_report}")
    # The island outline is the ring with the largest absolute projected area.
    return max(closed, key=lambda ring: abs(polygon_area(ring)))


def runway_axis(points: list[tuple[float, float]]) -> tuple[tuple[float, float], tuple[float, float], float]:
    if len(points) < 2:
        raise ValueError("Runway geometry has fewer than two points")
    cx = sum(p[0] for p in points) / len(points)
    cz = sum(p[1] for p in points) / len(points)
    xx = sum((p[0] - cx) ** 2 for p in points) / len(points)
    zz = sum((p[1] - cz) ** 2 for p in points) / len(points)
    xz = sum((p[0] - cx) * (p[1] - cz) for p in points) / len(points)
    angle = 0.5 * math.atan2(2.0 * xz, xx - zz)
    axis = (math.cos(angle), math.sin(angle))
    if axis[0] < 0 or (abs(axis[0]) < 1e-7 and axis[1] < 0):
        axis = (-axis[0], -axis[1])
    along = [(p[0] - cx) * axis[0] + (p[1] - cz) * axis[1] for p in points]
    length = max(along) - min(along)
    return (cx, cz), axis, length


def element_kind(tags: dict[str, str]) -> int | None:
    if tags.get("natural") == "coastline": return COAST
    if tags.get("aeroway") == "runway": return RUNWAY
    if tags.get("aeroway") == "taxiway": return TAXIWAY
    if "building" in tags and tags.get("building") != "no": return BUILDING
    if "highway" in tags: return ROAD
    if tags.get("natural") in ("water", "wetland", "bay", "reef"): return WATER
    if "landuse" in tags or tags.get("natural") in ("wood", "scrub", "grassland", "bare_rock"): return LANDUSE
    if tags.get("man_made") in ("pier", "breakwater", "groyne") or tags.get("man_made") == "pier": return PIER
    if tags.get("place") or ("name" in tags and tags.get("amenity")): return PLACE
    if "man_made" in tags or "power" in tags or "amenity" in tags: return UTILITY
    return None


def decode_elements(osm: dict, lat0: float, lon0: float) -> tuple[list[Feature], list[tuple[float, float]]]:
    features: list[Feature] = []
    coast_segments: list[list[tuple[float, float]]] = []
    for element in osm.get("elements", []):
        tags = {str(k): str(v) for k, v in element.get("tags", {}).items()}
        kind = element_kind(tags)
        if kind is None:
            continue
        if element.get("type") == "node":
            lat, lon = float(element["lat"]), float(element["lon"])
            coords = [(lat, lon)]
        else:
            geometry = element.get("geometry") or []
            coords = [coordinate_tuple(p) for p in geometry]
        if len(coords) < (3 if kind in (COAST, BUILDING, RUNWAY, WATER, LANDUSE) else 1):
            continue
        projected = [project(lat, lon, lat0, lon0) for lat, lon in coords]
        if kind == COAST:
            coast_segments.append(coords)
        name = tags.get("name:en") or tags.get("name") or tags.get("official_name") or ""
        features.append(Feature(kind, int(element.get("id", 0)), name[:160], projected, tags))
    return features, coast_segments


def read_srtm(path: pathlib.Path) -> tuple[array.array, int]:
    with gzip.open(path, "rb") as stream:
        raw = stream.read()
    if len(raw) % 2:
        raise ValueError("SRTM tile byte count is not a signed-16-bit grid")
    samples = array.array("h")
    samples.frombytes(raw)
    if sys.byteorder == "little":
        samples.byteswap()
    side = math.isqrt(len(samples))
    if side * side != len(samples) or side < 1201:
        raise ValueError(f"Unexpected SRTM tile dimensions: {side}")
    return samples, side


def sample_srtm(samples: array.array, side: int, lat: float, lon: float) -> float:
    # HGT files are north-to-south rows, west-to-east columns; S01E166 spans [-1,0] x [166,167].
    if not (-1.0001 <= lat <= 0.0001 and 165.9999 <= lon <= 167.0001):
        return 0.0
    col = int(round((lon - 166.0) * (side - 1)))
    row = int(round((0.0 - lat) * (side - 1)))
    col, row = min(side - 1, max(0, col)), min(side - 1, max(0, row))
    val = samples[row * side + col]
    if val != -32768:
        return float(val)
    for radius in (1, 2, 4, 8, 16):
        values = []
        for yy in range(max(0, row - radius), min(side, row + radius + 1)):
            for xx in range(max(0, col - radius), min(side, col + radius + 1)):
                sample = samples[yy * side + xx]
                if sample != -32768:
                    values.append(sample)
        if values:
            values.sort()
            return float(values[len(values) // 2])
    return 0.0


def simplify_points(points: list[tuple[float, float]], max_count: int = 1200) -> list[tuple[float, float]]:
    if len(points) <= max_count:
        return points
    step = (len(points) - 1) / (max_count - 1)
    return [points[round(i * step)] for i in range(max_count)]


def building_priority(feature: Feature) -> tuple[int, int]:
    tags = feature.tags
    key = 0 if tags.get("historic") or tags.get("amenity") or tags.get("building") in ("hospital", "school", "public") else 1
    stable_hash = int(hashlib.sha256(str(feature.osm_id).encode()).hexdigest()[:8], 16)
    return key, stable_hash


def footprint_overlap(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
    ax, az, aw, ad = a
    bx, bz, bw, bd = b
    overlap_x = max(0.0, min(ax + aw / 2, bx + bw / 2) - max(ax - aw / 2, bx - bw / 2))
    overlap_z = max(0.0, min(az + ad / 2, bz + bd / 2) - max(az - ad / 2, bz - bd / 2))
    intersection = overlap_x * overlap_z
    smaller = min(max(aw * ad, 1e-3), max(bw * bd, 1e-3))
    return intersection / smaller


def select_buildings(features: list[Feature], center: tuple[float, float], axis: tuple[float, float],
                     scale: float, inner: float, outer: float, max_count: int = 420) -> list[Feature]:
    selected: list[Feature] = []
    occupied: list[tuple[float, float, float, float]] = []
    for feature in sorted((f for f in features if f.kind == BUILDING), key=building_priority):
        xs, zs = [p[0] for p in feature.points], [p[1] for p in feature.points]
        cx, cz = (min(xs) + max(xs)) * 0.5, (min(zs) + max(zs)) * 0.5
        wx, wz = max(2.5, max(xs) - min(xs)), max(2.5, max(zs) - min(zs))
        tx, tz = world_transform(cx, cz, center, axis, scale, inner, outer)
        # Footprint detail stays at human scale, while only its geographic anchor is compressed.
        angle = math.atan2(axis[1], axis[0])
        half_x = max(1.2, wx * 0.5)
        half_z = max(1.2, wz * 0.5)
        candidate = (tx, tz, min(24.0, half_x * 2.0), min(24.0, half_z * 2.0))
        if any(footprint_overlap(candidate, other) > 0.20 for other in occupied):
            continue
        occupied.append(candidate)
        selected.append(feature)
        if len(selected) >= max_count:
            break
    return selected


def encode_string(stream: bytearray, value: str) -> None:
    payload = value.encode("utf-8")[:1024]
    stream.extend(struct.pack(">H", len(payload)))
    stream.extend(payload)


def encode_world(out_path: pathlib.Path, lat0: float, lon0: float,
                 runway_center: tuple[float, float], axis: tuple[float, float], runway_len: float,
                 airport_scale: float, bounds: tuple[float, float, float, float],
                 grid_size: int, heights: list[float], land_mask: list[bool], outline: list[tuple[float, float]],
                 features: list[Feature], spawns: list[dict], audit: dict) -> str:
    min_x, min_z, step_x, step_z = bounds
    stream = bytearray()
    stream.extend(b"NCRW")
    stream.extend(struct.pack(">I", 1))
    stream.extend(struct.pack(">dd", lat0, lon0))
    transformed_len = runway_len * airport_scale
    stream.extend(struct.pack(">10f", runway_center[0], runway_center[1], axis[0], axis[1],
                               runway_len, airport_scale, AIRPORT_INNER_M, AIRPORT_OUTER_M,
                               transformed_len, GLOBAL_SCALE))
    stream.extend(struct.pack(">I", grid_size))
    stream.extend(struct.pack(">4f", min_x, min_z, step_x, step_z))
    count = grid_size * grid_size
    if len(heights) != count or len(land_mask) != count:
        raise ValueError("Terrain grid array length mismatch")
    stream.extend(struct.pack(">I", count))
    for height, is_land in zip(heights, land_mask):
        stream.extend(struct.pack(">fB", height, 1 if is_land else 0))
    if not (3 <= len(outline) <= 100_000):
        raise ValueError(f"Invalid coastline point count: {len(outline)}")
    stream.extend(struct.pack(">I", len(outline)))
    for x, z in outline:
        stream.extend(struct.pack(">2f", x, z))
    if len(features) > MAX_FEATURES:
        raise ValueError(f"Too many features: {len(features)}")
    stream.extend(struct.pack(">I", len(features)))
    for feature in features:
        points = simplify_points(feature.points, MAX_POINTS_PER_FEATURE)
        stream.extend(struct.pack(">Bq", feature.kind, feature.osm_id))
        encode_string(stream, feature.name)
        stream.extend(struct.pack(">I", len(points)))
        for x, z in points:
            stream.extend(struct.pack(">2f", x, z))
    stream.extend(struct.pack(">I", len(spawns)))
    for spawn in spawns:
        stream.extend(struct.pack(">B", int(spawn["type"])))
        encode_string(stream, spawn["name"])
        stream.extend(struct.pack(">6f", spawn["x"], spawn["z"], spawn["offset_x"], spawn["offset_z"],
                                 spawn["height"], spawn["yaw"]))
    digest = hashlib.sha256(stream).digest()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(stream + digest)
    audit["binary_sha256"] = hashlib.sha256(stream + digest).hexdigest()
    audit["binary_bytes"] = len(stream) + len(digest)
    return hashlib.sha256(stream + digest).hexdigest()


def build(osm_path: pathlib.Path, hgt_path: pathlib.Path, out_path: pathlib.Path,
          manifest_path: pathlib.Path) -> dict:
    osm = json.loads(osm_path.read_text(encoding="utf-8"))
    elements = osm.get("elements", [])
    coastline_elements = [e for e in elements if e.get("type") == "way" and e.get("tags", {}).get("natural") == "coastline"]
    if not coastline_elements:
        raise ValueError("No OSM coastline ways in source")
    raw_coast = [coordinate_tuple(p) for e in coastline_elements for p in (e.get("geometry") or [])]
    if not raw_coast:
        raise ValueError("OSM coastline ways have no geometry")
    # Averages over the perimeter give a stable local ENU origin.
    lat0 = sum(p[0] for p in raw_coast) / len(raw_coast)
    lon0 = sum(p[1] for p in raw_coast) / len(raw_coast)
    features, coast_segments = decode_elements(osm, lat0, lon0)
    raw_outline = stitch_coastline(coast_segments)
    outline = [project(lat, lon, lat0, lon0) for lat, lon in raw_outline]
    if math.dist(outline[0], outline[-1]) < 0.05:
        outline = outline[:-1]

    runway_features = [f for f in features if f.kind == RUNWAY]
    if not runway_features:
        raise ValueError("No OSM aeroway=runway geometry; airport transform cannot be validated")
    runway_feature = max(runway_features, key=lambda f: abs(polygon_area(f.points)))
    runway_center, axis, runway_len = runway_axis(runway_feature.points)
    if not (250.0 <= runway_len <= 4_000.0):
        raise ValueError(f"Implausible real runway length from OSM: {runway_len:.1f} m")
    airport_scale = TARGET_RUNWAY_M / runway_len
    transformed_points = [world_transform(x, z, runway_center, axis, airport_scale)
                          for x, z in runway_feature.points]
    projected_along = [p[0] * axis[0] + p[1] * axis[1] for p in transformed_points]
    transformed_len = max(projected_along) - min(projected_along)
    if not (600.0 <= transformed_len <= 900.0):
        raise ValueError(f"Airport hero zone runway outside target: {transformed_len:.2f} m")

    selected_buildings = select_buildings(features, runway_center, axis, airport_scale,
                                          AIRPORT_INNER_M, AIRPORT_OUTER_M)
    kept = []
    for feature in features:
        if feature.kind == BUILDING:
            continue
        if feature.kind == PLACE and not feature.name:
            continue
        if feature.kind == COAST:
            # A single compiled coastline keeps the world outline deterministic and compact.
            continue
        if feature.kind in (ROAD, RUNWAY, TAXIWAY, WATER, LANDUSE, PLACE, PIER, UTILITY, OTHER):
            kept.append(feature)
    kept.extend(selected_buildings)
    kept.sort(key=lambda f: (f.kind, f.osm_id, f.name))
    if not any(f.kind == ROAD for f in kept):
        raise ValueError("OSM extract contains no roads")
    if not any(f.kind == PLACE for f in kept):
        raise ValueError("OSM extract contains no named places")

    xs, zs = [p[0] for p in outline], [p[1] for p in outline]
    pad = 90.0
    min_x, max_x = min(xs) - pad, max(xs) + pad
    min_z, max_z = min(zs) - pad, max(zs) + pad
    step_x = (max_x - min_x) / (GRID_SIZE - 1)
    step_z = (max_z - min_z) / (GRID_SIZE - 1)
    srtm, srtm_side = read_srtm(hgt_path)
    heights, mask = [], []
    for row in range(GRID_SIZE):
        z = min_z + row * step_z
        for col in range(GRID_SIZE):
            x = min_x + col * step_x
            is_land = point_in_polygon((x, z), outline)
            lat, lon = unproject(x, z, lat0, lon0)
            heights.append(sample_srtm(srtm, srtm_side, lat, lon) if is_land else 0.0)
            mask.append(is_land)
    if sum(mask) < GRID_SIZE * GRID_SIZE // 12:
        raise ValueError("Coastline produced an implausibly small land mask")

    # Spawn anchors are OSM-registered locations; offsets are explicit local-scale vehicle clearances.
    ax, az = axis
    right = (-az, ax)
    airport_start = (runway_center[0] - ax * runway_len * 0.44 + right[0] * 90.0,
                     runway_center[1] - az * runway_len * 0.44 + right[1] * 90.0)
    car_anchor = airport_start
    walk_anchor = airport_start
    heli_anchor = (runway_center[0] - ax * runway_len * 0.40 + right[0] * 170.0,
                   runway_center[1] - az * runway_len * 0.40 + right[1] * 170.0)
    plane_anchor = (runway_center[0] - ax * runway_len * 0.44,
                    runway_center[1] - az * runway_len * 0.44)

    # Select the coastline vertex nearest a mapped pier/harbour if one is present.
    pier_centers = []
    for feature in features:
        if feature.kind == PIER and feature.points:
            pier_centers.append((sum(p[0] for p in feature.points) / len(feature.points),
                                 sum(p[1] for p in feature.points) / len(feature.points)))
    if pier_centers:
        coast_anchor = min(outline, key=lambda p: min(math.dist(p, q) for q in pier_centers))
    else:
        # Nauru's east-side coast is the closest legal simple boat launch reference if OSM has no pier.
        coast_anchor = max(outline, key=lambda p: p[0])
    coast_cx = sum(p[0] for p in outline) / len(outline)
    coast_cz = sum(p[1] for p in outline) / len(outline)
    out_x, out_z = coast_anchor[0] - coast_cx, coast_anchor[1] - coast_cz
    out_len = math.hypot(out_x, out_z) or 1.0
    out_x, out_z = out_x / out_len, out_z / out_len
    boat_anchor = (coast_anchor[0] + out_x * 1250.0, coast_anchor[1] + out_z * 1250.0)

    def point_height(x: float, z: float) -> float:
        lat, lon = unproject(x, z, lat0, lon0)
        return sample_srtm(srtm, srtm_side, lat, lon)

    def heading_for(vector: tuple[float, float]) -> float:
        # LibGDX model forward is (0,0,-1); yaw is radians.
        return math.atan2(-vector[0], -vector[1])

    spawns = [
        {"type": 1, "name": "Walker start", "x": walk_anchor[0], "z": walk_anchor[1],
         "offset_x": right[0] * 5.0, "offset_z": right[1] * 5.0, "height": point_height(*walk_anchor), "yaw": heading_for(axis)},
        {"type": 2, "name": "Island 4x4", "x": car_anchor[0], "z": car_anchor[1],
         "offset_x": 0.0, "offset_z": 0.0, "height": point_height(*car_anchor), "yaw": heading_for(axis)},
        {"type": 3, "name": "Lagoon workboat", "x": boat_anchor[0], "z": boat_anchor[1],
         "offset_x": 0.0, "offset_z": 0.0, "height": 0.7, "yaw": heading_for((out_x, out_z))},
        {"type": 4, "name": "Utility helicopter", "x": heli_anchor[0], "z": heli_anchor[1],
         "offset_x": right[0] * 14.0, "offset_z": right[1] * 14.0, "height": point_height(*heli_anchor) + 0.6, "yaw": heading_for(axis)},
        {"type": 5, "name": "STOL light aircraft", "x": plane_anchor[0], "z": plane_anchor[1],
         "offset_x": 0.0, "offset_z": 0.0, "height": point_height(*plane_anchor) + 0.8, "yaw": heading_for(axis)},
    ]

    # Reuse the compile-time signature and input hashes for repeatable QA.
    def sha(path: pathlib.Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    counts = {TYPE_NAMES[k]: sum(1 for f in kept if f.kind == k) for k in TYPE_NAMES}
    audit = {
        "schema": 1,
        "built_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source_hashes": {"osm_sha256": sha(osm_path), "srtm_gzip_sha256": sha(hgt_path)},
        "projection": "local ENU tangent approximation, WGS84 around OSM coastline centroid",
        "global_scale": GLOBAL_SCALE,
        "airport_hero_zone": {"runway_osm_id": runway_feature.osm_id,
                               "runway_real_m": runway_len,
                               "runway_transformed_m": transformed_len,
                               "target_m": TARGET_RUNWAY_M,
                               "axis_east_north": list(axis),
                               "influence_inner_outer_m": [AIRPORT_INNER_M, AIRPORT_OUTER_M]},
        "terrain": {"grid_width": GRID_SIZE, "grid_height": GRID_SIZE,
                    "source": "SRTM 1 arc-second, one degree HGT tile S01E166", "land_samples": sum(mask)},
        "coastline_points": len(outline),
        "osm_element_count": len(elements),
        "selected_feature_counts": counts,
        "spawn_locations": [{"type": s["type"], "name": s["name"]} for s in spawns],
        "offline_runtime_data": True,
    }
    binary_sha = encode_world(out_path, lat0, lon0, runway_center, axis, runway_len,
                              airport_scale, (min_x, min_z, step_x, step_z), GRID_SIZE,
                              heights, mask, outline, kept, spawns, audit)
    audit["binary_sha256"] = binary_sha
    audit["binary_bytes"] = out_path.stat().st_size
    audit["world_origin_wgs84"] = {"lat": lat0, "lon": lon0}
    audit["bounds_real_m"] = [min_x, min_z, max_x, max_z]
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    return audit


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--osm", type=pathlib.Path, required=True)
    parser.add_argument("--hgt", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    args = parser.parse_args()
    audit = build(args.osm, args.hgt, args.out, args.manifest)
    print(json.dumps(audit, indent=2))


if __name__ == "__main__":
    main()
