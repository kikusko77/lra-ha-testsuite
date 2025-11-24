package io.narayana.lra.testcontainers;

import java.time.Duration;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class LraCoordinatorContainer extends GenericContainer<LraCoordinatorContainer> {

    private final String id;

    public LraCoordinatorContainer(String id, String sharedObjectStorePath) {
        //        super("quay.io/jbosstm/lra-coordinator:7.2.2.Final-3.29.3");
        super("christiankuric/lra-coordinator-quarkus:1.0.0-SNAPSHOT");

        this.id = id;

        withExposedPorts(8080);
        withFileSystemBind(sharedObjectStorePath, "/shared-objectstore", BindMode.READ_WRITE);
        withEnv("JAVA_OPTS", "-Dcom.arjuna.ats.arjuna.objectstore.objectStoreDir=/shared-objectstore");
        waitingFor(Wait.forHttp("/lra-coordinator/start")
                .forPort(8080)
                .forStatusCodeMatching(status -> status == 404)
                .withStartupTimeout(Duration.ofSeconds(60)));

        String serviceName = "lra-coordinator-" + id;
        withLabel("traefik.enable", "true");
        withLabel("traefik.http.services." + serviceName + ".loadbalancer.server.port", "8080");
        withLabel("traefik.http.routers." + serviceName + ".rule", "PathPrefix(`/lra-coordinator`)");
        withLabel("traefik.http.routers." + serviceName + ".priority", "10");
    }

    public String getId() {
        return id;
    }
}
