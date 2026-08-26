package com.example.backend.remotecontrol;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "app.remote-control")
public class RemoteControlRelayProperties {

    private boolean enabled = true;
    private int maxSessions = 64;
    private int maxViewerSessions = 192;
    private int maxViewersPerPc = 5;
    private int maxClientMessageBytes = 256 * 1024;
    private long maxSessionBytes = 2L * 1024 * 1024 * 1024;
    private int tcpReadBufferBytes = 16 * 1024;
    private Duration commandTimeout = Duration.ofSeconds(45);
    private Duration completedTaskMaxAge = Duration.ofMinutes(2);
    private Duration onlineFreshness = Duration.ofMinutes(3);
    private Duration ticketTtl = Duration.ofSeconds(30);
    private Duration readyTtl = Duration.ofMinutes(2);
    private Duration viewerGraceTtl = Duration.ofSeconds(10);
    private Duration sessionTtl = Duration.ofMinutes(30);
    private Duration tcpConnectTimeout = Duration.ofSeconds(5);
    private Duration tcpIdleTimeout = Duration.ofMinutes(2);
    private Set<Integer> allowedPorts = new LinkedHashSet<>(Set.of(5900));
    private List<String> allowedCidrs = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public int getMaxViewerSessions() {
        return maxViewerSessions;
    }

    public void setMaxViewerSessions(int maxViewerSessions) {
        this.maxViewerSessions = maxViewerSessions;
    }

    public int getMaxViewersPerPc() {
        return maxViewersPerPc;
    }

    public void setMaxViewersPerPc(int maxViewersPerPc) {
        this.maxViewersPerPc = maxViewersPerPc;
    }

    public int getMaxClientMessageBytes() {
        return maxClientMessageBytes;
    }

    public void setMaxClientMessageBytes(int maxClientMessageBytes) {
        this.maxClientMessageBytes = maxClientMessageBytes;
    }

    public long getMaxSessionBytes() {
        return maxSessionBytes;
    }

    public void setMaxSessionBytes(long maxSessionBytes) {
        this.maxSessionBytes = maxSessionBytes;
    }

    public int getTcpReadBufferBytes() {
        return tcpReadBufferBytes;
    }

    public void setTcpReadBufferBytes(int tcpReadBufferBytes) {
        this.tcpReadBufferBytes = tcpReadBufferBytes;
    }

    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    public void setCommandTimeout(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
    }

    public Duration getCompletedTaskMaxAge() {
        return completedTaskMaxAge;
    }

    public void setCompletedTaskMaxAge(Duration completedTaskMaxAge) {
        this.completedTaskMaxAge = completedTaskMaxAge;
    }

    public Duration getOnlineFreshness() {
        return onlineFreshness;
    }

    public void setOnlineFreshness(Duration onlineFreshness) {
        this.onlineFreshness = onlineFreshness;
    }

    public Duration getTicketTtl() {
        return ticketTtl;
    }

    public void setTicketTtl(Duration ticketTtl) {
        this.ticketTtl = ticketTtl;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public Duration getReadyTtl() {
        return readyTtl;
    }

    public void setReadyTtl(Duration readyTtl) {
        this.readyTtl = readyTtl;
    }

    public Duration getViewerGraceTtl() {
        return viewerGraceTtl;
    }

    public void setViewerGraceTtl(Duration viewerGraceTtl) {
        this.viewerGraceTtl = viewerGraceTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getTcpConnectTimeout() {
        return tcpConnectTimeout;
    }

    public void setTcpConnectTimeout(Duration tcpConnectTimeout) {
        this.tcpConnectTimeout = tcpConnectTimeout;
    }

    public Duration getTcpIdleTimeout() {
        return tcpIdleTimeout;
    }

    public void setTcpIdleTimeout(Duration tcpIdleTimeout) {
        this.tcpIdleTimeout = tcpIdleTimeout;
    }

    public Set<Integer> getAllowedPorts() {
        return allowedPorts;
    }

    public void setAllowedPorts(Set<Integer> allowedPorts) {
        this.allowedPorts = allowedPorts;
    }

    public List<String> getAllowedCidrs() {
        return allowedCidrs;
    }

    public void setAllowedCidrs(List<String> allowedCidrs) {
        this.allowedCidrs = allowedCidrs;
    }
}
