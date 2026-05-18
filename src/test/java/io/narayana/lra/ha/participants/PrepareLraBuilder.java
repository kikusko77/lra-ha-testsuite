package io.narayana.lra.ha.participants;

import static io.narayana.lra.ha.participants.ParticipantEndpoints.COMPENSATE;
import static io.narayana.lra.ha.participants.ParticipantEndpoints.COMPLETE;

import java.net.URI;
import org.jboss.logging.Logger;

public final class PrepareLraBuilder {

    private static final Logger LOG = Logger.getLogger(PrepareLraBuilder.class);

    private final TestBase test;

    private URI parent;
    private String clientId;
    private String compensatePath = COMPENSATE;
    private String completePath = COMPLETE;
    private String forgetPath;
    private String statusPath;
    private String afterPath;

    PrepareLraBuilder(TestBase test) {
        this.test = test;
    }

    public PrepareLraBuilder parent(URI parent) {
        this.parent = parent;
        return this;
    }

    public PrepareLraBuilder clientId(String id) {
        this.clientId = id;
        return this;
    }

    public PrepareLraBuilder scenario(String scenario) {
        this.clientId = test.participantClientId(scenario);
        return this;
    }

    public PrepareLraBuilder compensate(String path) {
        this.compensatePath = path;
        return this;
    }

    public PrepareLraBuilder complete(String path) {
        this.completePath = path;
        return this;
    }

    public PrepareLraBuilder forget(String path) {
        this.forgetPath = path;
        return this;
    }

    public PrepareLraBuilder status(String path) {
        this.statusPath = path;
        return this;
    }

    public PrepareLraBuilder after(String path) {
        this.afterPath = path;
        return this;
    }

    public URI start() {
        if (clientId == null) {
            throw new IllegalStateException("clientId(...) or scenario(...) must be set");
        }
        test.injectResetAll();

        URI lra = test.startLra(parent, clientId);
        test.lrasToAfterFinish.add(lra);

        URI compensate = test.participantUri(compensatePath);
        URI complete = test.participantUri(completePath);
        URI forget = forgetPath == null ? null : test.participantUri(forgetPath);
        URI status = statusPath == null ? null : test.participantUri(statusPath);
        URI after = afterPath == null ? null : test.participantUri(afterPath);

        URI recovery = test.lraClient.joinLRA(lra, 30L, compensate, complete, forget, null, after, status,
                new StringBuilder());

        if (parent != null) {
            LOG.debugf("Enrolled NESTED compensate=%s, complete=%s, forget=%s, after=%s, status=%s, "
                    + "parent=%s, nested=%s, recoveryUrl=%s",
                    compensate, complete, forget, after, status, parent, lra, recovery);
        } else {
            LOG.debugf("Enrolled compensate=%s, complete=%s, forget=%s, after=%s, status=%s, recoveryUrl=%s",
                    compensate, complete, forget, after, status, recovery);
        }
        return lra;
    }
}
