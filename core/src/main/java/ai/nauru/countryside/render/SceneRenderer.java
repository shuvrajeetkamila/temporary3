package ai.nauru.countryside.render;

import ai.nauru.countryside.physics.PhysicsWorld;
import ai.nauru.countryside.world.WorldData;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ShortArray;

import java.util.ArrayList;
import java.util.List;

/** Procedural, data-driven GLES 3D scene. All scene geometry is generated locally from NCRW/OpenStreetMap data. */
public final class SceneRenderer implements Disposable {
    private static final long ATTRIBUTES = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;
    private static final int TERRAIN_CHUNK_CELLS = 32;

    private final WorldData data;
    private final ModelBatch modelBatch;
    private final SpriteBatch upscaleBatch;
    private final Environment environment;
    private FrameBuffer scaledTarget;
    private float renderScale = 1f;
    private float viewDistance = 560f;
    private float cameraDistanceMultiplier = 1f;
    private final PerspectiveCamera camera;
    private final Array<Model> ownedModels = new Array<>();
    private final List<WorldItem> worldItems = new ArrayList<>();
    private final List<PlaceMarker> places = new ArrayList<>();
    private final Part[] humanParts;
    private final Part[] carParts;
    private final Part[] boatParts;
    private final Part[] helicopterParts;
    private final Part[] airplaneParts;
    private final Vector3 cameraPosition = new Vector3();
    private final Vector3 desiredCameraPosition = new Vector3();
    private final Vector3 target = new Vector3();
    private final Vector3 playerPosition = new Vector3();
    private final Vector3 vertexA = new Vector3();
    private final Vector3 vertexB = new Vector3();
    private final Vector3 vertexC = new Vector3();
    private final Vector3 vertexD = new Vector3();
    private final Vector3 normal = new Vector3();
    private final Vector3 tangent = new Vector3();
    private final Vector3 side = new Vector3();
    private final Vector3 point = new Vector3();
    private final Matrix4 geometryTransform = new Matrix4();
    private final Material waterMaterial = material(0.055f, 0.36f, 0.50f, 1f, 0.25f);
    private final Material asphaltMaterial = material(0.20f, 0.22f, 0.23f, 1f, 0.04f);
    private final Material taxiMaterial = material(0.29f, 0.30f, 0.29f, 1f, 0.03f);
    private final Material roadMaterial = material(0.38f, 0.37f, 0.32f, 1f, 0.02f);
    private final Material pierMaterial = material(0.35f, 0.24f, 0.14f, 1f, 0.01f);
    private final Material coastlineMaterial = material(0.79f, 0.72f, 0.48f, 1f, 0.01f);
    private final Material terrainMaterial = material(0.34f, 0.48f, 0.27f, 1f, 0.01f);
    private final Material jungleMaterial = material(0.15f, 0.36f, 0.20f, 1f, 0.01f);
    private final Material buildingMaterial = material(0.65f, 0.58f, 0.46f, 1f, 0.02f);
    private final Material markerMaterial = material(0.96f, 0.66f, 0.12f, 1f, 0.06f);
    private final Material skinMaterial = material(0.72f, 0.44f, 0.28f, 1f, 0.02f);
    private final Material shirtMaterial = material(0.16f, 0.33f, 0.54f, 1f, 0.02f);
    private final Material wheelMaterial = material(0.07f, 0.08f, 0.09f, 1f, 0.02f);
    private final ModelInstance sea;
    private final ModelInstance person;
    private final ModelInstance carBody;
    private final ModelInstance boatBody;
    private final ModelInstance helicopterBody;
    private final ModelInstance airplaneBody;
    private final ModelInstance carRoof;
    private final ModelInstance helicopterCanopy;
    private final ModelInstance helicopterRotor;
    private final ModelInstance airplaneWing;
    private final ModelInstance airplaneTail;
    private final ModelInstance airplaneProp;
    private final ModelInstance[] carWheels = new ModelInstance[4];
    private boolean cameraInitialized;
    private float viewportWidth = 1f;
    private float viewportHeight = 1f;
    private String nearbyPlace = "";

    public SceneRenderer(WorldData data, float viewportWidth, float viewportHeight) {
        this.data = data;
        modelBatch = new ModelBatch();
        upscaleBatch = new SpriteBatch();
        environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(0.68f, 0.70f, 0.68f, 1f));
        environment.add(new DirectionalLight().set(0.90f, 0.85f, 0.73f, -0.42f, -0.82f, 0.31f));
        camera = new PerspectiveCamera(65f, 1f, 1f);
        camera.near = 0.25f;
        camera.far = 5_500f;
        resize(viewportWidth, viewportHeight);

