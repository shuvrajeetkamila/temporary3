package ai.nauru.countryside.world;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector3;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounds-checked, checksummed offline OSM/SRTM world package. */
public final class WorldData {
    public static final int FORMAT_VERSION = 1;
    public static final int COAST = 1, ROAD = 2, BUILDING = 3, RUNWAY = 4, TAXIWAY = 5,
            WATER = 6, LANDUSE = 7, PLACE = 8, PIER = 9, UTILITY = 10, OTHER = 11;
    private static final int MAX_GRID_SIDE = 256;
    private static final int MAX_OUTLINE = 100_000;
    private static final int MAX_FEATURES = 10_000;
    private static final int MAX_POINTS = 20_000;
    private static final int MAX_SPAWNS = 16;

    public final double originLatitude;
    public final double originLongitude;
    public final WorldTransform transform;
    public final float transformedRunwayLength;
    public final int gridWidth;
    public final float gridMinX, gridMinZ, gridStepX, gridStepZ;
    public final float[] elevations;
    public final byte[] landMask;
    public final float[] coastlineXZ;
    public final List<Feature> features;
    public final List<Spawn> spawns;
    public final String sha256;

    private WorldData(double originLatitude, double originLongitude, WorldTransform transform,
                      float transformedRunwayLength, int gridWidth, float gridMinX, float gridMinZ,
                      float gridStepX, float gridStepZ, float[] elevations, byte[] landMask,
                      float[] coastlineXZ, List<Feature> features, List<Spawn> spawns, String sha256) {
        this.originLatitude = originLatitude;
        this.originLongitude = originLongitude;
        this.transform = transform;
        this.transformedRunwayLength = transformedRunwayLength;
        this.gridWidth = gridWidth;
        this.gridMinX = gridMinX;
        this.gridMinZ = gridMinZ;
        this.gridStepX = gridStepX;
        this.gridStepZ = gridStepZ;
        this.elevations = elevations;
        this.landMask = landMask;
        this.coastlineXZ = coastlineXZ;
        this.features = Collections.unmodifiableList(features);
        this.spawns = Collections.unmodifiableList(spawns);
        this.sha256 = sha256;
    }

    public static WorldData load(FileHandle file) throws IOException {
        return read(file.readBytes());
    }

