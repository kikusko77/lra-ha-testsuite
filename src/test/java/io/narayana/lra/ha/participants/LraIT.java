package io.narayana.lra.ha.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class LraIT extends TestBase {

    private static final Logger log = LoggerFactory.getLogger(LraIT.class);

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void testLraDuplicates() {
        holdAfterCurrentPush(coordinatorUris.get(0), 5000, "io.naryana.lra.ha.LRAParticipant#bookGame", 2);
        holdAfterCurrentPush(coordinatorUris.get(1), 5000, "io.naryana.lra.ha.LRAParticipant#bookGame", 1);
        URI lra = lraClient.startLRA(
                null,
                "io.naryana.lra.ha.LRAParticipant#bookGame",
                30L,
                ChronoUnit.SECONDS,
                true);

        lrasToAfterFinish.add(lra);
        List<String> all = new ArrayList<>(getActiveIds(coordinatorUris.getFirst()));

        long count = all.size();
        assertEquals(1, count, "Expected exactly one active LRA " + " but got " + count + " ids=" + all);
    }
}
