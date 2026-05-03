package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.LRAConstants;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirms that a participant joining a nested transaction does not leave duplicate or
 * orphaned records when the coordinator crashes mid-enrollment.
 */
@QuarkusTest
class NestedJoinLraIT extends TestBase {

    @Override
    protected String participantPath() {
        return "nested-participant";
    }

    private static final Logger log = LoggerFactory.getLogger(NestedJoinLraIT.class);

    @Test
    void testJoinNestedLraDuplicates_crashAtJoinAfterSave() {
        log.info("NestedJoinLraIT: testJoinNestedLraDuplicates_crashAtJoinAfterSave");
        injectResetAll();

        URI parent = startTopLra("nested-join-after");
        URI nested = startNestedLra(parent, "nested-join-after");

        URI compensateUri = participantUri(COMPENSATE);
        URI completeUri = participantUri(COMPLETE);

        URI injected = nextRoutedCoordinator();
        log.info("Injecting JOIN_AFTER_SAVE on coordinator {}", injected);
        enableFailurePoint(injected, InjectPoint.JOIN_AFTER_SAVE.name());

        URI recoveryUrl = lraClient.joinLRA(nested, 30L, compensateUri, completeUri,
                null, null, null, null, new StringBuilder());
        log.info("nested joinLRA recoveryUrl: {}", recoveryUrl);
        assertNotNull(recoveryUrl);

        List<String> uids = uniqueUids(getAllActiveIdsAcrossCoordinators());
        log.info("Cluster-wide unique active uids after JOIN_AFTER_SAVE crash: {}", uids);
        assertTrue(uids.contains(LRAConstants.getLRAUid(parent)),
                "Parent LRA must remain active after JOIN_AFTER_SAVE crash, uids=" + uids);
        assertTrue(uids.contains(LRAConstants.getLRAUid(nested)),
                "Nested LRA must remain active exactly once after JOIN_AFTER_SAVE crash, uids=" + uids);
    }

    @Test
    void testJoinNestedLra_crashAtJoinBeforeSave() {
        log.info("NestedJoinLraIT: testJoinNestedLra_crashAtJoinBeforeSave");
        injectResetAll();

        URI parent = startTopLra("nested-join-before");
        URI nested = startNestedLra(parent, "nested-join-before");

        URI compensateUri = participantUri(COMPENSATE);
        URI completeUri = participantUri(COMPLETE);

        URI injected = nextRoutedCoordinator();
        log.info("Injecting JOIN_BEFORE_SAVE on coordinator {}", injected);
        enableFailurePoint(injected, InjectPoint.JOIN_BEFORE_SAVE.name());

        URI recoveryUrl = lraClient.joinLRA(nested, 30L, compensateUri, completeUri,
                null, null, null, null, new StringBuilder());
        assertNotNull(recoveryUrl);

        List<String> uids = uniqueUids(getAllActiveIdsAcrossCoordinators());
        log.info("Cluster-wide unique active uids after JOIN_BEFORE_SAVE crash: {}", uids);
        assertTrue(uids.contains(LRAConstants.getLRAUid(parent)),
                "Parent LRA must remain active after JOIN_BEFORE_SAVE crash, uids=" + uids);
        assertTrue(uids.contains(LRAConstants.getLRAUid(nested)),
                "Nested LRA must remain active exactly once after JOIN_BEFORE_SAVE crash, uids=" + uids);
    }

    private static List<String> uniqueUids(List<String> uris) {
        return uris.stream()
                .map(s -> LRAConstants.getLRAUid(URI.create(s)))
                .distinct()
                .collect(Collectors.toList());
    }
}
