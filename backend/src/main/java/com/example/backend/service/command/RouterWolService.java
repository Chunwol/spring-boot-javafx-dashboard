package com.example.backend.service.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class RouterWolService {

    private static final Logger log = LoggerFactory.getLogger(RouterWolService.class);
    private static final Pattern MAC_PATTERN = Pattern.compile("(?i)^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$");

    private final RouterWolProperties properties;
    private final ObjectMapper objectMapper;
    public RouterWolService(RouterWolProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized Result wake(String targetIp, Collection<String> targetMacs) {
        List<String> macs = normalizeMacs(targetMacs);
        if (!properties.isEnabled()) {
            return Result.unavailable("router-wol-disabled", macs.size());
        }
        if (macs.isEmpty()) {
            return Result.unavailable("router-wol-no-valid-mac", 0);
        }
        if (isBlank(properties.getUsername()) || isBlank(properties.getPassword())) {
            return Result.unavailable("router-wol-credentials-not-configured", macs.size());
        }

        RouterWolProperties.Route route = properties.findRoute(targetIp);
        if (route == null) {
            return Result.unavailable("router-wol-route-not-configured", macs.size());
        }
        List<String> gateways = route.activeGateways();
        if (gateways.isEmpty()) {
            return Result.unavailable("router-wol-gateway-not-configured", macs.size(), route.getCidr());
        }

        int attempts = 0;
        String lastFailure = "router-wol-all-gateways-failed";
        for (String gateway : gateways) {
            attempts++;
            try {
                URI baseUri = validateGateway(gateway);
                signal(baseUri, macs);
                log.info("Router WOL sent: route={}, gateway={}, targets={}",
                        route.getCidr(), authority(baseUri), macs.size());
                return Result.sent(route.getCidr(), authority(baseUri), macs.size(), attempts);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                lastFailure = "router-wol-interrupted";
                break;
            } catch (GatewayException ex) {
                lastFailure = ex.code;
                log.warn("Router WOL gateway failed: route={}, gateway={}, reason={}",
                        route.getCidr(), safeAuthority(gateway), ex.code);
            } catch (IOException | IllegalArgumentException ex) {
                lastFailure = "router-wol-gateway-unreachable";
                log.warn("Router WOL gateway unavailable: route={}, gateway={}, error={}",
                        route.getCidr(), safeAuthority(gateway), ex.getClass().getSimpleName());
            }
        }
        return Result.failed(lastFailure, route.getCidr(), macs.size(), attempts);
    }

    private void signal(URI baseUri, List<String> macs) throws IOException, InterruptedException {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(positive(properties.getConnectTimeout(), Duration.ofSeconds(2)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        Duration requestTimeout = positive(properties.getRequestTimeout(), Duration.ofSeconds(4));

        HttpRequest landingRequest = HttpRequest.newBuilder(endpoint(baseUri, "/ui/"))
                .timeout(requestTimeout)
                .header("User-Agent", "ClassroomDashboard-RouterWOL/1.0")
                .GET()
                .build();
        HttpResponse<String> landing = client.send(landingRequest, HttpResponse.BodyHandlers.ofString());
        requireSuccess(landing, "router-wol-landing-failed");

        Map<String, Object> loginPayload = Map.of(
                "method", "session/login",
                "params", Map.of("id", properties.getUsername(), "pw", properties.getPassword()));
        HttpResponse<String> login = post(client, baseUri, loginPayload, requestTimeout);
        requireDone(login, "router-wol-auth-failed");

        registerTargets(client, baseUri, macs, requestTimeout);

        Map<String, Object> signalPayload = Map.of("method", "wol/signal", "params", macs);
        HttpResponse<String> signal = post(client, baseUri, signalPayload, requestTimeout);
        requireDone(signal, "router-wol-signal-failed");
    }

    private void registerTargets(HttpClient client,
                                 URI baseUri,
                                 List<String> macs,
                                 Duration requestTimeout) throws IOException, InterruptedException {
        HttpResponse<String> show = post(client, baseUri, Map.of("method", "wol/show"), requestTimeout);
        requireSuccess(show, "router-wol-show-failed");

        List<String> registered;
        try {
            JsonNode result = objectMapper.readTree(show.body()).path("result");
            if (!result.isArray()) {
                throw new GatewayException("router-wol-show-failed");
            }
            registered = new java.util.ArrayList<>();
            for (JsonNode item : result) {
                String mac = item.path("mac").asText("").trim().toUpperCase(Locale.ROOT);
                if (!mac.isBlank()) {
                    registered.add(mac.replace(':', '-'));
                }
            }
        } catch (GatewayException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GatewayException("router-wol-show-failed");
        }

        for (String mac : macs) {
            if (registered.contains(mac)) {
                continue;
            }
            String label = "ClassroomDashboard-" + mac.substring(mac.length() - 8).replace("-", "");
            Map<String, Object> addPayload = Map.of(
                    "method", "wol/add",
                    "params", Map.of("mac", mac, "pcname", label));
            HttpResponse<String> add = post(client, baseUri, addPayload, requestTimeout);
            requireDone(add, "router-wol-register-failed");
        }
    }

    private HttpResponse<String> post(HttpClient client,
                                      URI baseUri,
                                      Map<String, Object> payload,
                                      Duration timeout) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(payload);
        String origin = baseUri.getScheme() + "://" + baseUri.getRawAuthority();
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUri, "/cgi/service.cgi"))
                .timeout(timeout)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-store")
                .header("Origin", origin)
                .header("Referer", origin + "/ui/")
                .header("User-Agent", "ClassroomDashboard-RouterWOL/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void requireDone(HttpResponse<String> response, String failureCode) {
        requireSuccess(response, failureCode);
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root != null && "done".equalsIgnoreCase(root.path("result").asText())) {
                return;
            }
        } catch (Exception ignored) {
            // Normalized below without exposing a router response body or credentials.
        }
        throw new GatewayException(failureCode);
    }

    private void requireSuccess(HttpResponse<String> response, String failureCode) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GatewayException(failureCode);
        }
    }

    private URI validateGateway(String gateway) {
        URI uri = URI.create(gateway.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("invalid router gateway");
        }
        return uri;
    }

    private URI endpoint(URI baseUri, String path) {
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private List<String> normalizeMacs(Collection<String> targetMacs) {
        if (targetMacs == null) {
            return List.of();
        }
        return targetMacs.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(MAC_PATTERN.asMatchPredicate())
                .map(mac -> mac.replace(':', '-').toUpperCase(Locale.ROOT))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String authority(URI uri) {
        return uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    }

    private String safeAuthority(String gateway) {
        try {
            return authority(validateGateway(gateway));
        } catch (RuntimeException ignored) {
            return "invalid-gateway";
        }
    }

    private static final class GatewayException extends RuntimeException {
        private final String code;

        private GatewayException(String code) {
            super(code);
            this.code = code;
        }
    }

    public enum Status {
        SENT,
        DUPLICATE,
        UNAVAILABLE,
        FAILED
    }

    public record Result(Status status,
                         String message,
                         String routeCidr,
                         String gateway,
                         int targetCount,
                         int attempts) {

        public static Result sent(String routeCidr, String gateway, int targetCount, int attempts) {
            return new Result(Status.SENT, "router-wol-sent", routeCidr, gateway, targetCount, attempts);
        }

        public static Result duplicate(String routeCidr, int targetCount) {
            return new Result(Status.DUPLICATE, "duplicate-power-command-in-progress",
                    routeCidr, null, targetCount, 0);
        }

        public static Result unavailable(String message, int targetCount) {
            return unavailable(message, targetCount, null);
        }

        public static Result unavailable(String message, int targetCount, String routeCidr) {
            return new Result(Status.UNAVAILABLE, message, routeCidr, null, targetCount, 0);
        }

        public static Result failed(String message, String routeCidr, int targetCount, int attempts) {
            return new Result(Status.FAILED, message, routeCidr, null, targetCount, attempts);
        }

        public boolean sent() {
            return status == Status.SENT;
        }

        public boolean duplicate() {
            return status == Status.DUPLICATE;
        }
    }
}
