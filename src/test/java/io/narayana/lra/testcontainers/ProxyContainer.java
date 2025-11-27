package io.narayana.lra.testcontainers;

import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

public class ProxyContainer extends GenericContainer<ProxyContainer> {

    public ProxyContainer(Network net) {
        super("traefik:v3.0");

        withNetwork(net);
        withExposedPorts(8080);

        withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_ONLY);

        withCommand(
                "--providers.docker=true",
                "--providers.docker.endpoint=unix:///var/run/docker.sock",
                "--providers.docker.exposedByDefault=false",
                "--entrypoints.http.address=:8080",
                "--api.insecure=true",
                "--api.dashboard=true",
                "--entrypoints.traefik.address=:9999",
                "--log.level=DEBUG");

        withLogConsumer(outputFrame -> System.out.print("TRAEFIK: " + outputFrame.getUtf8String()));
    }

    public String getUrl() {
        return "http://" + getHost() + ":" + getMappedPort(8080);
    }
}
