package com.imslbd.call_center.controller;

import com.imslbd.call_center.MyEvents;
import com.imslbd.call_center.MyUris;
import com.imslbd.call_center.gv;
import io.crm.util.Util;
import io.crm.web.util.Converters;
import io.crm.web.util.WebUtils;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

/**
 * Created by someone on 08/12/2015.
 */
public class BrController {
    private final Vertx vertx;

    public BrController(Vertx vertx, Router router) {
        this.vertx = vertx;
        findAll(router);
        brInfo(router);
    }

    public void findAll(Router router) {
        router.get(MyUris.BRS.value).handler(ctx -> {
            final JsonObject entries = new JsonObject();

            long distributionHouseId = Converters.toLong(ctx.request().getParam("distributionHouseId"));
            if (distributionHouseId > 0) entries.put(gv.distributionHouseId, distributionHouseId);

            Util.<JsonObject>send(vertx.eventBus(), MyEvents.FIND_ALL_BRS, entries.put("baseUrl", ctx.session().get("baseUrl").toString()))
                .map(m -> m.body())
                .then(v -> ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, Controllers.APPLICATION_JSON))
                .then(js -> ctx.response().end(js.encodePrettily()))
                .error(ctx::fail)
            ;
        });
    }

    public void brInfo(Router router) {
        router.get(MyUris.BR_INFO.value).handler(ctx -> {
            Util.<JsonObject>send(vertx.eventBus(), MyEvents.BR_INFO, WebUtils.toJson(ctx.request().params()).put("baseUrl", ctx.session().get("baseUrl").toString()))
                .map(m -> m.body())
                .then(v -> ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, Controllers.APPLICATION_JSON))
                .then(js -> ctx.response().end(js.encodePrettily()))
                .error(ctx::fail)
            ;
        });
    }
}
