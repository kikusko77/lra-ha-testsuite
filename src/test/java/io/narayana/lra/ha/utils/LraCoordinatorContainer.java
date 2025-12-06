package io.narayana.lra.ha.utils;

import java.time.Duration;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class LraCoordinatorContainer extends GenericContainer<LraCoordinatorContainer> {

    private final String id;

    public LraCoordinatorContainer(String id, String sharedObjectStorePath) {
        //        super("quay.io/jbosstm/lra-coordinator:2025-11-27");
        super("local-coordinator:latest");

        this.id = id;

        withExposedPorts(8090);
        withEnv("QUARKUS_HTTP_PORT", "8090");
        withFileSystemBind(sharedObjectStorePath, "/shared-objectstore", BindMode.READ_WRITE);
        withEnv("JAVA_OPTS", "-Dcom.arjuna.ats.arjuna.objectstore.objectStoreDir=/shared-objectstore");
        waitingFor(Wait.forHttp("/lra-coordinator")
                .forPort(8090)
                .forStatusCodeMatching(status -> status == 200)
                .withStartupTimeout(Duration.ofSeconds(60)));

        String serviceName = "lra-coordinator-" + id;
        withLabel("traefik.enable", "true");
        withLabel("traefik.http.services." + serviceName + ".loadbalancer.server.port", "8090");
        withLabel("traefik.http.routers." + serviceName + ".rule", "PathPrefix(`/lra-coordinator`)");
        withLabel("traefik.http.routers." + serviceName + ".priority", "10");
    }

    public String getId() {
        return id;
    }
}
