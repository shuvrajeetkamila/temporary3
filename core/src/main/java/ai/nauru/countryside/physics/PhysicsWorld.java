package ai.nauru.countryside.physics;

import ai.nauru.countryside.input.VehicleControls;
import ai.nauru.countryside.world.WorldData;
import ai.nauru.countryside.world.WorldTransform;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.ClosestRayResultCallback;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.badlogic.gdx.physics.bullet.collision.btBvhTriangleMeshShape;
import com.badlogic.gdx.physics.bullet.collision.btCapsuleShape;
import com.badlogic.gdx.physics.bullet.collision.btCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.collision.btCollisionDispatcher;
import com.badlogic.gdx.physics.bullet.collision.btCollisionObject;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.collision.btDbvtBroadphase;
import com.badlogic.gdx.physics.bullet.collision.btDefaultCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.collision.btTriangleMesh;
import com.badlogic.gdx.physics.bullet.dynamics.btDiscreteDynamicsWorld;
import com.badlogic.gdx.physics.bullet.dynamics.btSequentialImpulseConstraintSolver;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/** Bullet-backed contact world plus force-based local vehicle models. */
public final class PhysicsWorld implements Disposable {
    public static final int WALK = 0, CAR = 1, BOAT = 2, HELICOPTER = 3, AIRPLANE = 4;
    private static final int DISABLE_DEACTIVATION = 4;
    private static final int CF_KINEMATIC_OBJECT = 2;
    private static final int CF_NO_CONTACT_RESPONSE = 4;
    private static final float DT = FixedStepClock.FIXED_DT;
    private static final float[] GEAR_RATIOS = {0f, 3.35f, 1.92f, 1.27f, 0.93f};
    private static final float[][] BOAT_SAMPLES = {{-0.58f,-0.42f,-1.65f},{-0.65f,-0.42f,-0.55f},{0.65f,-0.42f,-0.55f},{0.58f,-0.42f,-1.65f},
            {-0.58f,-0.42f,1.65f},{-0.65f,-0.42f,0.55f},{0.65f,-0.42f,0.55f},{0.58f,-0.42f,1.65f}};
    private static final float[][] CAR_WHEEL_OFFSETS = {{-0.79f,-0.25f,-1.275f},{0.79f,-0.25f,-1.275f},
            {-0.79f,-0.25f,1.275f},{0.79f,-0.25f,1.275f}};
    private static final float[][] PLANE_GEAR_OFFSETS = {{0f,-0.12f,-1.65f},{-1.55f,-0.12f,0.75f},{1.55f,-0.12f,0.75f}};

    private final WorldData data;
    private final btDefaultCollisionConfiguration collisionConfig;
    private final btCollisionDispatcher dispatcher;
    private final btDbvtBroadphase broadphase;
    private final btSequentialImpulseConstraintSolver solver;
    private final btDiscreteDynamicsWorld world;
    private final btTriangleMesh terrainMesh;
    private final btBvhTriangleMeshShape terrainShape;
    private final Array<btCollisionShape> shapes = new Array<>();
    private final Array<btRigidBody> staticBodies = new Array<>();
    public final Body character;
    public final Body car;
    public final Body boat;
    public final Body helicopter;
    public final Body airplane;
    private final Body[] vehicles = new Body[5];
    private final Vector3 v = new Vector3();
    private final Vector3 p = new Vector3();
    private final Vector3 forward = new Vector3();
    private final Vector3 right = new Vector3();
    private final Vector3 up = new Vector3();
    private final Vector3 force = new Vector3();
    private final Vector3 torque = new Vector3();
    private final Vector3 liftDir = new Vector3();
    private final Vector3 temp = new Vector3();
    private final Vector3 temp2 = new Vector3();
    private final Vector3 angular = new Vector3();
    private final Vector3 contactVelocity = new Vector3();
    private final Vector3 contactPoint = new Vector3();
    private final Vector3 contactNormal = new Vector3();
    private final ClosestRayResultCallback[] groundRays = new ClosestRayResultCallback[4];
    private final Vector3[] rayFrom = {new Vector3(), new Vector3(), new Vector3(), new Vector3()};
    private final Vector3[] rayTo = {new Vector3(), new Vector3(), new Vector3(), new Vector3()};
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 newTransform = new Matrix4();
    private final Quaternion rotation = new Quaternion();
    private final AirplaneForceModel.Moments aircraftMoments = new AirplaneForceModel.Moments(0f, 0f, 0f);
    private float simulationTime;
    private float carRpm = 900f;
    private int carGear = 1;
    private float rotorRpm;
    private float aircraftRpm;
    private boolean characterBoarded;
    private float waterImpactEnergy;

