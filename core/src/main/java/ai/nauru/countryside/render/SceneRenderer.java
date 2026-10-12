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
    private static final long ATTRIBUTES = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
            | VertexAttributes.Usage.ColorUnpacked;
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
    private final Material waterMaterial = material(0.055f, 0.34f, 0.46f, 1f, 0.25f);
    private final Material asphaltMaterial = material(0.19f, 0.21f, 0.22f, 1f, 0.04f);
    private final Material taxiMaterial = material(0.27f, 0.29f, 0.28f, 1f, 0.03f);
    private final Material roadMaterial = material(0.34f, 0.33f, 0.29f, 1f, 0.02f);
    private final Material pierMaterial = material(0.31f, 0.21f, 0.13f, 1f, 0.01f);
    private final Material coastlineMaterial = material(0.83f, 0.76f, 0.57f, 1f, 0.01f);
    private final Material terrainMaterial = material(0.36f, 0.48f, 0.29f, 1f, 0.01f);
    private final Material jungleMaterial = material(0.13f, 0.31f, 0.17f, 1f, 0.01f);
    private final Material palmLeafMaterial = material(0.18f, 0.39f, 0.21f, 1f, 0.01f);
    private final Material palmLeafLightMaterial = material(0.27f, 0.46f, 0.22f, 1f, 0.01f);
    private final Material palmTrunkMaterial = material(0.36f, 0.25f, 0.15f, 1f, 0.01f);
    private final Material buildingMaterial = material(0.78f, 0.72f, 0.61f, 1f, 0.02f);
    private final Material roofMaterial = material(0.31f, 0.33f, 0.32f, 1f, 0.04f);
    private final Material windowMaterial = material(0.10f, 0.28f, 0.32f, 1f, 0.22f);
    private final Material markerMaterial = material(0.96f, 0.66f, 0.12f, 1f, 0.06f);
    private final Material skinMaterial = material(0.63f, 0.38f, 0.25f, 1f, 0.02f);
    private final Material shirtMaterial = material(0.13f, 0.32f, 0.43f, 1f, 0.02f);
    private final Material wheelMaterial = material(0.035f, 0.045f, 0.05f, 1f, 0.02f);
    private final Material metalMaterial = material(0.49f, 0.53f, 0.51f, 1f, 0.10f);
    private final Material vehicleGlassMaterial = material(0.10f, 0.28f, 0.33f, 1f, 0.28f);
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
        environment.set(ColorAttribute.createAmbientLight(0.49f, 0.54f, 0.50f, 1f));
        environment.add(new DirectionalLight().set(1.0f, 0.91f, 0.78f, -0.42f, -0.82f, 0.31f));
        camera = new PerspectiveCamera(60f, 1f, 1f);
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

        Model humanModel = own(createHumanModel());
        person = new ModelInstance(humanModel);
        humanParts = new Part[]{new Part(person, 0f, -0.91f, 0f, 0.76f, 0.95f, 0.76f)};

        Model carModel = own(createCarModel());
        Model roofModel = own(createRoofRackModel());
        Model wheelModel = own(createWheelModel());
        carBody = new ModelInstance(carModel);
        carRoof = new ModelInstance(roofModel);
        for (int i = 0; i < carWheels.length; i++) carWheels[i] = new ModelInstance(wheelModel);
        carParts = new Part[]{new Part(carBody, 0f, 0f, 0f, 1f, 1f, 1f),
                new Part(carRoof, 0f, 0.73f, 0.12f, 1f, 1f, 1f),
                new Part(carWheels[0], -0.96f, -0.34f, -1.24f, 0.79f, 0.64f, 0.79f, 0f, 0f, 90f),
                new Part(carWheels[1], 0.96f, -0.34f, -1.24f, 0.79f, 0.64f, 0.79f, 0f, 0f, 90f),
                new Part(carWheels[2], -0.96f, -0.34f, 1.20f, 0.79f, 0.64f, 0.79f, 0f, 0f, 90f),
                new Part(carWheels[3], 0.96f, -0.34f, 1.20f, 0.79f, 0.64f, 0.79f, 0f, 0f, 90f)};

        boatBody = new ModelInstance(own(createBoatModel()));
        boatParts = new Part[]{new Part(boatBody, 0f, 0f, 0f, 1f, 1f, 1f)};

        helicopterBody = new ModelInstance(own(createHelicopterModel()));
        Model heliCanopyModel = own(new ModelBuilder().createSphere(1f, 1f, 1f, 16, 12,
                vehicleGlassMaterial, ATTRIBUTES));
        Model rotorModel = own(createRotorModel());
        helicopterCanopy = new ModelInstance(heliCanopyModel);
        helicopterRotor = new ModelInstance(rotorModel);
        helicopterParts = new Part[]{new Part(helicopterBody, 0f, 0f, 0f, 1f, 1f, 1f),
                new Part(helicopterCanopy, 0f, 0.22f, -0.72f, 1.72f, 0.95f, 1.25f),
                new Part(helicopterRotor, 0f, 1.08f, 0f, 9.1f, 0.12f, 0.32f, 0f, 0f, 0f)};

        airplaneBody = new ModelInstance(own(createAirplaneFuselageModel()));
        Model wingModel = own(createAirplaneWingModel());
        Model tailModel = own(createAirplaneTailModel());
        Model propModel = own(createRotorModel());
        airplaneWing = new ModelInstance(wingModel);
        airplaneTail = new ModelInstance(tailModel);
        airplaneProp = new ModelInstance(propModel);
        airplaneParts = new Part[]{new Part(airplaneBody, 0f, 0f, 0f, 1f, 1f, 1f),
                new Part(airplaneWing, 0f, 0.56f, 0.02f, 1f, 1f, 1f),
                new Part(airplaneTail, 0f, 0.43f, 1.78f, 1f, 1f, 1f),
                new Part(airplaneProp, 0f, 0.02f, -2.26f, 0.14f, 2.05f, 0.12f, 0f, 0f, 0f)};
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
                        float meanHeight = (p00.y + p10.y + p01.y + p11.y) * 0.25f;
                        float patch = 0.94f + ((row * 37 + col * 19) & 7) * 0.009f;
                        float highGround = clamp((meanHeight - 18f) * 0.0018f, 0f, 0.06f);
                        part.setColor(patch, patch - highGround * 0.25f, patch - highGround, 1f);
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
            if (feature.type == WorldData.LANDUSE) {
                float shade = 0.86f + (Math.abs(feature.osmId) % 7) * 0.025f;
                part.setColor(shade, 0.96f + (shade - 0.86f) * 0.18f, shade, 1f);
            }
            for (int i = 0; i + 2 < indices.size; i += 3) {
                int ia = indices.get(i) & 0xffff, ib = indices.get(i + 1) & 0xffff, ic = indices.get(i + 2) & 0xffff;
                if (ia >= count || ib >= count || ic >= count) continue;
                part.triangle(new MeshPartBuilder.VertexInfo().setPos(mapped[ia]).setNor(Vector3.Y),
                        new MeshPartBuilder.VertexInfo().setPos(mapped[ib]).setNor(Vector3.Y),
                        new MeshPartBuilder.VertexInfo().setPos(mapped[ic]).setNor(Vector3.Y));
            }
            addBuiltModel(builder, "area-" + feature.osmId, mapped[0].x, mapped[0].z, 350f);
        }
        buildRunwayMarkings();
    }

    private void buildRunwayMarkings() {
        float halfWidth = 18f;
        for (WorldData.Feature feature : data.features) {
            if (feature.type != WorldData.RUNWAY) continue;
            for (int i = 0; i < feature.points.length; i += 2) {
                float dx = feature.points[i] - data.transform.runwayCenterX;
                float dz = feature.points[i + 1] - data.transform.runwayCenterZ;
                halfWidth = Math.max(halfWidth, Math.abs(dx * data.transform.rightX() + dz * data.transform.rightZ()) - 1.0f);
            }
        }
        float length = data.transform.runwayRealLength;
        float height = data.sampleElevation(data.transform.runwayCenterX, data.transform.runwayCenterZ) + 0.23f;
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder paint = builder.part("runway-centreline-and-thresholds", GL20.GL_TRIANGLES,
                ATTRIBUTES, material(0.86f, 0.85f, 0.78f, 1f, 0.02f));
        runwayQuad(paint, -length * 0.46f, -length * 0.43f, -halfWidth + 2f, halfWidth - 2f, height);
        runwayQuad(paint, length * 0.43f, length * 0.46f, -halfWidth + 2f, halfWidth - 2f, height);
        for (float along = -length * 0.34f; along < length * 0.34f; along += 52f)
            runwayQuad(paint, along, Math.min(along + 21f, length * 0.35f), -0.46f, 0.46f, height + 0.015f);
        for (int end : new int[]{-1, 1}) {
            float along = end * length * 0.425f;
            for (int stripe = -4; stripe <= 4; stripe++) {
                float lateral = stripe * halfWidth * 0.13f;
                runwayQuad(paint, along - 3.8f, along + 3.8f, lateral - 0.65f, lateral + 0.65f,
                        height + 0.02f);
            }
        }
        addBuiltModel(builder, "airport-markings", 0f, 0f, Float.MAX_VALUE);
    }

    private void runwayQuad(MeshPartBuilder part, float along0, float along1,
                            float lateral0, float lateral1, float height) {
        float centerX = data.transform.runwayCenterX, centerZ = data.transform.runwayCenterZ;
        float axisX = data.transform.axisX, axisZ = data.transform.axisZ;
        float rightX = data.transform.rightX(), rightZ = data.transform.rightZ();
        float x0 = centerX + axisX * along0 + rightX * lateral0;
        float z0 = centerZ + axisZ * along0 + rightZ * lateral0;
        float x1 = centerX + axisX * along0 + rightX * lateral1;
        float z1 = centerZ + axisZ * along0 + rightZ * lateral1;
        float x2 = centerX + axisX * along1 + rightX * lateral1;
        float z2 = centerZ + axisZ * along1 + rightZ * lateral1;
        float x3 = centerX + axisX * along1 + rightX * lateral0;
        float z3 = centerZ + axisZ * along1 + rightZ * lateral0;
        data.transform.mapRunwayVertex(x0, height, z0, vertexA);
        data.transform.mapRunwayVertex(x1, height, z1, vertexB);
        data.transform.mapRunwayVertex(x2, height, z2, vertexC);
        data.transform.mapRunwayVertex(x3, height, z3, vertexD);
        part.rect(vertexA, vertexB, vertexC, vertexD, Vector3.Y);
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
        BuildingBatch batch = null;
        int batchCount = 0;
        for (int k = 0; k < data.features.size(); k++) {
            WorldData.Feature feature = data.features.get(k);
            if (feature.type != WorldData.BUILDING || feature.points.length < 6) continue;
            if (batch == null) batch = new BuildingBatch();
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            float sumX = 0f, sumZ = 0f;
            for (int i = 0; i < feature.points.length; i += 2) {
                float x = feature.points[i], z = feature.points[i + 1];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                sumX += x; sumZ += z;
            }
            float anchorX = sumX / (feature.points.length / 2f), anchorZ = sumZ / (feature.points.length / 2f);
            float width = clamp(maxX - minX, 3.1f, 13.5f), depth = clamp(maxZ - minZ, 3.0f, 15f);
            float height = 2.8f + (Math.abs(feature.osmId) % 4) * 0.34f;
            Vector3 base = data.transform.mapFootprint(anchorX, data.sampleElevation(anchorX, anchorZ), anchorZ,
                    anchorX, anchorZ, point);
            int palette = (int) (Math.abs(feature.osmId) % 5);
            float[] wallR = {0.96f, 0.91f, 0.88f, 0.82f, 0.94f};
            float[] wallG = {0.90f, 0.83f, 0.88f, 0.91f, 0.86f};
            float[] wallB = {0.72f, 0.70f, 0.76f, 0.70f, 0.81f};
            batch.walls.setColor(wallR[palette], wallG[palette], wallB[palette], 1f);
            boxAt(batch.walls, base.x, base.y + height * 0.5f, base.z, width, height, depth);
            batch.roofs.setColor(0.78f + palette * 0.035f, 0.83f, 0.84f - palette * 0.045f, 1f);
            float roofWidth = Math.max(2f, width * 0.56f);
            float roofY = base.y + height + 0.12f;
            boxAt(batch.roofs, base.x - width * 0.235f, roofY, base.z, roofWidth, 0.16f, depth * 1.09f,
                    0f, 0f, -24f);
            boxAt(batch.roofs, base.x + width * 0.235f, roofY, base.z, roofWidth, 0.16f, depth * 1.09f,
                    0f, 0f, 24f);
            boxAt(batch.trim, base.x, base.y + 0.95f, base.z - depth * 0.505f,
                    Math.min(0.82f, width * 0.24f), 1.90f, 0.055f);
            float windowY = base.y + height * 0.63f;
            float windowWidth = Math.min(0.82f, width * 0.20f);
            float windowHeight = Math.min(0.72f, height * 0.26f);
            float frontZ = base.z - depth * 0.505f - 0.012f;
            if (width > 4.4f) {
                boxAt(batch.windows, base.x - width * 0.25f, windowY, frontZ, windowWidth, windowHeight, 0.035f);
                boxAt(batch.windows, base.x + width * 0.25f, windowY, frontZ, windowWidth, windowHeight, 0.035f);
            }
            if (depth > 4.2f) {
                boxAt(batch.windows, base.x - width * 0.505f - 0.012f, windowY, base.z - depth * 0.18f,
                        0.035f, windowHeight, Math.min(0.86f, depth * 0.20f));
                boxAt(batch.windows, base.x + width * 0.505f + 0.012f, windowY, base.z + depth * 0.18f,
                        0.035f, windowHeight, Math.min(0.86f, depth * 0.20f));
            }
            if (width > 5.5f && depth > 4.5f) {
                boxAt(batch.trim, base.x, base.y + 0.15f, base.z - depth * 0.60f,
                        Math.min(width * 0.58f, 5.5f), 0.20f, Math.min(1.25f, depth * 0.28f));
                for (float sideSign : new float[]{-1f, 1f})
                    boxAt(batch.trim, base.x + sideSign * Math.min(width * 0.27f, 2.4f),
                            base.y + 1.25f, base.z - depth * 0.58f, 0.07f, 2.5f, 0.07f);
            }
            batchCount++;
            if (batchCount >= 120) {
                addBuildingBatch(batch);
                batch = null;
                batchCount = 0;
            }
        }
        if (batch != null) addBuildingBatch(batch);
    }

    private void addBuildingBatch(BuildingBatch batch) {
        ModelInstance buildings = new ModelInstance(own(batch.builder.end()));
        addWorldItem(buildings, 0f, 0f, Float.MAX_VALUE);
    }

    private final class BuildingBatch {
        final ModelBuilder builder = new ModelBuilder();
        final MeshPartBuilder walls;
        final MeshPartBuilder roofs;
        final MeshPartBuilder windows;
        final MeshPartBuilder trim;

        BuildingBatch() {
            builder.begin();
            walls = builder.part("weathered-island-houses", GL20.GL_TRIANGLES, ATTRIBUTES, buildingMaterial);
            roofs = builder.part("corrugated-pitched-roofs", GL20.GL_TRIANGLES, ATTRIBUTES, roofMaterial);
            windows = builder.part("house-windows", GL20.GL_TRIANGLES, ATTRIBUTES, windowMaterial);
            trim = builder.part("porches-doors-and-eaves", GL20.GL_TRIANGLES, ATTRIBUTES,
                    material(0.29f, 0.24f, 0.18f, 1f, 0.02f));
        }
    }

    private void buildTrees() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder trunks = builder.part("coconut-palm-trunks", GL20.GL_TRIANGLES, ATTRIBUTES, palmTrunkMaterial);
        MeshPartBuilder fronds = builder.part("fan-shaped-palm-fronds", GL20.GL_TRIANGLES, ATTRIBUTES, palmLeafMaterial);
        MeshPartBuilder highlights = builder.part("sunlit-palm-fronds", GL20.GL_TRIANGLES, ATTRIBUTES, palmLeafLightMaterial);
        MeshPartBuilder coconuts = builder.part("coconut-clusters", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.42f, 0.30f, 0.15f, 1f, 0.01f));
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < data.coastlineXZ.length; i += 2) {
            minX = Math.min(minX, data.coastlineXZ[i]); maxX = Math.max(maxX, data.coastlineXZ[i]);
            minZ = Math.min(minZ, data.coastlineXZ[i + 1]); maxZ = Math.max(maxZ, data.coastlineXZ[i + 1]);
        }
        int seed = 0x6e617572;
        int made = 0;
        for (int attempt = 0; attempt < 3_200 && made < 115; attempt++) {
            seed = seed * 1_664_525 + 1_013_904_223;
            float x = minX + ((seed >>> 8) & 0x00ffffff) / 16_777_216f * (maxX - minX);
            seed = seed * 1_664_525 + 1_013_904_223;
            float z = minZ + ((seed >>> 8) & 0x00ffffff) / 16_777_216f * (maxZ - minZ);
            if (!insideCoastline(x, z) || !landAt(x, z)) continue;
            Vector3 mapped = data.transform.map(x, data.sampleElevation(x, z), z, point);
            seed = seed * 1_664_525 + 1_013_904_223;
            float scale = 0.82f + ((seed >>> 8) & 0xffff) / 65_535f * 0.43f;
            float lean = (((seed >>> 16) & 0xff) / 255f - 0.5f) * 7f;
            trunks.setColor(0.88f + (made % 4) * 0.025f, 0.88f, 0.84f, 1f);
            geometryTransform.idt().setToTranslation(mapped.x, mapped.y + 2.25f * scale, mapped.z)
                    .rotate(Vector3.Z, lean).scale(scale, scale, scale);
            trunks.setVertexTransform(geometryTransform);
            trunks.cylinder(0.27f, 4.5f, 0.27f, 9);
            int leafCount = 7 + (seed & 1);
            for (int leaf = 0; leaf < leafCount; leaf++) {
                float angle = 360f * leaf / leafCount + (made % 13) * 3.7f;
                MeshPartBuilder target = (leaf & 1) == 0 ? fronds : highlights;
                geometryTransform.idt().setToTranslation(mapped.x, mapped.y + 4.30f * scale, mapped.z)
                        .rotate(Vector3.Y, angle).rotate(Vector3.X, 52f)
                        .translate(0f, 0f, -1.35f).scale(scale, scale, scale);
                target.setVertexTransform(geometryTransform);
                target.box(0.16f, 0.09f, 3.35f);
            }
            for (int nut = 0; nut < 3; nut++) {
                float angle = nut * 120f + made * 7.0f;
                float radians = (float) Math.toRadians(angle);
                geometryTransform.idt().setToTranslation(mapped.x + (float) Math.cos(radians) * 0.20f * scale,
                        mapped.y + 4.00f * scale, mapped.z + (float) Math.sin(radians) * 0.20f * scale)
                        .scale(scale, scale, scale);
                coconuts.setVertexTransform(geometryTransform);
                coconuts.sphere(0.27f, 0.24f, 0.27f, 8, 6);
            }
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
        MeshPartBuilder shirt = builder.part("shirt-and-jacket", GL20.GL_TRIANGLES, ATTRIBUTES, shirtMaterial);
        sphereAt(shirt, 0f, 1.28f, 0f, 0.66f, 0.78f, 0.42f);
        boxAt(shirt, 0f, 0.88f, 0f, 0.47f, 0.30f, 0.34f);
        MeshPartBuilder trousers = builder.part("trousers", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.13f, 0.16f, 0.17f, 1f, 0.02f));
        cylinderAt(trousers, -0.18f, 0.45f, 0f, 0.25f, 0.77f, 0.27f, 0f, 0f, 0f);
        cylinderAt(trousers, 0.18f, 0.45f, 0f, 0.25f, 0.77f, 0.27f, 0f, 0f, 0f);
        MeshPartBuilder arms = builder.part("arms", GL20.GL_TRIANGLES, ATTRIBUTES, shirtMaterial);
        cylinderAt(arms, -0.43f, 1.24f, 0f, 0.22f, 0.66f, 0.24f, 0f, 0f, -9f);
        cylinderAt(arms, 0.43f, 1.24f, 0f, 0.22f, 0.66f, 0.24f, 0f, 0f, 9f);
        MeshPartBuilder skin = builder.part("face-and-hands", GL20.GL_TRIANGLES, ATTRIBUTES, skinMaterial);
        sphereAt(skin, 0f, 2.00f, 0f, 0.40f, 0.47f, 0.37f);
        sphereAt(skin, -0.49f, 0.91f, -0.015f, 0.18f, 0.19f, 0.18f);
        sphereAt(skin, 0.49f, 0.91f, -0.015f, 0.18f, 0.19f, 0.18f);
        sphereAt(skin, 0f, 1.78f, -0.01f, 0.22f, 0.13f, 0.23f);
        MeshPartBuilder hair = builder.part("hair", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.11f, 0.085f, 0.065f, 1f, 0.01f));
        sphereAt(hair, 0f, 2.20f, 0.035f, 0.41f, 0.25f, 0.38f);
        MeshPartBuilder eyes = builder.part("eyes", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.045f, 0.055f, 0.05f, 1f, 0.03f));
        sphereAt(eyes, -0.13f, 2.04f, -0.339f, 0.045f, 0.05f, 0.025f);
        sphereAt(eyes, 0.13f, 2.04f, -0.339f, 0.045f, 0.05f, 0.025f);
        MeshPartBuilder shoes = builder.part("field-boots", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.12f, 0.13f, 0.12f, 1f, 0.02f));
        boxAt(shoes, -0.18f, 0.075f, -0.10f, 0.32f, 0.15f, 0.43f);
        boxAt(shoes, 0.18f, 0.075f, -0.10f, 0.32f, 0.15f, 0.43f);
        MeshPartBuilder belt = builder.part("belt", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.24f, 0.20f, 0.15f, 1f, 0.02f));
        boxAt(belt, 0f, 0.86f, 0f, 0.49f, 0.08f, 0.36f);
        return builder.end();
    }

    private Model createCarModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder paint = builder.part("4x4-painted-body", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.58f, 0.13f, 0.07f, 1f, 0.12f));
        boxAt(paint, 0f, -0.01f, 0.03f, 1.84f, 0.55f, 3.65f);
        boxAt(paint, 0f, 0.20f, -1.07f, 1.74f, 0.28f, 1.35f);
        boxAt(paint, 0f, 0.19f, 1.29f, 1.68f, 0.26f, 0.85f);
        boxAt(paint, -0.90f, 0.18f, 0.12f, 0.08f, 0.40f, 1.35f);
        boxAt(paint, 0.90f, 0.18f, 0.12f, 0.08f, 0.40f, 1.35f);
        boxAt(paint, -0.70f, 0.26f, -0.39f, 0.10f, 0.06f, 0.42f);
        boxAt(paint, 0.70f, 0.26f, -0.39f, 0.10f, 0.06f, 0.42f);
        boxAt(paint, -0.70f, 0.26f, 0.56f, 0.10f, 0.06f, 0.42f);
        boxAt(paint, 0.70f, 0.26f, 0.56f, 0.10f, 0.06f, 0.42f);
        MeshPartBuilder glass = builder.part("glazing", GL20.GL_TRIANGLES, ATTRIBUTES, vehicleGlassMaterial);
        boxAt(glass, 0f, 0.58f, -0.67f, 1.26f, 0.43f, 0.045f, 20f, 0f, 0f);
        boxAt(glass, 0f, 0.57f, 0.82f, 1.18f, 0.39f, 0.04f, -18f, 0f, 0f);
        boxAt(glass, -0.735f, 0.58f, -0.02f, 0.035f, 0.41f, 0.70f);
        boxAt(glass, 0.735f, 0.58f, -0.02f, 0.035f, 0.41f, 0.70f);
        MeshPartBuilder interior = builder.part("cabin-interior", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.10f, 0.13f, 0.13f, 1f, 0.02f));
        boxAt(interior, 0f, 0.34f, 0.10f, 1.22f, 0.13f, 1.22f);
        MeshPartBuilder trim = builder.part("bumpers-grille-and-wheel-arches", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.075f, 0.09f, 0.09f, 1f, 0.08f));
        boxAt(trim, 0f, -0.19f, -1.89f, 1.90f, 0.18f, 0.16f);
        boxAt(trim, 0f, -0.17f, 1.88f, 1.90f, 0.18f, 0.15f);
        boxAt(trim, 0f, 0.06f, -1.86f, 0.62f, 0.26f, 0.06f);
        for (int sideSign : new int[]{-1, 1}) {
            boxAt(trim, sideSign * 0.94f, -0.19f, -1.20f, 0.08f, 0.28f, 0.90f);
            boxAt(trim, sideSign * 0.94f, -0.19f, 1.17f, 0.08f, 0.28f, 0.90f);
        }
        MeshPartBuilder chrome = builder.part("metal-details", GL20.GL_TRIANGLES, ATTRIBUTES, metalMaterial);
        boxAt(chrome, 0f, -0.16f, -1.985f, 1.35f, 0.07f, 0.035f);
        boxAt(chrome, 0f, -0.12f, 1.965f, 1.15f, 0.045f, 0.035f);
        for (int sideSign : new int[]{-1, 1}) {
            boxAt(chrome, sideSign * 0.94f, 0.55f, -0.62f, 0.20f, 0.15f, 0.18f);
            boxAt(chrome, sideSign * 0.70f, 0.33f, -0.59f, 0.08f, 0.035f, 0.12f);
            boxAt(chrome, sideSign * 0.70f, 0.33f, 0.57f, 0.08f, 0.035f, 0.12f);
            boxAt(chrome, sideSign * 0.94f, 0.25f, 0.00f, 0.035f, 0.08f, 0.025f);
        }
        MeshPartBuilder lamps = builder.part("lamps", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.94f, 0.78f, 0.48f, 1f, 0.18f));
        boxAt(lamps, -0.62f, 0.19f, -1.86f, 0.37f, 0.20f, 0.055f);
        boxAt(lamps, 0.62f, 0.19f, -1.86f, 0.37f, 0.20f, 0.055f);
        MeshPartBuilder tailLamps = builder.part("tail-lamps", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.72f, 0.08f, 0.045f, 1f, 0.12f));
        boxAt(tailLamps, -0.68f, 0.20f, 1.86f, 0.27f, 0.23f, 0.05f);
        boxAt(tailLamps, 0.68f, 0.20f, 1.86f, 0.27f, 0.23f, 0.05f);
        return builder.end();
    }

    private Model createRoofRackModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder rack = builder.part("utility-roof-rack", GL20.GL_TRIANGLES, ATTRIBUTES, metalMaterial);
        boxAt(rack, -0.59f, 0.04f, 0f, 0.055f, 0.08f, 1.85f);
        boxAt(rack, 0.59f, 0.04f, 0f, 0.055f, 0.08f, 1.85f);
        for (float z : new float[]{-0.75f, 0f, 0.75f}) boxAt(rack, 0f, 0.04f, z, 1.22f, 0.07f, 0.07f);
        return builder.end();
    }

    private Model createWheelModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder tire = builder.part("all-terrain-rubber", GL20.GL_TRIANGLES, ATTRIBUTES, wheelMaterial);
        tire.cylinder(0.95f, 0.65f, 0.95f, 14);
        MeshPartBuilder rim = builder.part("alloy-wheel-face", GL20.GL_TRIANGLES, ATTRIBUTES, metalMaterial);
        rim.cylinder(0.61f, 0.68f, 0.61f, 14);
        MeshPartBuilder hub = builder.part("wheel-hub", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.23f, 0.28f, 0.28f, 1f, 0.13f));
        hub.cylinder(0.25f, 0.72f, 0.25f, 12);
        return builder.end();
    }

    private Model createBoatModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder hull = builder.part("deep-v-workboat-hull", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.07f, 0.30f, 0.34f, 1f, 0.08f));
        boxAt(hull, 0f, -0.13f, 0.02f, 1.42f, 0.42f, 3.45f);
        setAt(hull, 0f, -0.12f, -1.65f, 90f, 0f, 0f);
        hull.cone(1.42f, 0.48f, 1.30f, 10);
        MeshPartBuilder keel = builder.part("keel-and-rub-rail", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.035f, 0.10f, 0.13f, 1f, 0.06f));
        boxAt(keel, 0f, -0.36f, 0.15f, 0.28f, 0.15f, 3.4f);
        boxAt(keel, -0.73f, 0.10f, 0.15f, 0.09f, 0.12f, 3.55f);
        boxAt(keel, 0.73f, 0.10f, 0.15f, 0.09f, 0.12f, 3.55f);
        MeshPartBuilder deck = builder.part("non-slip-deck", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.70f, 0.68f, 0.56f, 1f, 0.02f));
        boxAt(deck, 0f, 0.13f, 0.10f, 1.32f, 0.12f, 3.35f);
        boxAt(deck, 0f, 0.18f, -1.48f, 1.16f, 0.12f, 0.84f);
        MeshPartBuilder cabin = builder.part("workboat-console-and-seats", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.82f, 0.79f, 0.67f, 1f, 0.04f));
        boxAt(cabin, 0f, 0.54f, -0.14f, 0.76f, 0.68f, 0.60f);
        boxAt(cabin, -0.43f, 0.36f, 0.88f, 0.50f, 0.18f, 0.66f);
        boxAt(cabin, 0.43f, 0.36f, 0.88f, 0.50f, 0.18f, 0.66f);
        boxAt(cabin, 0f, 0.35f, -1.12f, 1.06f, 0.16f, 0.20f);
        MeshPartBuilder windscreen = builder.part("helm-windscreen", GL20.GL_TRIANGLES, ATTRIBUTES,
                vehicleGlassMaterial);
        boxAt(windscreen, 0f, 0.89f, -0.25f, 0.73f, 0.40f, 0.055f, 14f, 0f, 0f);
        MeshPartBuilder rails = builder.part("stainless-rails", GL20.GL_TRIANGLES, ATTRIBUTES, metalMaterial);
        for (float sideSign : new float[]{-1f, 1f}) {
            cylinderAt(rails, sideSign * 0.66f, 0.43f, -1.10f, 0.055f, 0.75f, 0.055f, 0f, 0f, 0f);
            cylinderAt(rails, sideSign * 0.66f, 0.43f, 1.38f, 0.055f, 0.75f, 0.055f, 0f, 0f, 0f);
            boxAt(rails, sideSign * 0.66f, 0.80f, 0.12f, 0.05f, 0.05f, 2.60f);
        }
        MeshPartBuilder equipment = builder.part("outboard-and-safety-gear", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.86f, 0.31f, 0.08f, 1f, 0.07f));
        boxAt(equipment, 0f, -0.12f, 1.91f, 0.46f, 0.62f, 0.34f);
        sphereAt(equipment, -0.48f, 0.29f, 0.36f, 0.24f, 0.24f, 0.12f);
        sphereAt(equipment, 0.48f, 0.29f, 0.36f, 0.24f, 0.24f, 0.12f);
        return builder.end();
    }

    private Model createHelicopterModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder fuselage = builder.part("utility-helicopter-fuselage", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.16f, 0.37f, 0.23f, 1f, 0.10f));
        sphereAt(fuselage, 0f, 0.01f, 0.18f, 1.64f, 0.92f, 2.18f);
        sphereAt(fuselage, 0f, -0.07f, -1.25f, 1.28f, 0.74f, 1.30f);
        cylinderAt(fuselage, 0f, 0.10f, 2.65f, 0.46f, 3.40f, 0.46f, 90f, 0f, 0f);
        boxAt(fuselage, 0f, 0.10f, 4.30f, 1.25f, 0.16f, 0.63f);
        boxAt(fuselage, 0f, 0.63f, 4.00f, 0.18f, 1.18f, 0.38f);
        cylinderAt(fuselage, 0f, 0.82f, 0.05f, 0.23f, 0.72f, 0.23f, 0f, 0f, 0f);
        MeshPartBuilder cabin = builder.part("cabin-frames-and-skids", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.075f, 0.14f, 0.11f, 1f, 0.06f));
        for (float sideSign : new float[]{-1f, 1f}) {
            cylinderAt(cabin, sideSign * 0.72f, -0.49f, -0.65f, 0.13f, 0.72f, 0.13f, 0f, 0f, 0f);
            cylinderAt(cabin, sideSign * 0.72f, -0.49f, 1.10f, 0.13f, 0.72f, 0.13f, 0f, 0f, 0f);
            cylinderAt(cabin, sideSign * 0.86f, -0.80f, 0.22f, 0.12f, 3.35f, 0.12f, 90f, 0f, 0f);
        }
        MeshPartBuilder tailRotor = builder.part("tail-rotor", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.08f, 0.10f, 0.09f, 1f, 0.04f));
        boxAt(tailRotor, 0.50f, 0.70f, 4.12f, 0.95f, 0.09f, 0.10f, 0f, 0f, 0f);
        boxAt(tailRotor, 0.50f, 0.70f, 4.12f, 0.09f, 0.95f, 0.10f, 0f, 0f, 0f);
        return builder.end();
    }

    private Model createRotorModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder blade = builder.part("spinning-blade", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.08f, 0.10f, 0.10f, 1f, 0.04f));
        blade.box(1f, 1f, 1f);
        return builder.end();
    }

    private Model createAirplaneFuselageModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder paint = builder.part("high-wing-fuselage", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.79f, 0.71f, 0.50f, 1f, 0.10f));
        sphereAt(paint, 0f, 0f, 0.08f, 0.76f, 0.67f, 2.40f);
        sphereAt(paint, 0f, 0.01f, -1.55f, 0.62f, 0.55f, 0.96f);
        boxAt(paint, 0f, 0.05f, -2.18f, 0.53f, 0.45f, 0.46f);
        MeshPartBuilder windows = builder.part("cockpit-and-cabin-windows", GL20.GL_TRIANGLES, ATTRIBUTES,
                vehicleGlassMaterial);
        sphereAt(windows, 0f, 0.43f, -0.52f, 0.48f, 0.31f, 0.69f);
        for (float sideSign : new float[]{-1f, 1f}) {
            for (float z : new float[]{0.18f, 0.82f})
                boxAt(windows, sideSign * 0.63f, 0.24f, z, 0.035f, 0.24f, 0.37f);
        }
        MeshPartBuilder gear = builder.part("tricycle-landing-gear", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.19f, 0.21f, 0.21f, 1f, 0.08f));
        for (float sideSign : new float[]{-1f, 1f}) {
            cylinderAt(gear, sideSign * 1.30f, -0.33f, 0.78f, 0.09f, 0.60f, 0.09f, 0f, 0f, sideSign * -22f);
            sphereAt(gear, sideSign * 1.30f, -0.66f, 0.78f, 0.36f, 0.36f, 0.20f);
        }
        cylinderAt(gear, 0f, -0.30f, -1.45f, 0.08f, 0.44f, 0.08f, 0f, 0f, 0f);
        sphereAt(gear, 0f, -0.50f, -1.48f, 0.25f, 0.25f, 0.17f);
        MeshPartBuilder trim = builder.part("cowl-and-control-details", GL20.GL_TRIANGLES, ATTRIBUTES, metalMaterial);
        boxAt(trim, 0f, -0.09f, -2.32f, 0.64f, 0.12f, 0.10f);
        boxAt(trim, 0f, 0.37f, 1.75f, 0.42f, 0.09f, 0.30f);
        return builder.end();
    }

    private Model createAirplaneWingModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder wing = builder.part("high-wing-and-flaps", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.88f, 0.82f, 0.66f, 1f, 0.10f));
        boxAt(wing, 0f, 0f, 0f, 10.4f, 0.17f, 1.62f);
        boxAt(wing, 0f, -0.015f, 0.59f, 8.1f, 0.07f, 0.34f);
        boxAt(wing, 0f, 0.012f, -0.69f, 9.4f, 0.055f, 0.09f);
        for (float sideSign : new float[]{-1f, 1f}) {
            boxAt(wing, sideSign * 3.80f, 0.02f, 0.02f, 2.10f, 0.13f, 1.16f, 0f, 0f, sideSign * 2.5f);
            sphereAt(wing, sideSign * 5.04f, -0.01f, -0.02f, 0.22f, 0.12f, 0.44f);
        }
        MeshPartBuilder struts = builder.part("lift-struts", GL20.GL_TRIANGLES, ATTRIBUTES, metalMaterial);
        for (float sideSign : new float[]{-1f, 1f})
            cylinderAt(struts, sideSign * 1.34f, -0.24f, -0.03f, 0.07f, 1.12f, 0.07f,
                    0f, 0f, sideSign * 12f);
        return builder.end();
    }

    private Model createAirplaneTailModel() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder tail = builder.part("empennage", GL20.GL_TRIANGLES, ATTRIBUTES,
                material(0.86f, 0.79f, 0.60f, 1f, 0.08f));
        boxAt(tail, 0f, 0f, 0.18f, 3.65f, 0.13f, 0.80f);
        boxAt(tail, 0f, 0.34f, 0.49f, 0.16f, 0.73f, 0.78f);
        boxAt(tail, 0f, -0.035f, 0.43f, 2.66f, 0.07f, 0.18f);
        return builder.end();
    }

    private void setAt(MeshPartBuilder part, float x, float y, float z) {
        setAt(part, x, y, z, 0f, 0f, 0f);
    }

    private void setAt(MeshPartBuilder part, float x, float y, float z, float rotationX, float rotationY, float rotationZ) {
        geometryTransform.idt().translate(x, y, z);
        if (rotationX != 0f) geometryTransform.rotate(Vector3.X, rotationX);
        if (rotationY != 0f) geometryTransform.rotate(Vector3.Y, rotationY);
        if (rotationZ != 0f) geometryTransform.rotate(Vector3.Z, rotationZ);
        part.setVertexTransform(geometryTransform);
    }

    private void boxAt(MeshPartBuilder part, float x, float y, float z,
                      float width, float height, float depth) {
        boxAt(part, x, y, z, width, height, depth, 0f, 0f, 0f);
    }

    private void boxAt(MeshPartBuilder part, float x, float y, float z,
                      float width, float height, float depth, float rotationX, float rotationY, float rotationZ) {
        setAt(part, x, y, z, rotationX, rotationY, rotationZ);
        part.box(width, height, depth);
    }

    private void sphereAt(MeshPartBuilder part, float x, float y, float z,
                         float width, float height, float depth) {
        setAt(part, x, y, z);
        part.sphere(width, height, depth, 14, 10);
    }

    private void cylinderAt(MeshPartBuilder part, float x, float y, float z,
                            float width, float height, float depth,
                            float rotationX, float rotationY, float rotationZ) {
        setAt(part, x, y, z, rotationX, rotationY, rotationZ);
        part.cylinder(width, height, depth, 10);
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
        // Vehicles are world objects, not equipment that materializes only after boarding.
        // Draw every parked craft in every mode so the player can see and walk up to them.
        drawParts(humanParts, mode == PhysicsWorld.WALK);
        drawParts(carParts, true);
        drawParts(boatParts, true);
        drawParts(helicopterParts, true);
        drawParts(airplaneParts, true);
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
