package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.LRAConstants;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class NestedStartLraIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(NestedStartLraIT.class);

    @Test
    void testStartNestedLraDuplicates() {
        log.info("NestedStartLraIT: testStartNestedLraDuplicates");
        injectResetAll();

        URI parent = startTopLra("start-nested-dup");
        enableFailurePoint(nextRoutedCoordinator(), InjectPoint.START.name());

        URI nested = lraClient.startLRA(
                parent,
                participantClientId("start-nested-dup") + "-nested-" + System.nanoTime(),
                30L,
                ChronoUnit.SECONDS,
                true);
        lrasToAfterFinish.add(nested);

        assertNotEquals(parent, nested,
                "Nested LRA must be distinct from its parent");

        List<String> uids = uniqueUids(getAllActiveIdsAcrossCoordinators());
        log.info("Cluster-wide unique active uids after nested-start with crash: {}", uids);
        assertTrue(uids.contains(LRAConstants.getLRAUid(parent)),
                "Parent LRA uid must be active after crash recovery, uids=" + uids);
        assertTrue(uids.contains(LRAConstants.getLRAUid(nested)),
                "Nested LRA uid must be active exactly once after crash, uids=" + uids);
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

    private static List<String> uniqueUids(List<String> uris) {
        return uris.stream()
                .map(s -> LRAConstants.getLRAUid(URI.create(s)))
                .distinct()
                .collect(Collectors.toList());
    }
}
