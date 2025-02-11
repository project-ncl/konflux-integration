package org.jboss.pnc.konflux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.jboss.pnc.api.konfluxbuilddriver.dto.BuildRequest;
import org.jboss.pnc.api.konfluxbuilddriver.dto.BuildResponse;
import org.jboss.pnc.api.konfluxbuilddriver.dto.PipelineNotification;
import org.jboss.pnc.api.konfluxbuilddriver.dto.PipelineStatus;
import org.jboss.pnc.konfluxbuilddriver.Driver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpResponseExpectation;
import io.vertx.ext.web.client.WebClient;

@QuarkusTest
@QuarkusTestResource(
        value = LogCollectingTestResource.class,
        restrictToAnnotatedClass = true,
        initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
public class PipelineTest {

    private static final Logger logger = LoggerFactory.getLogger(PipelineTest.class);

    @Inject
    Driver driver;

    static Vertx vertx;

    static BlockingQueue<PipelineNotification> completed = new ArrayBlockingQueue<>(1);

    @BeforeAll
    public static void init() throws Exception {
        vertx = Vertx.vertx();
        vertx.deployVerticle(CallbackHandler.class.getName());
    }

    @AfterAll
    public static void stop() throws Exception {
        if (vertx != null) {
            vertx.close();
        }
    }

    @Test
    public void testVertxServer() throws InterruptedException {

        PipelineNotification body = new PipelineNotification(PipelineStatus.Failed, "123", null);

        WebClient.create(vertx)
                .put(CallbackHandler.port, "localhost", "/internal/completed")
                .sendJson(body)
                .expecting(HttpResponseExpectation.SC_SUCCESS);

        PipelineNotification pipelineNotification = completed.take();

        assertEquals(body.getStatus(), pipelineNotification.getStatus());
        assertEquals(body.getBuildId(), pipelineNotification.getBuildId());
    }

    // TODO: Create further tests using different url/buildScript/scmRevision for Gradle/Ant/etc
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    // Only run this test if a custom KUBECONFIG configuration file with an appropriate token for
    // the openshift cluster exists.
    @EnabledIfEnvironmentVariable(named = "KUBECONFIG", matches = ".*")
    public void testMavenPipeline() throws InterruptedException {
        // A very simple repository that still deploys a jar.
        String url = "https://github.com/project-ncl/ide-config/";
        String scmRevision = "ide-config-1.0.0";
        String buildScript = "mvn deploy -Dspotless.apply.skip";

        // Builder image
        String recipeImage = "quay.io/redhat-user-workloads/konflux-jbs-pnc-tenant/jvm-build-service-builder-images/ubi8:latest";

        // TODO: Probably need these configurable per test.
        String namespace = "pnc-devel-tenant";
        String deploy = "https://gateway.indy.corp.stage.redhat.com/api/folo/track/test-maven-konflux-int-0001/maven/group/test-maven-konflux-int-0001";
        // TODO: When can these be different?
        String dependencies = deploy;

        BuildRequest request = BuildRequest.builder()
                .namespace(namespace)
                .scmUrl(url)
                .scmRevision(scmRevision)
                .buildTool("maven")
                .buildToolVersion("3.9.5")
                .javaVersion("17")
                .buildScript(buildScript)
                .repositoryDeployUrl(deploy)
                .repositoryDependencyUrl(dependencies)
                .repositoryBuildContentId("test-maven-konflux-int-0001")
                .recipeImage(recipeImage)
                // Just use default from buildah-oci-ta for now.
                .podMemoryOverride("4Gi")
                .build();
        BuildResponse b = driver.create(request);

        logger.info("Got response {}", b);

        PipelineNotification notification = completed.take();

        logger.info("Got notification {}", notification);

        assertEquals(PipelineStatus.Succeeded, notification.getStatus());
    }
}