    public PhysicsWorld(WorldData data) {
        this.data = data;
        Bullet.init();
        collisionConfig = new btDefaultCollisionConfiguration();
        dispatcher = new btCollisionDispatcher(collisionConfig);
        broadphase = new btDbvtBroadphase();
        solver = new btSequentialImpulseConstraintSolver();
        world = new btDiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);
        for (int i = 0; i < groundRays.length; i++) {
            groundRays[i] = new ClosestRayResultCallback(Vector3.Zero, new Vector3(0f, -1f, 0f));
            groundRays[i].setCollisionFilterGroup(1);
            groundRays[i].setCollisionFilterMask(1);
        }
        world.setGravity(new Vector3(0f, -9.81f, 0f));
        terrainMesh = createTerrainMesh(data);
        terrainShape = new btBvhTriangleMeshShape(terrainMesh, true);
        shapes.add(terrainShape);
        addStaticBody(terrainShape, 0f, 0f, 0f);
        addBuildingCollision();

        WorldTransform map = data.transform;
        Vector3 walkSpawn = map.mapSpawn(data.spawn(1), new Vector3()).add(0f, 0.95f, 0f);
        character = createBody("walker", new btCapsuleShape(0.36f, 1.15f), 72f,
                walkSpawn, data.spawn(1).yaw, 0.88f, 0.92f);
        character.rigidBody.setAngularFactor(0f);
        car = createBody("island-4x4", new btBoxShape(new Vector3(0.96f, 0.68f, 1.95f)),
                CarForceModel.MASS_KG, map.mapSpawn(data.spawn(2), new Vector3()).add(0f, 0.88f, 0f),
                data.spawn(2).yaw, 0.22f, 0.08f);
        boat = createBody("lagoon-workboat", new btBoxShape(new Vector3(0.82f, 0.48f, 2.05f)),
                BoatForceModel.HULL_MASS_KG, map.mapSpawn(data.spawn(3), new Vector3()),
                data.spawn(3).yaw, 0.08f, 0.12f);
        Vector3 helicopterSpawn = map.mapSpawn(data.spawn(4), new Vector3()).add(0f, 0.9f, 0f);
        helicopter = createBody("utility-helicopter", new btBoxShape(new Vector3(0.82f, 0.82f, 3.1f)),
                HelicopterForceModel.MASS_KG, helicopterSpawn,
                data.spawn(4).yaw, 0.07f, 0.16f);
        airplane = createBody("stol-aircraft", new btBoxShape(new Vector3(0.60f, 0.50f, 3.8f)),
                AirplaneForceModel.MASS_KG, map.mapSpawn(data.spawn(5), new Vector3()),
                data.spawn(5).yaw, 0.025f, 0.09f);
        vehicles[CAR] = car;
        vehicles[BOAT] = boat;
        vehicles[HELICOPTER] = helicopter;
        vehicles[AIRPLANE] = airplane;
    }

    private btTriangleMesh createTerrainMesh(WorldData worldData) {
        btTriangleMesh mesh = new btTriangleMesh();
        int side = worldData.gridWidth;
        Vector3[] points = new Vector3[side * side];
        for (int row = 0; row < side; row++) {
            float realZ = worldData.gridMinZ + row * worldData.gridStepZ;
            for (int col = 0; col < side; col++) {
                int index = row * side + col;
                float realX = worldData.gridMinX + col * worldData.gridStepX;
                float elevation = worldData.elevations[index];
                points[index] = worldData.transform.map(realX, elevation, realZ, new Vector3());
            }
        }
        for (int row = 0; row < side - 1; row++) {
            for (int col = 0; col < side - 1; col++) {
                int i00 = row * side + col, i10 = i00 + 1, i01 = i00 + side, i11 = i01 + 1;
                boolean l00 = worldData.landMask[i00] != 0;
                boolean l10 = worldData.landMask[i10] != 0;
                boolean l01 = worldData.landMask[i01] != 0;
                boolean l11 = worldData.landMask[i11] != 0;
                if (l00 && l01 && l10) mesh.addTriangle(points[i00], points[i01], points[i10], true);
                if (l10 && l01 && l11) mesh.addTriangle(points[i10], points[i01], points[i11], true);
            }
        }
        for (Vector3 point : points) point.setZero();
        return mesh;
    }

    private void addBuildingCollision() {
        for (WorldData.Feature feature : data.features) {
            if (feature.type != WorldData.BUILDING || feature.points.length < 6) continue;
            float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            float sumX = 0f, sumZ = 0f;
            for (int i = 0; i < feature.points.length; i += 2) {
                float x = feature.points[i], z = feature.points[i + 1];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                sumX += x; sumZ += z;
            }
            float anchorX = sumX / (feature.points.length / 2f), anchorZ = sumZ / (feature.points.length / 2f);
            float width = clamp(maxX - minX, 3f, 18f), depth = clamp(maxZ - minZ, 3f, 18f);
            Vector3 center = data.transform.mapFootprint(anchorX, data.sampleElevation(anchorX, anchorZ), anchorZ,
                    anchorX, anchorZ, new Vector3());
            float height = 3.4f + Math.abs(feature.osmId % 5) * 0.65f;
            btBoxShape shape = new btBoxShape(new Vector3(width * 0.5f, height * 0.5f, depth * 0.5f));
            shapes.add(shape);
            addStaticBody(shape, center.x, center.y + height * 0.5f, center.z);
        }
    }

    private void addStaticBody(btCollisionShape shape, float x, float y, float z) {
        btRigidBody body = new btRigidBody(0f, null, shape, new Vector3());
        body.setWorldTransform(new Matrix4().setToTranslation(x, y, z));
        body.setFriction(0.88f);
        world.addRigidBody(body, 1, -1);
        staticBodies.add(body);
    }

    private Body createBody(String id, btCollisionShape shape, float mass, Vector3 position,
                            float yawRad, float linearDamping, float angularDamping) {
        shapes.add(shape);
        Vector3 inertia = new Vector3();
        shape.calculateLocalInertia(mass, inertia);
        btRigidBody rigid = new btRigidBody(mass, null, shape, inertia);
        Matrix4 initial = new Matrix4().setToTranslation(position).rotate(Vector3.Y, (float) Math.toDegrees(yawRad));
        rigid.setWorldTransform(initial);
        rigid.setInterpolationWorldTransform(initial);
        rigid.setDamping(linearDamping, angularDamping);
        rigid.setFriction(0.66f);
        rigid.setRestitution(0.04f);
        rigid.setActivationState(DISABLE_DEACTIVATION);
        world.addRigidBody(rigid, 2, -1);
        return new Body(id, rigid, mass, shape, position.cpy(), yawRad);
    }

    public Body bodyForMode(int mode) {
        return mode == WALK ? character : (mode >= CAR && mode <= AIRPLANE ? vehicles[mode] : character);
    }

    public Vector3 position(Body body, Vector3 out) {
        body.rigidBody.getWorldTransform(transform);
        return transform.getTranslation(out);
    }

    public float yaw(Body body) {
        body.rigidBody.getWorldTransform(transform);
        transform.getRotation(rotation);
        return rotation.getYawRad();
    }

    public float speedMps(Body body) {
        return body.rigidBody.getLinearVelocity().len();
    }

    public float verticalSpeedMps(Body body) {
        return body.rigidBody.getLinearVelocity().y;
    }

    public float carRpm() { return carRpm; }
    public int carGear() { return carGear; }
    public float rotorRpm() { return rotorRpm; }
    public float aircraftRpm() { return aircraftRpm; }
    public float waterImpactEnergy() { return waterImpactEnergy; }

    public void setCharacterBoarded(boolean boarded) {
        if (characterBoarded == boarded) return;
        characterBoarded = boarded;
        int flags = character.rigidBody.getCollisionFlags();
        if (boarded) {
            character.rigidBody.setCollisionFlags(flags | CF_KINEMATIC_OBJECT | CF_NO_CONTACT_RESPONSE);
            character.rigidBody.setActivationState(DISABLE_DEACTIVATION);
            character.rigidBody.setLinearVelocity(Vector3.Zero);
            character.rigidBody.setAngularVelocity(Vector3.Zero);
        } else {
            character.rigidBody.setCollisionFlags(flags & ~(CF_KINEMATIC_OBJECT | CF_NO_CONTACT_RESPONSE));
            character.rigidBody.setActivationState(1);
        }
    }

    public boolean isCharacterBoarded() { return characterBoarded; }

    public void setBodyTransform(Body body, Vector3 position, float yawRad) {
        newTransform.setToTranslation(position).rotate(Vector3.Y, (float) Math.toDegrees(yawRad));
        body.rigidBody.setWorldTransform(newTransform);
        body.rigidBody.setInterpolationWorldTransform(newTransform);
        body.rigidBody.setLinearVelocity(Vector3.Zero);
        body.rigidBody.setAngularVelocity(Vector3.Zero);
        body.rigidBody.clearForces();
        body.rigidBody.activate(true);
        world.updateSingleAabb(body.rigidBody);
    }

    public void recover(Body body, int spawnType) {
        WorldData.Spawn spawn = data.spawn(spawnType);
        Vector3 p = data.transform.mapSpawn(spawn, new Vector3());
        float offsetY = spawnType == 1 ? 0.95f : spawnType == CAR ? 0.88f
                : spawnType == AIRPLANE ? 0.8f : spawnType == HELICOPTER ? 0.9f : 0f;
        p.y += offsetY;
        if (spawnType == BOAT) p.y = Math.max(0.7f, p.y);
        setBodyTransform(body, p, spawn.yaw);
    }

    public boolean nearBody(Body a, Body b, float radius) {
        position(a, p);
        position(b, temp);
        return p.dst2(temp) <= radius * radius;
    }

    public void step(VehicleControls controls, int mode, float timeSeconds) {
        simulationTime = timeSeconds;
        if (mode == WALK && !characterBoarded) applyCharacter(controls);
        else if (mode == CAR) applyCar(controls);
        else if (mode == BOAT) applyBoat(controls);
        else if (mode == HELICOPTER) applyHelicopter(controls);
        else if (mode == AIRPLANE) applyAirplane(controls);
        world.stepSimulation(DT, 0, DT);
    }

    private void applyCharacter(VehicleControls input) {
        btRigidBody body = character.rigidBody;
        Vector3 velocity = body.getLinearVelocity();
        // The game camera supplies a camera-relative movement vector in world coordinates.
        float desiredX = input.moveX * 4.2f;
        float desiredZ = input.moveY * 4.2f;
        float forceX = (desiredX - velocity.x) * character.mass * 5.8f;
        float forceZ = (desiredZ - velocity.z) * character.mass * 5.8f;
        body.applyCentralForce(force.set(forceX, 0f, forceZ));
        body.applyCentralForce(temp.set(-velocity.x * character.mass * 1.15f, 0f,
                -velocity.z * character.mass * 1.15f));
        if (input.moveX * input.moveX + input.moveY * input.moveY > 0.035f) {
            character.lastYaw = (float) Math.atan2(-input.moveX, -input.moveY);
            body.getWorldTransform(transform);
            transform.getTranslation(p);
            newTransform.setToTranslation(p).rotate(Vector3.Y, (float) Math.toDegrees(character.lastYaw));
            body.setWorldTransform(newTransform);
            body.setInterpolationWorldTransform(newTransform);
            world.updateSingleAabb(body);
        }
        if (input.jumpPressed && Math.abs(velocity.y) < 0.35f) {
            // v = sqrt(2 g h), with a documented 1.1 m hop target.
            float jumpVelocity = (float) Math.sqrt(2f * 9.81f * 1.1f);
            body.applyCentralImpulse(temp.set(0f, character.mass * jumpVelocity, 0f));
        }
    }

    private void applyCar(VehicleControls input) {
        btRigidBody body = car.rigidBody;
        float supportedLoad = applyGroundSprings(body, CAR_WHEEL_OFFSETS, 4, 0.75f,
                CarForceModel.SPRING_N_PER_M, CarForceModel.DAMPER_NS_PER_M,
                CarForceModel.MAX_WHEEL_LOAD_N, 1.25f);
        getBasis(body, forward, right, up, v);
        float speed = body.getLinearVelocity().dot(forward);
        float absolute = Math.abs(speed);
        if (absolute < 6f) carGear = 1;
        else if (absolute < 13f) carGear = 2;
        else if (absolute < 22f) carGear = 3;
        else carGear = 4;
        carRpm = CarForceModel.clamp(900f + absolute * 135f + input.throttle * 1_450f, 850f, 5_800f);
        float torqueN = CarForceModel.engineTorque(carRpm, input.throttle) * GEAR_RATIOS[carGear] * 3.9f * 0.84f / 0.34f;
        if (input.throttle < 0f) torqueN = input.throttle * 4_200f;
        float brake = Math.max(input.brake, input.handbrake * 0.84f);
        float brakeN = brake * (input.handbrake > 0.1f ? 5_800f : 8_000f) * Math.signum(speed);
        float drag = CarForceModel.dragNewtons(speed, 1.05f) + speed * 105f;
        float requestedLong = torqueN - brakeN - drag;
        float sideSpeed = body.getLinearVelocity().dot(right);
        float requestedSide = -sideSpeed * CarForceModel.MASS_KG * 3.2f;
        float mu = input.handbrake > 0.2f ? 0.56f : 0.88f;
        float limit = Math.max(0f, supportedLoad) * mu;
        float mag = (float) Math.sqrt(requestedLong * requestedLong + requestedSide * requestedSide);
        if (mag > limit) { requestedLong *= limit / mag; requestedSide *= limit / mag; }
        body.applyCentralForce(force.set(forward).scl(requestedLong).mulAdd(right, requestedSide));
        float steeringSpeed = Math.min(1f, absolute / 3f);
        body.applyTorque(torque.set(0f, input.steer * 2_800f * steeringSpeed * (speed >= 0f ? 1f : -1f)
                - body.getAngularVelocity().y * 520f, 0f));
    }

    /** Four Bullet ray springs provide SI wheel loads and add the matching suspension moment to the chassis. */
    private float applyGroundSprings(btRigidBody body, float[][] hardpoints, int count, float restLength,
                                    float springRate, float damperRate, float maxWheelLoad, float rayLength) {
        body.getWorldTransform(transform);
        Vector3 center = transform.getTranslation(p);
        v.set(body.getLinearVelocity());
        angular.set(body.getAngularVelocity());
        float totalLoad = 0f;
        for (int i = 0; i < count; i++) {
            rayFrom[i].set(hardpoints[i][0], hardpoints[i][1], hardpoints[i][2]).rot(transform).add(center);
            rayTo[i].set(rayFrom[i]).add(0f, -rayLength, 0f);
            ClosestRayResultCallback callback = groundRays[i];
            callback.setRayFromWorld(rayFrom[i]);
            callback.setRayToWorld(rayTo[i]);
            callback.setClosestHitFraction(1f);
            callback.setCollisionObject(null);
            world.rayTest(rayFrom[i], rayTo[i], callback);
            if (!callback.hasHit()) continue;
            callback.getHitPointWorld(contactPoint);
            callback.getHitNormalWorld(contactNormal);
            if (contactNormal.y < 0f) contactNormal.scl(-1f);
            if (contactNormal.y < 0.25f) continue;
            float distance = rayFrom[i].dst(contactPoint);
            float compression = restLength - distance;
            temp.set(contactPoint).sub(center);
            temp2.set(angular).crs(temp);
            contactVelocity.set(v).add(temp2);
            float normalVelocity = contactVelocity.dot(contactNormal);
            float load = CarForceModel.suspensionLoad(compression, normalVelocity,
                    springRate, damperRate, maxWheelLoad);
            if (load <= 0f) continue;
            force.set(contactNormal).scl(load);
            body.applyForce(force, temp);
            totalLoad += load * contactNormal.y;
        }
        return totalLoad;
    }

    private void applyBoat(VehicleControls input) {
        btRigidBody body = boat.rigidBody;
        getBasis(body, forward, right, up, v);
        Vector3 velocity = body.getLinearVelocity();
        waterImpactEnergy = velocity.y < 0f
                ? 0.5f * BoatForceModel.HULL_MASS_KG * velocity.y * velocity.y : 0f;
        float verticalForce = -velocity.y * 360f;
        torque.setZero();
        force.set(0f, verticalForce, 0f);
        // Eight distributed sample points share the same analytic wave model as the water visuals.
        body.getWorldTransform(transform);
        Vector3 center = transform.getTranslation(p);
        for (float[] sample : BOAT_SAMPLES) {
            temp.set(sample[0], sample[1], sample[2]).rot(transform);
            float x = center.x + temp.x, z = center.z + temp.z;
            float pointY = center.y + temp.y;
            float wave = BoatForceModel.waveHeight(x, z, simulationTime);
            float fraction = clamp((wave - pointY + 0.34f) / 0.72f, 0f, 1f);
            float buoyancy = BoatForceModel.displacedBuoyancy(fraction, 0.54f / BOAT_SAMPLES.length);
            force.y += buoyancy;
            torque.x -= temp.z * buoyancy;
            torque.z += temp.x * buoyancy;
        }
        body.applyCentralForce(force);
        body.applyTorque(torque);
        float speed = velocity.dot(forward);
        float sideSpeed = velocity.dot(right);
        float thrust = input.throttle * 850f - 18f * speed * Math.abs(speed);
        float planing = BoatForceModel.planingLift(Math.abs(speed), 0.24f,
                clamp(Math.abs(speed) / 15f * 0.10f, 0f, 0.10f));
        body.applyCentralForce(force.set(up).scl(planing));
        float lateral = -sideSpeed * 520f;
        body.applyCentralForce(force.set(forward).scl(thrust).mulAdd(right, lateral));
        float steerAuthority = clamp(Math.abs(speed) / 2.8f, 0.15f, 1f);
        body.applyTorque(torque.set(0f, input.steer * 1_150f * steerAuthority - body.getAngularVelocity().y * 180f, 0f));
    }

    private void applyHelicopter(VehicleControls input) {
        btRigidBody body = helicopter.rigidBody;
        getBasis(body, forward, right, up, v);
        rotorRpm = HelicopterForceModel.spool(rotorRpm, input.engineOn ? 1f : 0f, DT);
        float speedHorizontal = (float) Math.sqrt(v.x * v.x + v.z * v.z);
        float descent = v.y < 0f ? -v.y : 0f;
        float inflow = 1f + Math.min(0.16f, speedHorizontal * 0.012f)
                - Math.min(0.24f, descent * speedHorizontal * 0.004f);
        body.getWorldTransform(transform);
        float height = transform.getTranslation(p).y - helicopter.spawnPosition.y;
        inflow *= HelicopterForceModel.groundEffect(height);
        float collective = clamp(input.collective, 0f, 1f);
        if (input.engineOn && input.collective <= 0f) collective = 0.58f;
        float thrust = HelicopterForceModel.thrust(collective, rotorRpm, inflow);
        body.applyCentralForce(force.set(up).scl(thrust));
        body.applyCentralForce(temp.set(v).scl(-42f));
        body.applyTorque(torque.set(right).scl(input.cyclicPitch * 3_100f)
                .mulAdd(forward, -input.cyclicRoll * 3_100f)
                .add(0f, HelicopterForceModel.reactionTorque(collective, input.yaw)
                        - body.getAngularVelocity().y * 360f, 0f));
        if (!input.engineOn && rotorRpm > 0.05f) {
            float autorotationLift = rotorRpm * Math.min(5_000f, descent * 420f);
            body.applyCentralForce(temp.set(up).scl(autorotationLift));
        }
    }

    private void applyAirplane(VehicleControls input) {
        btRigidBody body = airplane.rigidBody;
        applyGroundSprings(body, PLANE_GEAR_OFFSETS, 3, 0.82f, 12_500f, 1_750f, 6_000f, 1.45f);
        getBasis(body, forward, right, up, v);
        Vector3 velocity = body.getLinearVelocity();
        float airspeed = velocity.len();
        aircraftRpm += ((input.engineOn ? input.throttle : 0f) - aircraftRpm) * Math.min(1f, DT * 1.5f);
        float forwardSpeed = velocity.dot(forward);
        float alpha = (float) Math.atan2(-velocity.dot(up), Math.max(0.05f, Math.abs(forwardSpeed)));
        float cl = AirplaneForceModel.liftCoefficient(alpha, input.flaps);
        float liftN = AirplaneForceModel.liftNewtons(airspeed, alpha, input.flaps);
        // Lift points perpendicular to the incoming air, not directly upward when banked.
        liftDir.set(up).mulAdd(velocity, -up.dot(velocity) / Math.max(airspeed * airspeed, 0.01f));
        if (liftDir.len2() > 0.01f) liftDir.nor(); else liftDir.set(up);
        body.applyCentralForce(force.set(liftDir).scl(liftN));
        float dragN = AirplaneForceModel.dragNewtons(airspeed, alpha, input.flaps);
        body.applyCentralForce(temp.set(velocity).scl(-dragN / Math.max(airspeed, 0.1f)));
        body.applyCentralForce(temp.set(forward).scl(AirplaneForceModel.propellerThrust(input.throttle, aircraftRpm, airspeed)));
        float q = AirplaneForceModel.dynamicPressure(airspeed);
        AirplaneForceModel.Moments moments = AirplaneForceModel.controlMoments(input.cyclicRoll,
                input.cyclicPitch, input.yaw, q, aircraftMoments);
        body.applyTorque(torque.set(forward).scl(moments.rollNm)
                .mulAdd(right, moments.pitchNm).mulAdd(up, moments.yawNm));
        body.applyTorque(temp.set(body.getAngularVelocity()).scl(-120f));
    }

    private void getBasis(btRigidBody body, Vector3 outForward, Vector3 outRight, Vector3 outUp, Vector3 outVelocity) {
        body.getWorldTransform(transform);
        transform.getRotation(rotation);
        outForward.set(0f, 0f, -1f).rot(rotation).nor();
        outRight.set(1f, 0f, 0f).rot(rotation).nor();
        outUp.set(0f, 1f, 0f).rot(rotation).nor();
        outVelocity.set(body.getLinearVelocity());
    }

    private static float clamp(float x, float low, float high) { return Math.max(low, Math.min(high, x)); }

    @Override public void dispose() {
        for (Body body : new Body[]{character, car, boat, helicopter, airplane}) {
            if (body != null) {
                world.removeRigidBody(body.rigidBody);
                body.rigidBody.dispose();
            }
        }
        for (btRigidBody body : staticBodies) {
            world.removeRigidBody(body);
            body.dispose();
        }
        for (btCollisionShape shape : shapes) shape.dispose();
        for (ClosestRayResultCallback callback : groundRays) if (callback != null) callback.dispose();
        terrainMesh.dispose();
        world.dispose();
        solver.dispose();
        broadphase.dispose();
        dispatcher.dispose();
        collisionConfig.dispose();
    }

    public static final class Body {
        public final String id;
        public final btRigidBody rigidBody;
        public final float mass;
        public final btCollisionShape shape;
        public final Vector3 spawnPosition;
        public float lastYaw;
        final float initialYaw;
        Body(String id, btRigidBody rigidBody, float mass, btCollisionShape shape, Vector3 spawnPosition, float yaw) {
            this.id = id; this.rigidBody = rigidBody; this.mass = mass; this.shape = shape;
            this.spawnPosition = spawnPosition; this.initialYaw = yaw; this.lastYaw = yaw;
        }
    }
}
