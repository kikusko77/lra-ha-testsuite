package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.LRAConstants;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Confirms that starting a nested transaction across the cluster never leaves duplicate
 * records, even when the coordinator crashes during the start handshake.
 */
@QuarkusTest
class NestedStartLraIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = Logger.getLogger(NestedStartLraIT.class);

    @Test
    void testStartNestedLraDuplicates() {
        log.info("NestedStartLraIT: testStartNestedLraDuplicates");
        injectResetAll();

        URI parent = startTopLra("start-nested-dup");
        enableFailurePoint(nextRoutedCoordinator(), FailurePoint.START);

        URI nested = lraClient.startLRA(
                parent,
                participantClientId("start-nested-dup") + "-nested-" + System.nanoTime(),
                30L,
                ChronoUnit.SECONDS,
                true);
        lrasToAfterFinish.add(nested);

        assertNotEquals(parent, nested,
                "Nested LRA must be distinct from its parent");

        List<String> uids = getAllActiveIdsAcrossCoordinators().stream()
                .map(s -> LRAConstants.getLRAUid(URI.create(s)))
                .collect(Collectors.toList());
        log.infof("Cluster-wide raw active uids after nested-start with crash: %s", uids);

        String parentUid = LRAConstants.getLRAUid(parent);
        String nestedUid = LRAConstants.getLRAUid(nested);
        assertEquals(1, uids.stream().filter(parentUid::equals).count(),
                "Parent LRA uid must be active exactly once after crash recovery, uids=" + uids);
        assertEquals(1, uids.stream().filter(nestedUid::equals).count(),
                "Nested LRA uid must be active exactly once after crash recovery, uids=" + uids);
    }

    @Test
    void testStartNestedLra_happyPath() {
        log.info("NestedStartLraIT: testStartNestedLra_happyPath");
        injectResetAll();

        URI parent = startTopLra("start-nested-happy");
        URI nested = startNestedLra(parent, "start-nested-happy");

        assertNotEquals(parent, nested);
        List<String> uids = uniqueUids(getAllActiveIdsAcrossCoordinators());
        assertTrue(uids.contains(LRAConstants.getLRAUid(parent)),
                "Parent LRA must be active across the cluster, uids=" + uids);
        assertTrue(uids.contains(LRAConstants.getLRAUid(nested)),
                "Nested LRA must be active across the cluster, uids=" + uids);
    }
}
