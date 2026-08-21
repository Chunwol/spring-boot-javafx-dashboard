package com.example.backend.service.command;

import com.example.backend.util.IpRangeUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.wol.router")
public class RouterWolProperties {

    private boolean enabled;
    private String username = "";
    private String password = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(4);
    private Duration dedupWindow = Duration.ofMinutes(2);
    private List<Route> routes = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getDedupWindow() {
        return dedupWindow;
    }

    public void setDedupWindow(Duration dedupWindow) {
        this.dedupWindow = dedupWindow;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes == null ? new ArrayList<>() : routes;
    }

    public Route findRoute(String ipAddress) {
        if (!IpRangeUtil.isIpv4(ipAddress)) {
            return null;
        }
        for (Route route : routes) {
            if (route == null || route.getCidr() == null || route.getCidr().isBlank()) {
                continue;
            }
            try {
                if (IpRangeUtil.containsIp(ipAddress, route.getCidr(), null, null)) {
                    return route;
                }
            } catch (RuntimeException ignored) {
                // Invalid deployment configuration is treated as an unavailable route.
            }
        }
        return null;
    }

    public static class Route {
        private String cidr = "";
        private List<String> gateways = new ArrayList<>();

        public String getCidr() {
            return cidr;
        }

        public void setCidr(String cidr) {
            this.cidr = cidr;
        }

        public List<String> getGateways() {
            return gateways;
        }

        public void setGateways(List<String> gateways) {
            this.gateways = gateways == null ? new ArrayList<>() : gateways;
        }

        public List<String> activeGateways() {
            return gateways.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        }
    }
}
