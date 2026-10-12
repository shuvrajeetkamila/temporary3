package ai.nauru.countryside.world;

import com.badlogic.gdx.math.Vector3;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorldTransformTest {
    @Test public void compressesGlobalTravelOneToTwentyFive() {
        WorldTransform transform = new WorldTransform(0, 0, 1, 0, 2_000, 0.39f, 250, 1_000, 0.04f);
        Vector3 result = transform.map(2_000, 12, 1_500, new Vector3());
        assertEquals(80f, result.x, 1e-3f);
        assertEquals(12f, result.y, 1e-3f);
        assertEquals(60f, result.z, 1e-3f);
    }

    @Test public void runwayHeroZoneIsBetweenSixHundredAndNineHundredMetres() {
        WorldTransform transform = new WorldTransform(30, -10, 0.8f, 0.6f, 2_000, 0.39f, 250, 1_000, 0.04f);
        Vector3 start = transform.map(30 - 1_000 * 0.8f, 0, -10 - 1_000 * 0.6f, new Vector3());
        Vector3 end = transform.map(30 + 1_000 * 0.8f, 0, -10 + 1_000 * 0.6f, new Vector3());
        assertEquals(780f, end.dst(start), 0.1f);
        assertTrue(transform.transformedRunwayLength() >= 600f);
        assertTrue(transform.transformedRunwayLength() <= 900f);
    }

    @Test public void airportFalloffDoesNotJumpAtOuterEdge() {
        WorldTransform transform = new WorldTransform(0, 0, 1, 0, 2_000, 0.39f, 250, 1_000, 0.04f);
        Vector3 a = transform.map(400, 0, 999.9f, new Vector3());
        Vector3 b = transform.map(400, 0, 1_000f, new Vector3());
        Vector3 c = transform.map(400, 0, 1_000.1f, new Vector3());
        assertTrue(a.dst(b) < 0.02f);
        assertTrue(b.dst(c) < 0.02f);
    }

    @Test public void buildingFootprintStaysHumanScale() {
        WorldTransform transform = new WorldTransform(0, 0, 1, 0, 2_000, 0.39f, 250, 1_000, 0.04f);
        Vector3 anchor = transform.map(100, 0, 0, new Vector3());
        Vector3 corner = transform.mapFootprint(104, 0, 3, 100, 0, new Vector3());
        assertEquals(anchor.x + 4f, corner.x, 1e-4f);
        assertEquals(anchor.z + 3f, corner.z, 1e-4f);
    }
}
