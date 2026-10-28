package com.platform.system.alert;

import com.platform.system.service.AlertWebhookService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

class ScratchWebhookDebugTest {

    @Test
    void debugFacade() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<String>();
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();

        var sender = new WebhookAlertSender();
        ReflectionTestUtils.setField(sender, "enabled", true);
        ReflectionTestUtils.setField(sender, "url",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        ReflectionTestUtils.setField(sender, "secret", "");
        ReflectionTestUtils.setField(sender, "type", "dingtalk");
        var service = new com.platform.system.service.AlertWebhookService(sender, null, null);
        var result = service.alertAll("数据库连接数告警", "活跃连接 95%");
        System.out.println("SCRATCH result=" + result);
        System.out.println("SCRATCH body=" + body.get());
        server.stop(0);
    }
}