        Model seaModel = own(new ModelBuilder().createBox(1f, 1f, 1f, waterMaterial, ATTRIBUTES));
        sea = new ModelInstance(seaModel);
        sea.transform.setToTranslation(0f, -0.10f, 0f).scale(5_000f, 0.12f, 5_000f);
        addWorldItem(sea, 0f, 0f, Float.MAX_VALUE);

        buildTerrain();
        buildCoastline();
        buildFeatureSurfaces();
        buildRoads();
        buildBuildings();
        buildTrees();
        buildPlaceMarkers();

        Model humanModel = createHumanModel();
        person = new ModelInstance(humanModel);
        humanParts = new Part[]{new Part(person, 0f, -0.91f, 0f, 0.76f, 0.95f, 0.76f)};
        Model carModel = own(new ModelBuilder().createBox(1f, 1f, 1f,
                material(0.64f, 0.15f, 0.09f, 1f, 0.08f), ATTRIBUTES));
        Model roofModel = own(new ModelBuilder().createBox(1f, 1f, 1f,
                material(0.77f, 0.78f, 0.72f, 1f, 0.08f), ATTRIBUTES));
        Model wheelModel = own(new ModelBuilder().createCylinder(1f, 1f, 1f, 10, wheelMaterial, ATTRIBUTES));
        carBody = new ModelInstance(carModel);
        carRoof = new ModelInstance(roofModel);
        for (int i = 0; i < carWheels.length; i++) carWheels[i] = new ModelInstance(wheelModel);
        carParts = new Part[]{
                new Part(carBody, 0f, 0f, 0f, 1.92f, 0.78f, 3.9f),
                new Part(carRoof, 0f, 0.69f, 0.18f, 1.42f, 0.65f, 1.90f),
                new Part(carWheels[0], -0.98f, -0.36f, -1.18f, 0.78f, 0.62f, 0.78f, 0f, 0f, 90f),
                new Part(carWheels[1], 0.98f, -0.36f, -1.18f, 0.78f, 0.62f, 0.78f, 0f, 0f, 90f),
                new Part(carWheels[2], -0.98f, -0.36f, 1.18f, 0.78f, 0.62f, 0.78f, 0f, 0f, 90f),
                new Part(carWheels[3], 0.98f, -0.36f, 1.18f, 0.78f, 0.62f, 0.78f, 0f, 0f, 90f)};

        Model boatModel = own(new ModelBuilder().createBox(1f, 1f, 1f,
                material(0.12f, 0.42f, 0.48f, 1f, 0.08f), ATTRIBUTES));
        boatBody = new ModelInstance(boatModel);
        boatParts = new Part[]{new Part(boatBody, 0f, -0.05f, 0f, 1.55f, 0.72f, 3.9f)};

        Model heliModel = own(new ModelBuilder().createBox(1f, 1f, 1f,
                material(0.17f, 0.40f, 0.27f, 1f, 0.05f), ATTRIBUTES));
        Model heliCanopyModel = own(new ModelBuilder().createSphere(1f, 1f, 1f, 12, 8,
                material(0.22f, 0.50f, 0.59f, 1f, 0.22f), ATTRIBUTES));
        Model rotorModel = own(new ModelBuilder().createBox(1f, 1f, 1f,
                material(0.12f, 0.14f, 0.13f, 1f, 0.05f), ATTRIBUTES));
        helicopterBody = new ModelInstance(heliModel);
        helicopterCanopy = new ModelInstance(heliCanopyModel);
        helicopterRotor = new ModelInstance(rotorModel);
        helicopterParts = new Part[]{new Part(helicopterBody, 0f, 0f, 0f, 1.6f, 1.25f, 4.0f),
                new Part(helicopterCanopy, 0f, 0.2f, -0.75f, 1.75f, 1.02f, 1.25f),
                new Part(helicopterRotor, 0f, 1.0f, 0f, 9.4f, 0.10f, 0.25f, 0f, 0f, 0f)};

