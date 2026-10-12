package ai.nauru.countryside.world;

import com.badlogic.gdx.math.Vector3;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Loads the exact binary copied into android/assets/compiled before APK assembly. */
public class PackagedWorldStartupTest {
    @Test public void generatedOfflineWorldParsesAndHasRequiredSpawnSet() throws Exception {
        File asset = new File("../android/assets/compiled/world.bin");
        assertTrue("CI geodata compiler must create the packaged world asset", asset.isFile());
        WorldData world = WorldData.read(Files.readAllBytes(asset.toPath()));
        assertEquals(780f, world.transformedRunwayLength, 1.0f);
        assertTrue(world.coastlineXZ.length >= 8);
        assertTrue(world.elevations.length == world.gridWidth * world.gridWidth);
        assertTrue(world.features.size() >= 10);
        assertNotNull(world.spawn(1));
        assertNotNull(world.spawn(2));
        assertNotNull(world.spawn(3));
        assertNotNull(world.spawn(4));
        assertNotNull(world.spawn(5));
        assertTrue("Boat spawn should begin close to water level", world.spawn(3).height < 5f);
        float[] hullRadii = {0f, 0.95f, 2.6f, 2.4f, 3.4f, 5.5f};
        Vector3[] starts = new Vector3[6];
        for (int type = 1; type <= 5; type++)
            starts[type] = world.transform.mapSpawn(world.spawn(type), new Vector3());
        assertEquals("walker must start close enough to board the 4x4", 5.0f,
                horizontalDistance(starts[1], starts[2]), 0.05f);
        for (int a = 2; a <= 5; a++) {
            for (int b = a + 1; b <= 5; b++) {
                float minimum = hullRadii[a] + hullRadii[b] + 0.5f;
                assertTrue("parked spawns " + a + " and " + b + " overlap their clearance envelopes",
                        horizontalDistance(starts[a], starts[b]) >= minimum);
            }
        }
        assertFalse(world.sha256.isEmpty());
    }

    private static float horizontalDistance(Vector3 a, Vector3 b) {
        float dx = a.x - b.x, dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }
}
