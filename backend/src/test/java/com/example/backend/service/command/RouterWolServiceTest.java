package com.example.backend.service.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RouterWolServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void wake_primaryOffline_fallsBackToSecondaryAndAllowsRetry() throws Exception {
        AtomicInteger signalCount = new AtomicInteger();
        AtomicBoolean signalHadSessionCookie = new AtomicBoolean();
        AtomicReference<String> signaledMac = new AtomicReference<>();
        AtomicReference<String> registeredMac = new AtomicReference<>();
        server = startRouterServer(signalCount, signalHadSessionCookie, signaledMac, registeredMac);

        RouterWolProperties properties = enabledProperties();
        properties.setDedupWindow(Duration.ofMinutes(2));
        properties.setRoutes(List.of(route(
                "127.0.0.0/8",
                "http://127.0.0.1:1",
                "http://127.0.0.1:" + server.getAddress().getPort())));
        RouterWolService service = new RouterWolService(properties, objectMapper);

        RouterWolService.Result first = service.wake(
                "127.0.0.25", List.of("aa:bb:cc:dd:ee:ff"));
        RouterWolService.Result retry = service.wake(
                "127.0.0.25", List.of("AA-BB-CC-DD-EE-FF"));

        assertThat(first.status()).isEqualTo(RouterWolService.Status.SENT);
        assertThat(first.attempts()).isEqualTo(2);
        assertThat(first.gateway()).isEqualTo("127.0.0.1:" + server.getAddress().getPort());
        assertThat(retry.status()).isEqualTo(RouterWolService.Status.SENT);
        assertThat(signalCount).hasValue(2);
        assertThat(signalHadSessionCookie).isTrue();
        assertThat(signaledMac).hasValue("AA-BB-CC-DD-EE-FF");
        assertThat(registeredMac).hasValue("AA-BB-CC-DD-EE-FF");
    }

    @Test
    void wake_allGatewaysOffline_returnsFailureWithoutThrowing() {
        RouterWolProperties properties = enabledProperties();
        properties.setRoutes(List.of(route("127.0.0.0/8", "http://127.0.0.1:1")));
        RouterWolService service = new RouterWolService(properties, objectMapper);

        RouterWolService.Result result = service.wake(
                "127.0.0.25", List.of("AA:BB:CC:DD:EE:01"));

        assertThat(result.status()).isEqualTo(RouterWolService.Status.FAILED);
        assertThat(result.message()).isEqualTo("router-wol-gateway-unreachable");
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test
    void wake_futureBuildingWithoutGateway_returnsUnavailable() {
        RouterWolProperties properties = enabledProperties();
        properties.setRoutes(List.of(route("172.16.13.0/24", "", " ")));
        RouterWolService service = new RouterWolService(properties, objectMapper);

        RouterWolService.Result result = service.wake(
                "172.16.13.20", List.of("AA:BB:CC:DD:EE:02"));

        assertThat(result.status()).isEqualTo(RouterWolService.Status.UNAVAILABLE);
        assertThat(result.message()).isEqualTo("router-wol-gateway-not-configured");
        assertThat(result.attempts()).isZero();
    }

    private RouterWolProperties enabledProperties() {
        RouterWolProperties properties = new RouterWolProperties();
        properties.setEnabled(true);
        properties.setUsername("router-user");
        properties.setPassword("router-password");
        properties.setConnectTimeout(Duration.ofMillis(200));
        properties.setRequestTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private RouterWolProperties.Route route(String cidr, String... gateways) {
        RouterWolProperties.Route route = new RouterWolProperties.Route();
        route.setCidr(cidr);
        route.setGateways(List.of(gateways));
        return route;
    }

    private HttpServer startRouterServer(AtomicInteger signalCount,
                                         AtomicBoolean signalHadSessionCookie,
                                         AtomicReference<String> signaledMac,
                                         AtomicReference<String> registeredMac) throws IOException {
        HttpServer httpServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.createContext("/ui/", exchange -> respond(exchange, 200, "<html></html>"));
        httpServer.createContext("/cgi/service.cgi", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if ("session/login".equals(method)) {
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                String referer = exchange.getRequestHeaders().getFirst("Referer");
                if (origin == null || referer == null || !referer.equals(origin + "/ui/")) {
                    respond(exchange, 502, "{\"error\":\"missing browser headers\"}");
                    return;
                }
                exchange.getResponseHeaders().add(
                        "Set-Cookie", "efm_session_id=test-session; SameSite=Strict; Path=/");
                respond(exchange, 200, "{\"result\":\"done\"}");
                return;
            }
            if ("wol/show".equals(method)) {
                String mac = registeredMac.get();
                String result = mac == null
                        ? "{\"result\":[]}"
                        : "{\"result\":[{\"mac\":\"" + mac + "\",\"pcname\":\"test\"}]}";
                respond(exchange, 200, result);
                return;
            }
            if ("wol/add".equals(method)) {
                registeredMac.set(request.path("params").path("mac").asText());
                respond(exchange, 200, "{\"result\":\"done\"}");
                return;
            }
            if ("wol/signal".equals(method)) {
                signalCount.incrementAndGet();
                signalHadSessionCookie.set(exchange.getRequestHeaders()
                        .getOrDefault("Cookie", List.of()).stream()
                        .anyMatch(value -> value.contains("efm_session_id=test-session")));
                signaledMac.set(request.path("params").path(0).asText());
                respond(exchange, 200, "{\"result\":\"done\"}");
                return;
            }
            respond(exchange, 400, "{\"error\":\"unexpected method\"}");
        });
        httpServer.start();
        return httpServer;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
