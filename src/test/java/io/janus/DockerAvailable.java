package io.janus;

import org.testcontainers.DockerClientFactory;

/**
 * Whether this machine can run the integration tests at all.
 *
 * <p>A separate class on purpose: the condition is read before {@link IntegrationTest} is
 * initialised, and asking the question from inside that class would trigger the very container
 * start it is meant to guard.
 *
 * <p>The tests are skipped rather than failed when Docker is absent, so a contributor without it
 * still gets a green {@code mvn verify} out of the unit suite. CI runs with Docker, and the
 * coverage floor is what stops the integration suite from being quietly skipped there.
 */
final class DockerAvailable {
    private DockerAvailable() {}

    static boolean yes() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
