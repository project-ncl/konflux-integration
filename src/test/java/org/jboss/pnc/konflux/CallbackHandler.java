package org.jboss.pnc.konflux;

import org.jboss.pnc.api.konfluxbuilddriver.dto.PipelineNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

public class CallbackHandler extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(CallbackHandler.class);

    // Do not change this - firewalls in REQ5534435 / RITM2008631 have been changed to allow this.
    static final int port = 8082;

    @Override
    public void start(Promise<Void> promise) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        Route route = router.route(HttpMethod.PUT, "/internal/completed").consumes("application/json");
        route.handler(ctx -> {
            var result = ctx.body().asJsonObject().mapTo(PipelineNotification.class);
            logger.info("Received notification: {}", result);
            PipelineTest.completed.add(result);
            ctx.response().setStatusCode(200).end("Received");
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(
                        port,
                        result -> {
                            if (result.succeeded()) {
                                promise.complete();
                            } else {
                                promise.fail(result.cause());
                            }
                        });
    }
}