    public static WorldData read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 128) throw new IOException("World package is missing or truncated");
        final int bodyLength = bytes.length - 32;
        final byte[] actualDigest = sha256(bytes, 0, bodyLength);
        for (int i = 0; i < 32; i++) {
            if (actualDigest[i] != bytes[bodyLength + i]) throw new IOException("NCRW checksum mismatch");
        }
        final String digestHex = hex(actualDigest);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'N' || magic[1] != 'C' || magic[2] != 'R' || magic[3] != 'W')
                throw new IOException("NCRW magic bytes do not match");
            int version = in.readInt();
            if (version != FORMAT_VERSION) throw new IOException("Unsupported world format version: " + version);
            double lat0 = in.readDouble();
            double lon0 = in.readDouble();
            float centerX = finite(in.readFloat(), "runway center x");
            float centerZ = finite(in.readFloat(), "runway center z");
            float axisX = finite(in.readFloat(), "runway axis x");
            float axisZ = finite(in.readFloat(), "runway axis z");
            float runwayReal = finite(in.readFloat(), "runway real length");
            float airportScale = finite(in.readFloat(), "airport along scale");
            float inner = finite(in.readFloat(), "airport inner radius");
            float outer = finite(in.readFloat(), "airport outer radius");
            float transformedRunway = finite(in.readFloat(), "transformed runway length");
            float globalScale = finite(in.readFloat(), "global scale");
            if (runwayReal < 250f || runwayReal > 4_000f) throw new IOException("Runway real length is invalid");
            if (transformedRunway < 600f || transformedRunway > 900f) throw new IOException("Hero runway length is outside 600–900 m");
            if (Math.abs(globalScale - 0.04f) > 1e-5f) throw new IOException("Expected a 1:25 world scale");
            WorldTransform transform = new WorldTransform(centerX, centerZ, axisX, axisZ,
                    runwayReal, airportScale, inner, outer, globalScale);

            int gridWidth = in.readInt();
            if (gridWidth < 8 || gridWidth > MAX_GRID_SIDE) throw new IOException("Terrain grid width out of bounds");
            float minX = finite(in.readFloat(), "terrain min x");
            float minZ = finite(in.readFloat(), "terrain min z");
            float stepX = finite(in.readFloat(), "terrain step x");
            float stepZ = finite(in.readFloat(), "terrain step z");
            if (!(stepX > 0f) || !(stepZ > 0f)) throw new IOException("Terrain grid step must be positive");
            int cells = in.readInt();
            if (cells != gridWidth * gridWidth) throw new IOException("Terrain cell count mismatch");
            float[] elevations = new float[cells];
            byte[] land = new byte[cells];
            int landSamples = 0;
            for (int i = 0; i < cells; i++) {
                elevations[i] = finite(in.readFloat(), "terrain elevation");
                land[i] = in.readByte();
                if (land[i] != 0 && land[i] != 1) throw new IOException("Invalid terrain land mask");
                if (land[i] == 1) landSamples++;
            }
            if (landSamples < cells / 12) throw new IOException("World terrain mask is unexpectedly sparse");

            int outlineCount = boundedCount(in.readInt(), 3, MAX_OUTLINE, "coastline");
            float[] outline = new float[outlineCount * 2];
            for (int i = 0; i < outline.length; i++) outline[i] = finite(in.readFloat(), "coastline coordinate");

            int featureCount = boundedCount(in.readInt(), 1, MAX_FEATURES, "features");
            List<Feature> features = new ArrayList<>(featureCount);
            int roads = 0, places = 0, runways = 0, buildings = 0;
            for (int i = 0; i < featureCount; i++) {
                int type = in.readUnsignedByte();
                long osmId = in.readLong();
                String name = readString(in);
                int count = boundedCount(in.readInt(), 1, MAX_POINTS, "feature points");
                float[] points = new float[count * 2];
                for (int j = 0; j < points.length; j++) points[j] = finite(in.readFloat(), "feature coordinate");
                Feature feature = new Feature(type, osmId, name, points);
                features.add(feature);
                if (type == ROAD || type == TAXIWAY) roads++;
                if (type == PLACE && !name.isEmpty()) places++;
                if (type == RUNWAY) runways++;
                if (type == BUILDING) buildings++;
            }
            if (roads == 0 || places == 0 || runways == 0) throw new IOException("World is missing roads, named places, or runway geometry");

            int spawnCount = boundedCount(in.readInt(), 5, MAX_SPAWNS, "spawns");
            List<Spawn> spawns = new ArrayList<>(spawnCount);
            boolean[] hasSpawn = new boolean[6];
            for (int i = 0; i < spawnCount; i++) {
                int type = in.readUnsignedByte();
                if (type < 1 || type > 5 || hasSpawn[type]) throw new IOException("Invalid or duplicate spawn type " + type);
                hasSpawn[type] = true;
                Spawn spawn = new Spawn(type, readString(in),
                        finite(in.readFloat(), "spawn x"), finite(in.readFloat(), "spawn z"),
                        finite(in.readFloat(), "spawn local x"), finite(in.readFloat(), "spawn local z"),
                        finite(in.readFloat(), "spawn height"), finite(in.readFloat(), "spawn yaw"));
                Vector3 mapped = transform.mapSpawn(spawn, new Vector3());
                if (!Float.isFinite(mapped.x) || !Float.isFinite(mapped.y) || !Float.isFinite(mapped.z))
                    throw new IOException("A spawn transformed to a non-finite location");
                spawns.add(spawn);
            }
            for (int type = 1; type <= 5; type++) if (!hasSpawn[type]) throw new IOException("Missing required spawn type " + type);
            if (in.available() != 0) throw new IOException("World data has unparsed trailing bytes");
            return new WorldData(lat0, lon0, transform, transformedRunway, gridWidth, minX, minZ,
                    stepX, stepZ, elevations, land, outline, features, spawns, digestHex);
        } catch (RuntimeException ex) {
            throw new IOException("World package failed validation", ex);
        }
    }

    public Spawn spawn(int type) {
        for (Spawn spawn : spawns) if (spawn.type == type) return spawn;
        throw new IllegalArgumentException("No spawn type " + type);
    }

    public float sampleElevation(float realX, float realZ) {
        float gx = (realX - gridMinX) / gridStepX;
        float gz = (realZ - gridMinZ) / gridStepZ;
        int x0 = clamp((int) Math.floor(gx), 0, gridWidth - 1);
        int z0 = clamp((int) Math.floor(gz), 0, gridWidth - 1);
        int x1 = Math.min(gridWidth - 1, x0 + 1);
        int z1 = Math.min(gridWidth - 1, z0 + 1);
        float tx = clamp(gx - x0, 0f, 1f);
        float tz = clamp(gz - z0, 0f, 1f);
        float a = lerp(elevations[z0 * gridWidth + x0], elevations[z0 * gridWidth + x1], tx);
        float b = lerp(elevations[z1 * gridWidth + x0], elevations[z1 * gridWidth + x1], tx);
        return lerp(a, b, tz);
    }

    public static final class Feature {
        public final int type;
        public final long osmId;
        public final String name;
        /** Alternating real ENU x,z values. */
        public final float[] points;
        Feature(int type, long osmId, String name, float[] points) {
            this.type = type; this.osmId = osmId; this.name = name; this.points = points;
        }
    }

    public static final class Spawn {
        public final int type;
        public final String name;
        public final float realX, realZ, offsetX, offsetZ, height, yaw;
        Spawn(int type, String name, float realX, float realZ, float offsetX, float offsetZ, float height, float yaw) {
            this.type = type; this.name = name; this.realX = realX; this.realZ = realZ;
            this.offsetX = offsetX; this.offsetZ = offsetZ; this.height = height; this.yaw = yaw;
        }
    }

    private static int boundedCount(int value, int min, int max, String what) throws IOException {
        if (value < min || value > max) throw new IOException(what + " count outside bounds: " + value);
        return value;
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len > 1024) throw new IOException("String length outside bounds: " + len);
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static float finite(float value, String field) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("Non-finite value in " + field);
        return value;
    }

    private static byte[] sha256(byte[] bytes, int offset, int length) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, offset, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 unavailable", ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
