package ai.nauru.countryside.world;

import com.badlogic.gdx.math.Vector3;

/** One authoritative WGS84-local ENU to gameplay transform used by terrain, roads, map and spawns. */
public final class WorldTransform {
    public final float runwayCenterX;
    public final float runwayCenterZ;
    public final float axisX;
    public final float axisZ;
    public final float runwayRealLength;
    public final float airportAlongScale;
    public final float airportInnerRadius;
    public final float airportOuterRadius;
    public final float globalScale;
    private final float rightX;
    private final float rightZ;

    public WorldTransform(float runwayCenterX, float runwayCenterZ, float axisX, float axisZ,
                          float runwayRealLength, float airportAlongScale,
                          float airportInnerRadius, float airportOuterRadius, float globalScale) {
        float norm = (float) Math.sqrt(axisX * axisX + axisZ * axisZ);
        if (!(norm > 0f) || !Float.isFinite(norm)) throw new IllegalArgumentException("Invalid airport axis");
        this.runwayCenterX = runwayCenterX;
        this.runwayCenterZ = runwayCenterZ;
        this.axisX = axisX / norm;
        this.axisZ = axisZ / norm;
        this.runwayRealLength = runwayRealLength;
        this.airportAlongScale = airportAlongScale;
        this.airportInnerRadius = airportInnerRadius;
        this.airportOuterRadius = airportOuterRadius;
        this.globalScale = globalScale;
        this.rightX = -this.axisZ;
        this.rightZ = this.axisX;
        if (!(globalScale > 0f) || !(airportAlongScale > 0f) || airportOuterRadius <= airportInnerRadius)
            throw new IllegalArgumentException("Invalid world transform scales");
    }

    /** Apply 1:25 global compression and the smooth airport runway-axis expansion. */
    public Vector3 map(float realX, float height, float realZ, Vector3 out) {
        final float dx = realX - runwayCenterX;
        final float dz = realZ - runwayCenterZ;
        final float along = dx * axisX + dz * axisZ;
        final float lateral = Math.abs(dx * rightX + dz * rightZ);
        final float t = clamp((lateral - airportInnerRadius) / (airportOuterRadius - airportInnerRadius), 0f, 1f);
        final float smooth = t * t * (3f - 2f * t);
        final float influence = 1f - smooth;
        final float extra = along * (airportAlongScale - globalScale) * influence;
        return out.set(realX * globalScale + axisX * extra, height,
                       realZ * globalScale + axisZ * extra);
    }

    /** Map a human-scale building footprint around its geographically compressed anchor. */
    public Vector3 mapFootprint(float realX, float height, float realZ,
                                float anchorX, float anchorZ, Vector3 out) {
        map(anchorX, height, anchorZ, out);
        out.x += realX - anchorX;
        out.z += realZ - anchorZ;
        return out;
    }

    /** Expand runway-axis length while preserving its real-world width. */
    public Vector3 mapRunwayVertex(float realX, float height, float realZ, Vector3 out) {
        final float dx = realX - runwayCenterX;
        final float dz = realZ - runwayCenterZ;
        final float along = dx * axisX + dz * axisZ;
        final float lateral = dx * rightX + dz * rightZ;
        final float distanceFromAxis = Math.abs(lateral);
        final float t = clamp((distanceFromAxis - airportInnerRadius) / (airportOuterRadius - airportInnerRadius), 0f, 1f);
        final float smooth = t * t * (3f - 2f * t);
        final float scale = globalScale + (airportAlongScale - globalScale) * (1f - smooth);
        map(runwayCenterX, height, runwayCenterZ, out);
        out.x += axisX * along * scale + rightX * lateral;
        out.z += axisZ * along * scale + rightZ * lateral;
        return out;
    }

    public Vector3 mapSpawn(WorldData.Spawn spawn, Vector3 out) {
        map(spawn.realX, spawn.height, spawn.realZ, out);
        out.x += spawn.offsetX;
        out.z += spawn.offsetZ;
        return out;
    }

    public float transformedRunwayLength() {
        return runwayRealLength * airportAlongScale;
    }

    public float rightX() { return rightX; }
    public float rightZ() { return rightZ; }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
