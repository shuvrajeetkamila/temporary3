package ai.nauru.countryside.world;

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
        assertFalse(world.sha256.isEmpty());
    }
}