        Model airplaneModel = own(new ModelBuilder().createBox(1f, 1f, 1f,
                material(0.84f, 0.78f, 0.55f, 1f, 0.06f), ATTRIBUTES));
        Model wingModel = own(new ModelBuilder().createBox(1f, 1f, 1f,
                material(0.90f, 0.85f, 0.67f, 1f, 0.06f), ATTRIBUTES));
        Model propModel = own(new ModelBuilder().createBox(1f, 1f, 1f,
                material(0.12f, 0.13f, 0.13f, 1f, 0.04f), ATTRIBUTES));
        airplaneBody = new ModelInstance(airplaneModel);
        airplaneWing = new ModelInstance(wingModel);
        airplaneTail = new ModelInstance(wingModel);
        airplaneProp = new ModelInstance(propModel);
        airplaneParts = new Part[]{new Part(airplaneBody, 0f, 0f, 0.25f, 0.82f, 0.82f, 4.8f),
                new Part(airplaneWing, 0f, 0.20f, 0.1f, 10.4f, 0.16f, 1.55f),
                new Part(airplaneTail, 0f, 0.62f, 1.85f, 3.7f, 0.13f, 0.85f),
                new Part(airplaneProp, 0f, 0f, -2.48f, 0.12f, 2.45f, 0.13f)};
    }

    public void resize(float width, float height) {
        viewportWidth = Math.max(1f, width);
        viewportHeight = Math.max(1f, height);
        camera.viewportWidth = viewportWidth;
        camera.viewportHeight = viewportHeight;
        camera.update();
        rebuildScaledTarget();
    }

    public void setQuality(float worldViewDistance, float newRenderScale) {
        viewDistance = Math.max(220f, worldViewDistance);
        newRenderScale = Math.max(0.52f, Math.min(1f, newRenderScale));
        if (Math.abs(renderScale - newRenderScale) > 0.015f) {
            renderScale = newRenderScale;
            rebuildScaledTarget();
        }
    }

    public void setCameraDistanceMultiplier(float multiplier) {
        cameraDistanceMultiplier = Math.max(0.62f, Math.min(1.65f, multiplier));
    }

    private void rebuildScaledTarget() {
        if (scaledTarget != null) {
            scaledTarget.dispose();
            scaledTarget = null;
        }
        if (renderScale >= 0.995f) return;
        int width = Math.max(320, (int) (viewportWidth * renderScale));
        int height = Math.max(240, (int) (viewportHeight * renderScale));
        try {
            scaledTarget = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, true);
        } catch (RuntimeException unavailable) {
            renderScale = 1f;
            scaledTarget = null;
        }
    }

    private void buildTerrain() {
        int width = data.gridWidth;
        Vector3[] grid = new Vector3[width * width];
        for (int row = 0; row < width; row++) {
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                grid[index] = data.transform.map(data.gridMinX + col * data.gridStepX,
                        data.elevations[index], data.gridMinZ + row * data.gridStepZ, new Vector3());
            }
        }
        for (int row0 = 0; row0 < width - 1; row0 += TERRAIN_CHUNK_CELLS) {
            for (int col0 = 0; col0 < width - 1; col0 += TERRAIN_CHUNK_CELLS) {
                int rowMax = Math.min(width - 1, row0 + TERRAIN_CHUNK_CELLS);
                int colMax = Math.min(width - 1, col0 + TERRAIN_CHUNK_CELLS);
                boolean hasLand = false;
                for (int row = row0; row < rowMax && !hasLand; row++) {
                    for (int col = col0; col < colMax; col++) {
                        int i = row * width + col;
                        if (data.landMask[i] != 0 && data.landMask[i + 1] != 0
                                && data.landMask[i + width] != 0 && data.landMask[i + width + 1] != 0) {
                            hasLand = true; break;
                        }
                    }
                }
                if (!hasLand) continue;
                ModelBuilder builder = new ModelBuilder();
                builder.begin();
                MeshPartBuilder part = builder.part("dem-terrain", GL20.GL_TRIANGLES, ATTRIBUTES, terrainMaterial);
                for (int row = row0; row < rowMax; row++) {
                    for (int col = col0; col < colMax; col++) {
                        int i = row * width + col;
                        if (data.landMask[i] == 0 || data.landMask[i + 1] == 0
                                || data.landMask[i + width] == 0 || data.landMask[i + width + 1] == 0) continue;
                        Vector3 p00 = grid[i];
                        Vector3 p10 = grid[i + 1];
                        Vector3 p01 = grid[i + width];
                        Vector3 p11 = grid[i + width + 1];
                        normal.set(p01).sub(p00).crs(vertexA.set(p10).sub(p00)).nor();
                        if (normal.y < 0f) normal.scl(-1f);
                        part.rect(p00, p01, p11, p10, normal);
                    }
                }
                Model model = own(builder.end());
                ModelInstance instance = new ModelInstance(model);
                Vector3 center = instance.transform.getTranslation(new Vector3());
                addWorldItem(instance, center.x, center.z, 380f);
            }
        }
    }

    private void buildCoastline() {
        if (data.coastlineXZ.length < 6) return;
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder part = builder.part("coastal-sand-edge", GL20.GL_TRIANGLES, ATTRIBUTES, coastlineMaterial);
        int points = data.coastlineXZ.length / 2;
        for (int i = 0; i < points; i++) {
            int j = (i + 1) % points;
            float ax = data.coastlineXZ[i * 2], az = data.coastlineXZ[i * 2 + 1];
            float bx = data.coastlineXZ[j * 2], bz = data.coastlineXZ[j * 2 + 1];
            if (!mapAtGround(ax, az, vertexA) || !mapAtGround(bx, bz, vertexB)) continue;
            float dx = vertexB.x - vertexA.x, dz = vertexB.z - vertexA.z;
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            if (length < 0.03f) continue;
            float nx = -dz / length * 0.42f, nz = dx / length * 0.42f;
            vertexC.set(vertexA.x - nx, vertexA.y + 0.05f, vertexA.z - nz);
            vertexD.set(vertexA.x + nx, vertexA.y + 0.05f, vertexA.z + nz);
            point.set(vertexB.x + nx, vertexB.y + 0.05f, vertexB.z + nz);
            normal.set(vertexB.x - nx, vertexB.y + 0.05f, vertexB.z - nz);
            part.rect(vertexC, vertexD, point, normal, Vector3.Y);
        }
        addBuiltModel(builder, "coastline", 0f, 0f, Float.MAX_VALUE);
    }

    private void buildFeatureSurfaces() {
        EarClippingTriangulator triangulator = new EarClippingTriangulator();
        for (int k = 0; k < data.features.size(); k++) {
            WorldData.Feature feature = data.features.get(k);
            if ((feature.type != WorldData.WATER && feature.type != WorldData.LANDUSE && feature.type != WorldData.RUNWAY)
                    || feature.points.length < 6) continue;
            int count = feature.points.length / 2;
            if (count > 4_000) continue;
            if (count > 3 && samePoint(feature.points, 0, count - 1)) count--;
            if (count < 3) continue;
            float[] flat = new float[count * 2];
            Vector3[] mapped = new Vector3[count];
            float y = feature.type == WorldData.WATER ? 0.12f : 0.08f;
            for (int i = 0; i < count; i++) {
                float realX = feature.points[i * 2], realZ = feature.points[i * 2 + 1];
                float elevation = feature.type == WorldData.WATER ? y : data.sampleElevation(realX, realZ);
                mapped[i] = feature.type == WorldData.RUNWAY
                        ? data.transform.mapRunwayVertex(realX, elevation + 0.16f, realZ, new Vector3())
                        : data.transform.map(realX, elevation + y, realZ, new Vector3());
                flat[i * 2] = mapped[i].x;
                flat[i * 2 + 1] = mapped[i].z;
            }
            ShortArray indices;
            try { indices = triangulator.computeTriangles(flat); }
            catch (RuntimeException malformedPolygon) { continue; }
            if (indices.size < 3) continue;
            Material surface = feature.type == WorldData.WATER ? waterMaterial
                    : feature.type == WorldData.RUNWAY ? asphaltMaterial : jungleMaterial;
            ModelBuilder builder = new ModelBuilder();
            builder.begin();
            MeshPartBuilder part = builder.part("feature-area-" + feature.osmId,
                    GL20.GL_TRIANGLES, ATTRIBUTES, surface);
            for (int i = 0; i + 2 < indices.size; i += 3) {
                int ia = indices.get(i) & 0xffff, ib = indices.get(i + 1) & 0xffff, ic = indices.get(i + 2) & 0xffff;
                if (ia >= count || ib >= count || ic >= count) continue;
                part.triangle(new MeshPartBuilder.VertexInfo().setPos(mapped[ia]).setNor(Vector3.Y),
                        new MeshPartBuilder.VertexInfo().setPos(mapped[ib]).setNor(Vector3.Y),
                        new MeshPartBuilder.VertexInfo().setPos(mapped[ic]).setNor(Vector3.Y));
            }
            addBuiltModel(builder, "area-" + feature.osmId, mapped[0].x, mapped[0].z, 350f);
        }
    }

    private boolean samePoint(float[] coordinates, int a, int b) {
        return Math.abs(coordinates[a * 2] - coordinates[b * 2]) < 0.001f
                && Math.abs(coordinates[a * 2 + 1] - coordinates[b * 2 + 1]) < 0.001f;
    }

    private void buildRoads() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder roads = builder.part("naru-roads", GL20.GL_TRIANGLES, ATTRIBUTES, roadMaterial);
        MeshPartBuilder taxiways = builder.part("airport-taxiways", GL20.GL_TRIANGLES, ATTRIBUTES, taxiMaterial);
        MeshPartBuilder piers = builder.part("harbour-piers", GL20.GL_TRIANGLES, ATTRIBUTES, pierMaterial);
        boolean wrote = false;
        for (int k = 0; k < data.features.size(); k++) {
            WorldData.Feature feature = data.features.get(k);
            if (feature.points.length < 4) continue;
            MeshPartBuilder part;
            float width;
            if (feature.type == WorldData.ROAD) { part = roads; width = 5.0f; }
            else if (feature.type == WorldData.TAXIWAY) { part = taxiways; width = 11.0f; }
            else if (feature.type == WorldData.PIER) { part = piers; width = 3.5f; }
            else continue;
            for (int i = 0; i + 3 < feature.points.length; i += 2) {
                if (!mapAtGround(feature.points[i], feature.points[i + 1], vertexA)
                        || !mapAtGround(feature.points[i + 2], feature.points[i + 3], vertexB)) continue;
                float dx = vertexB.x - vertexA.x, dz = vertexB.z - vertexA.z;
                float len = (float) Math.sqrt(dx * dx + dz * dz);
                if (len < 0.035f) continue;
                float nx = -dz / len * width * 0.5f, nz = dx / len * width * 0.5f;
                vertexC.set(vertexA.x - nx, vertexA.y + 0.06f, vertexA.z - nz);
                vertexD.set(vertexA.x + nx, vertexA.y + 0.06f, vertexA.z + nz);
                point.set(vertexB.x + nx, vertexB.y + 0.06f, vertexB.z + nz);
                normal.set(vertexB.x - nx, vertexB.y + 0.06f, vertexB.z - nz);
                part.rect(vertexC, vertexD, point, normal, Vector3.Y);
                wrote = true;
            }
        }
        if (wrote) addBuiltModel(builder, "roads-taxiways-piers", 0f, 0f, Float.MAX_VALUE);
        else builder.end().dispose();
    }

    private void buildBuildings() {
        boolean hasBuildings = false;
        for (int i = 0; i < data.features.size(); i++)
            if (data.features.get(i).type == WorldData.BUILDING && data.features.get(i).points.length >= 6) {
                hasBuildings = true; break;
            }
        if (!hasBuildings) return;
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder houses = builder.part("human-scale-osm-buildings", GL20.GL_TRIANGLES, ATTRIBUTES, buildingMaterial);
        for (int k = 0; k < data.features.size(); k++) {
            WorldData.Feature feature = data.features.get(k);
            if (feature.type != WorldData.BUILDING || feature.points.length < 6) continue;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            float sumX = 0f, sumZ = 0f;
            for (int i = 0; i < feature.points.length; i += 2) {
                float x = feature.points[i], z = feature.points[i + 1];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                sumX += x; sumZ += z;
            }
            float anchorX = sumX / (feature.points.length / 2f), anchorZ = sumZ / (feature.points.length / 2f);
            float width = clamp(maxX - minX, 3.1f, 18f), depth = clamp(maxZ - minZ, 3.0f, 18f);
            float height = 3.2f + Math.abs(feature.osmId % 5) * 0.55f;
            Vector3 center = data.transform.mapFootprint(anchorX, data.sampleElevation(anchorX, anchorZ), anchorZ,
                    anchorX, anchorZ, point);
            center.y += height * 0.5f;
            geometryTransform.setToTranslation(center).scale(width, height, depth);
            houses.setVertexTransform(geometryTransform);
            houses.box(1f, 1f, 1f);
        }
        ModelInstance buildings = new ModelInstance(own(builder.end()));
        addWorldItem(buildings, 0f, 0f, Float.MAX_VALUE);
    }

    private void buildTrees() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder foliage = builder.part("procedural-tree-canopies", GL20.GL_TRIANGLES, ATTRIBUTES, jungleMaterial);
        MeshPartBuilder trunks = builder.part("procedural-tree-trunks", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.32f, 0.20f, 0.12f, 1f, 0.01f));
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < data.coastlineXZ.length; i += 2) {
            minX = Math.min(minX, data.coastlineXZ[i]); maxX = Math.max(maxX, data.coastlineXZ[i]);
            minZ = Math.min(minZ, data.coastlineXZ[i + 1]); maxZ = Math.max(maxZ, data.coastlineXZ[i + 1]);
        }
        int seed = 0x6e617572;
        int made = 0;
        for (int attempt = 0; attempt < 2_500 && made < 145; attempt++) {
            seed = seed * 1_664_525 + 1_013_904_223;
            float x = minX + ((seed >>> 8) & 0x00ffffff) / 16_777_216f * (maxX - minX);
            seed = seed * 1_664_525 + 1_013_904_223;
            float z = minZ + ((seed >>> 8) & 0x00ffffff) / 16_777_216f * (maxZ - minZ);
            if (!insideCoastline(x, z) || !landAt(x, z)) continue;
            Vector3 mapped = data.transform.map(x, data.sampleElevation(x, z), z, point);
            seed = seed * 1_664_525 + 1_013_904_223;
            float scale = 0.72f + ((seed >>> 8) & 0xffff) / 65_535f * 0.63f;
            geometryTransform.setToTranslation(mapped.x, mapped.y + 1.7f * scale, mapped.z)
                    .scale(scale, scale, scale);
            foliage.setVertexTransform(geometryTransform);
            foliage.cone(2.9f, 4.2f, 2.9f, 7);
            geometryTransform.setToTranslation(mapped.x, mapped.y + 0.6f * scale, mapped.z)
                    .scale(scale, scale, scale);
            trunks.setVertexTransform(geometryTransform);
            trunks.cylinder(0.46f, 1.7f, 0.46f, 6);
            made++;
        }
        if (made > 0) {
            ModelInstance grove = new ModelInstance(own(builder.end()));
            addWorldItem(grove, 0f, 0f, Float.MAX_VALUE);
        } else builder.end().dispose();
    }

    private boolean insideCoastline(float x, float z) {
        boolean inside = false;
        int count = data.coastlineXZ.length / 2;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            float xi = data.coastlineXZ[i * 2], zi = data.coastlineXZ[i * 2 + 1];
            float xj = data.coastlineXZ[j * 2], zj = data.coastlineXZ[j * 2 + 1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi + 0.000001f) + xi) inside = !inside;
        }
        return inside;
    }

    private boolean landAt(float x, float z) {
        int col = Math.round((x - data.gridMinX) / data.gridStepX);
        int row = Math.round((z - data.gridMinZ) / data.gridStepZ);
        if (col < 0 || col >= data.gridWidth || row < 0 || row >= data.gridWidth) return false;
        return data.landMask[row * data.gridWidth + col] != 0;
    }

    private void buildPlaceMarkers() {
        Model marker = own(new ModelBuilder().createCylinder(0.11f, 2.4f, 0.11f, 6, markerMaterial, ATTRIBUTES));
        Model crown = own(new ModelBuilder().createSphere(0.48f, 0.48f, 0.48f, 8, 6, markerMaterial, ATTRIBUTES));
        for (int k = 0; k < data.features.size(); k++) {
            WorldData.Feature feature = data.features.get(k);
            if (feature.type != WorldData.PLACE || feature.name == null || feature.name.isEmpty()
                    || feature.points.length < 2) continue;
            float x = feature.points[0], z = feature.points[1];
            Vector3 mapped = data.transform.map(x, data.sampleElevation(x, z), z, new Vector3());
            ModelInstance post = new ModelInstance(marker);
            post.transform.setToTranslation(mapped.x, mapped.y + 1.15f, mapped.z);
            addWorldItem(post, mapped.x, mapped.z, 18f);
            ModelInstance orb = new ModelInstance(crown);
            orb.transform.setToTranslation(mapped.x, mapped.y + 2.45f, mapped.z);
            addWorldItem(orb, mapped.x, mapped.z, 18f);
            places.add(new PlaceMarker(feature.name, mapped.x, mapped.y, mapped.z));
        }
    }

    private void addBuiltModel(ModelBuilder builder, String name, float x, float z, float radius) {
        Model model = own(builder.end());
        ModelInstance instance = new ModelInstance(model);
        addWorldItem(instance, x, z, radius);
    }

    private boolean mapAtGround(float realX, float realZ, Vector3 out) {
        return data.transform.map(realX, data.sampleElevation(realX, realZ), realZ, out) != null;
    }

    private void addWorldItem(ModelInstance instance, float x, float z, float radius) {
        worldItems.add(new WorldItem(instance, x, z, radius));
    }

    private Model createHumanModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder torso = builder.part("shirt", GL20.GL_TRIANGLES, ATTRIBUTES, shirtMaterial);
        torso.setVertexTransform(new Matrix4().setToTranslation(0f, 1.23f, 0f));
        torso.box(0.62f, 0.86f, 0.37f);
        MeshPartBuilder head = builder.part("head", GL20.GL_TRIANGLES, ATTRIBUTES, skinMaterial);
        head.setVertexTransform(new Matrix4().setToTranslation(0f, 1.91f, 0f));
        head.sphere(0.43f, 0.43f, 0.43f, 8, 6);
        MeshPartBuilder legs = builder.part("trousers", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.12f, 0.16f, 0.19f, 1f, 0.02f));
        legs.setVertexTransform(new Matrix4().setToTranslation(-0.18f, 0.47f, 0f));
        legs.box(0.22f, 0.78f, 0.28f);
        legs.setVertexTransform(new Matrix4().setToTranslation(0.18f, 0.47f, 0f));
        legs.box(0.22f, 0.78f, 0.28f);
        MeshPartBuilder arms = builder.part("arms", GL20.GL_TRIANGLES, ATTRIBUTES, skinMaterial);
        arms.setVertexTransform(new Matrix4().setToTranslation(-0.42f, 1.25f, 0f));
        arms.box(0.19f, 0.76f, 0.23f);
        arms.setVertexTransform(new Matrix4().setToTranslation(0.42f, 1.25f, 0f));
        arms.box(0.19f, 0.76f, 0.23f);
        return own(builder.end());
    }

    private static Material material(float r, float g, float b, float a, float shininess) {
        return new Material(ColorAttribute.createDiffuse(new Color(r, g, b, a)),
                ColorAttribute.createSpecular(new Color(0.11f, 0.11f, 0.11f, 1f)),
                com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute.createShininess(shininess));
    }

    private Model own(Model model) { ownedModels.add(model); return model; }

    public String updateAndRender(PhysicsWorld physics, int mode, float cameraYaw, float cameraLift,
                                  float deltaSeconds, float simTime) {
        PhysicsWorld.Body player = physics.bodyForMode(mode);
        physics.position(player, playerPosition);
        float yaw = physics.yaw(player);
        float distance = (mode == PhysicsWorld.WALK ? 8.8f
                : mode == PhysicsWorld.HELICOPTER ? 25f
                : mode == PhysicsWorld.AIRPLANE ? 28f
                : mode == PhysicsWorld.BOAT ? 13f : 12f) * cameraDistanceMultiplier;
        float height = (mode == PhysicsWorld.WALK ? 4.0f
                : mode == PhysicsWorld.HELICOPTER ? 9.0f
                : mode == PhysicsWorld.AIRPLANE ? 10.5f : 5.5f) * cameraDistanceMultiplier;
        // The camera's world-space azimuth is touch-adjustable and follows the vehicle heading when centered.
        float orbit = cameraYaw + yaw * 0.20f;
        desiredCameraPosition.set(playerPosition.x + (float) Math.sin(orbit) * distance,
                playerPosition.y + height + cameraLift, playerPosition.z + (float) Math.cos(orbit) * distance);
        target.set(playerPosition).add(0f, mode == PhysicsWorld.WALK ? 0.9f : 0.4f, 0f);
        float blend = cameraInitialized ? 1f - (float) Math.exp(-Math.max(0f, deltaSeconds) * 5.5f) : 1f;
        cameraPosition.lerp(desiredCameraPosition, blend);
        camera.position.set(cameraPosition);
        camera.lookAt(target);
        camera.up.set(Vector3.Y);
        camera.update();
        cameraInitialized = true;

        updateParts(humanParts, physics.position(physics.character, point), physics.yaw(physics.character), 0f);
        updateParts(carParts, physics.position(physics.car, point), physics.yaw(physics.car), 0f);
        updateParts(boatParts, physics.position(physics.boat, point), physics.yaw(physics.boat), 0f);
        updateParts(helicopterParts, physics.position(physics.helicopter, point), physics.yaw(physics.helicopter),
                simTime * 780f * physics.rotorRpm());
        updateParts(airplaneParts, physics.position(physics.airplane, point), physics.yaw(physics.airplane),
                simTime * 550f * physics.aircraftRpm());

        if (scaledTarget != null) scaledTarget.begin();
        else Gdx.gl.glViewport(0, 0, (int) viewportWidth, (int) viewportHeight);
        Gdx.gl.glClearColor(0.49f, 0.72f, 0.82f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        modelBatch.begin(camera);
        for (int i = 0; i < worldItems.size(); i++) {
            WorldItem item = worldItems.get(i);
            float dx = item.x - cameraPosition.x, dz = item.z - cameraPosition.z;
            float maxDistance = item.radius == Float.MAX_VALUE ? viewDistance : Math.min(item.radius, viewDistance);
            if (dx * dx + dz * dz > maxDistance * maxDistance) continue;
            modelBatch.render(item.instance, environment);
        }
        drawParts(humanParts, mode == PhysicsWorld.WALK);
        drawParts(carParts, mode == PhysicsWorld.CAR);
        drawParts(boatParts, mode == PhysicsWorld.BOAT);
        drawParts(helicopterParts, mode == PhysicsWorld.HELICOPTER);
        drawParts(airplaneParts, mode == PhysicsWorld.AIRPLANE);
        modelBatch.end();
        if (scaledTarget != null) {
            scaledTarget.end();
            Gdx.gl.glViewport(0, 0, (int) viewportWidth, (int) viewportHeight);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            upscaleBatch.getProjectionMatrix().setToOrtho2D(0f, 0f, viewportWidth, viewportHeight);
            upscaleBatch.begin();
            upscaleBatch.draw(scaledTarget.getColorBufferTexture(), 0f, 0f, viewportWidth, viewportHeight,
                    0f, 1f, 1f, 0f);
            upscaleBatch.end();
        }
        nearbyPlace = nearestPlace(playerPosition.x, playerPosition.z);
        return nearbyPlace;
    }

    private void updateParts(Part[] parts, Vector3 position, float yaw, float rotorAngle) {
        float yawDegrees = (float) Math.toDegrees(yaw);
        for (int i = 0; i < parts.length; i++) {
            Part part = parts[i];
            part.instance.transform.idt().translate(position).rotate(Vector3.Y, yawDegrees)
                    .translate(part.x, part.y, part.z);
            if (part.rotationZ != 0f) part.instance.transform.rotate(Vector3.Z, part.rotationZ);
            if (part.rotationX != 0f) part.instance.transform.rotate(Vector3.X, part.rotationX);
            if (i == 2 && parts == helicopterParts) part.instance.transform.rotate(Vector3.Y, rotorAngle);
            if (i == 3 && parts == airplaneParts) part.instance.transform.rotate(Vector3.Z, rotorAngle);
            part.instance.transform.scale(part.scaleX, part.scaleY, part.scaleZ);
        }
    }

    private void drawParts(Part[] parts, boolean visible) {
        if (!visible) return;
        for (int i = 0; i < parts.length; i++) modelBatch.render(parts[i].instance, environment);
    }

    public String nearestPlace(float x, float z) {
        String nearest = "";
        float best = 70f * 70f;
        for (int i = 0; i < places.size(); i++) {
            PlaceMarker place = places.get(i);
            float dx = place.x - x, dz = place.z - z;
            float distance = dx * dx + dz * dz;
            if (distance < best) { best = distance; nearest = place.name; }
        }
        return nearest;
    }

    public float cameraYaw() { return (float) Math.atan2(cameraPosition.x - playerPosition.x,
            cameraPosition.z - playerPosition.z); }

    @Override public void dispose() {
        modelBatch.dispose();
        upscaleBatch.dispose();
        if (scaledTarget != null) scaledTarget.dispose();
        for (Model model : ownedModels) model.dispose();
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    private static final class WorldItem {
        final ModelInstance instance;
        final float x, z, radius;
        WorldItem(ModelInstance instance, float x, float z, float radius) {
            this.instance = instance; this.x = x; this.z = z; this.radius = radius;
        }
    }

    private static final class PlaceMarker {
        final String name;
        final float x, y, z;
        PlaceMarker(String name, float x, float y, float z) { this.name = name; this.x = x; this.y = y; this.z = z; }
    }

    private static final class Part {
        final ModelInstance instance;
        final float x, y, z, scaleX, scaleY, scaleZ;
        final float rotationX, rotationY, rotationZ;
        Part(ModelInstance instance, float x, float y, float z, float sx, float sy, float sz) {
            this(instance, x, y, z, sx, sy, sz, 0f, 0f, 0f);
        }
        Part(ModelInstance instance, float x, float y, float z, float sx, float sy, float sz,
             float rotationX, float rotationY, float rotationZ) {
            this.instance = instance; this.x = x; this.y = y; this.z = z;
            this.scaleX = sx; this.scaleY = sy; this.scaleZ = sz;
            this.rotationX = rotationX; this.rotationY = rotationY; this.rotationZ = rotationZ;
        }
    }
}
