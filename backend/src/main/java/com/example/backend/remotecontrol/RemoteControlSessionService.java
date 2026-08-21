package com.example.backend.remotecontrol;

import com.example.backend.common.exception.ApplicationException;
import com.example.backend.common.exception.ConflictException;
import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.command.CommandLifecycleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RemoteControlSessionService {

    private static final Logger log = LoggerFactory.getLogger(RemoteControlSessionService.class);
    private static final String START = "REMOTE_CONTROL_START";
    private static final String STOP = "REMOTE_CONTROL_STOP";

    private final CommandLifecycleService commandLifecycleService;
    private final CommandQueueRepository commandQueueRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ObjectMapper objectMapper;
    private final RemoteControlRelayProperties properties;
    private final RemoteControlRelayRegistry relayRegistry;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<UUID, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> activeSessionByMac = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();

    @Autowired
    public RemoteControlSessionService(
            CommandLifecycleService commandLifecycleService,
            CommandQueueRepository commandQueueRepository,
            PcMasterRepository pcMasterRepository,
            ObjectMapper objectMapper,
            RemoteControlRelayProperties properties,
            RemoteControlRelayRegistry relayRegistry) {
        this(commandLifecycleService, commandQueueRepository, pcMasterRepository, objectMapper,
                properties, Clock.systemDefaultZone(), relayRegistry);
    }

    RemoteControlSessionService(
            CommandLifecycleService commandLifecycleService,
            CommandQueueRepository commandQueueRepository,
            PcMasterRepository pcMasterRepository,
            ObjectMapper objectMapper,
            RemoteControlRelayProperties properties,
            Clock clock) {
        this(commandLifecycleService, commandQueueRepository, pcMasterRepository, objectMapper,
                properties, clock, new RemoteControlRelayRegistry());
    }

    RemoteControlSessionService(
            CommandLifecycleService commandLifecycleService,
            CommandQueueRepository commandQueueRepository,
            PcMasterRepository pcMasterRepository,
            ObjectMapper objectMapper,
            RemoteControlRelayProperties properties,
            Clock clock,
            RemoteControlRelayRegistry relayRegistry) {
        this.commandLifecycleService = commandLifecycleService;
        this.commandQueueRepository = commandQueueRepository;
        this.pcMasterRepository = pcMasterRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.relayRegistry = relayRegistry;
    }

    public RemoteControlSessionView createSession(String requestedMac, Long completedTaskId, String owner) {
        ensureEnabled();
        PcMaster pc = requireOnlinePc(requestedMac);
        String canonicalMac = canonicalMac(pc.getMacAddress());
        UUID sessionId = UUID.randomUUID();
        Instant now = clock.instant();
        SessionEntry entry = new SessionEntry(
                sessionId,
                pc.getMacAddress(),
                canonicalMac,
                owner,
                now,
                now.plus(properties.getSessionTtl()),
                now.plus(properties.getCommandTimeout()));

        synchronized (registryLock) {
            if (activeSessionByMac.size() >= Math.max(1, properties.getMaxSessions())) {
                throw new ConflictException("Maximum remote-control sessions reached");
            }
            UUID existing = activeSessionByMac.putIfAbsent(canonicalMac, sessionId);
            if (existing != null) {
                throw new ConflictException("Remote-control session already active for this PC");
            }
            sessions.put(sessionId, entry);
        }

        try {
            if (completedTaskId == null) {
                entry.taskId = enqueue(pc.getMacAddress(), START, sessionId);
            } else {
                entry.taskId = completedTaskId;
                applyValidatedResult(entry, requireCompletedStartTask(entry, completedTaskId, pc));
            }
            return toView(entry);
        } catch (RuntimeException exception) {
            sessions.remove(sessionId);
            activeSessionByMac.remove(canonicalMac, sessionId);
            throw exception;
        }
    }

    public RemoteControlSessionView getSession(UUID sessionId, String owner) {
        SessionEntry entry = requireOwnedSession(sessionId, owner);
        synchronized (entry) {
            refreshPending(entry);
            return toView(entry);
        }
    }

    public RemoteControlTicketResponse issueTicket(UUID sessionId, String owner) {
        SessionEntry entry = requireOwnedSession(sessionId, owner);
        synchronized (entry) {
            refreshPending(entry);
            if (entry.state != State.READY && entry.state != State.TICKET_ISSUED) {
                throw new ConflictException("Remote-control session is not ready: " + entry.state);
            }
            Instant now = clock.instant();
            if (!now.isBefore(entry.expiresAt)
                    || entry.readyDeadline == null
                    || !now.isBefore(entry.readyDeadline)) {
                expire(entry, "Remote-control session expired");
                throw new ConflictException("Remote-control session expired");
            }
            String ticket = newTicket();
            entry.ticketHash = hashTicket(ticket);
            entry.ticketExpiresAt = min(min(now.plus(properties.getTicketTtl()), entry.expiresAt),
                    entry.readyDeadline);
            entry.state = State.TICKET_ISSUED;
            return new RemoteControlTicketResponse(
                    entry.sessionId,
                    entry.taskId,
                    "/vnc-relay/" + entry.sessionId + "?ticket=" + ticket,
                    entry.oneTimePassword,
                    entry.ticketExpiresAt);
        }
    }

    public RemoteControlRelayGrant consumeTicket(UUID sessionId, String ticket) {
        SessionEntry entry = requireSession(sessionId);
        synchronized (entry) {
            Instant now = clock.instant();
            if (entry.state != State.TICKET_ISSUED
                    || entry.ticketHash == null
                    || entry.ticketExpiresAt == null
                    || !now.isBefore(entry.ticketExpiresAt)
                    || !MessageDigest.isEqual(entry.ticketHash, hashTicket(ticket))) {
                throw new ApplicationException("REMOTE_CONTROL_TICKET_INVALID",
                        "Invalid or expired remote-control ticket", 401);
            }
            entry.ticketHash = null;
            entry.ticketExpiresAt = null;
            entry.state = State.CONNECTING;
            return new RemoteControlRelayGrant(
                    entry.sessionId,
                    entry.macAddress,
                    entry.targetAddress,
                    entry.targetPort,
                    entry.expiresAt);
        }
    }

    public void markConnected(UUID sessionId) {
        SessionEntry entry = requireSession(sessionId);
        synchronized (entry) {
            if (entry.state != State.CONNECTING) {
                throw new ConflictException("Remote-control relay is not connecting");
            }
            entry.state = State.CONNECTED;
        }
    }

    public void connectionFailed(UUID sessionId, String reason) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            if (entry.state == State.CONNECTING) {
                fail(entry, reason == null || reason.isBlank()
                        ? "Unable to connect to the target VNC server" : reason);
            }
        }
    }

    public void closeSession(UUID sessionId, String owner) {
        SessionEntry entry = requireOwnedSession(sessionId, owner);
        closeEntry(entry, "Closed by administrator");
    }

    public void closeSessions(List<UUID> sessionIds, String owner) {
        List<SessionEntry> ownedEntries = new LinkedHashSet<>(sessionIds).stream()
                .map(sessionId -> requireOwnedSession(sessionId, owner))
                .toList();
        ownedEntries.forEach(entry -> closeEntry(entry, "Closed by administrator"));
    }

    public void relayClosed(UUID sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry != null) {
            closeEntry(entry, "Relay disconnected");
        }
    }

    @Scheduled(fixedDelayString = "${app.remote-control.cleanup-interval-ms:30000}")
    public void cleanupExpiredSessions() {
        Instant now = clock.instant();
        for (SessionEntry entry : sessions.values()) {
            synchronized (entry) {
                if (entry.state == State.PENDING && !now.isBefore(entry.commandDeadline)) {
                    expire(entry, "Remote-control start command timed out");
                } else if (isActive(entry.state) && !now.isBefore(entry.expiresAt)) {
                    expire(entry, "Remote-control session expired");
                } else if (isWaitingForConnection(entry.state)
                        && entry.readyDeadline != null
                        && !now.isBefore(entry.readyDeadline)) {
                    expire(entry, "Remote-control connection window expired");
                } else if (entry.state == State.TICKET_ISSUED
                        && entry.ticketExpiresAt != null
                        && !now.isBefore(entry.ticketExpiresAt)) {
                    entry.ticketHash = null;
                    entry.ticketExpiresAt = null;
                    entry.state = State.READY;
                } else if (entry.state == State.CLOSED || entry.state == State.FAILED) {
                    tryIssueStop(entry);
                    if (entry.closedAt != null
                            && ((entry.stopIssued && now.isAfter(entry.closedAt.plusSeconds(300)))
                            || now.isAfter(entry.closedAt.plusSeconds(3600)))) {
                        sessions.remove(entry.sessionId, entry);
                    }
                }
            }
        }
    }

    private void refreshPending(SessionEntry entry) {
        if (entry.state != State.PENDING) {
            return;
        }
        if (!clock.instant().isBefore(entry.commandDeadline)) {
            expire(entry, "Remote-control start command timed out");
            return;
        }
        CommandQueue task = commandQueueRepository.findById(entry.taskId)
                .orElseThrow(() -> new ResourceNotFoundException("taskId not found: " + entry.taskId));
        String status = normalize(task.getStatus());
        if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
            fail(entry, task.getStderr() == null || task.getStderr().isBlank()
                    ? "Remote-control start command failed" : task.getStderr());
            return;
        }
        if ("COMPLETED".equals(status)) {
            try {
                PcMaster pc = requireOnlinePc(entry.macAddress);
                applyValidatedResult(entry, validateCompletedStartTask(entry, task, pc));
            } catch (ApplicationException exception) {
                fail(entry, exception.getMessage());
            }
        }
    }

    private CommandQueue requireCompletedStartTask(SessionEntry entry, Long taskId, PcMaster pc) {
        CommandQueue task = commandQueueRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("taskId not found: " + taskId));
        return validateCompletedStartTask(entry, task, pc);
    }

    private CommandQueue validateCompletedStartTask(SessionEntry entry, CommandQueue task, PcMaster pc) {
        if (!START.equalsIgnoreCase(task.getCommandType())) {
            throw new InvalidRequestException("taskId is not a REMOTE_CONTROL_START command");
        }
        if (!entry.canonicalMac.equals(canonicalMac(task.getMacAddress()))
                || !entry.canonicalMac.equals(canonicalMac(pc.getMacAddress()))) {
            throw new InvalidRequestException("taskId does not belong to the requested PC");
        }
        if (!"COMPLETED".equalsIgnoreCase(task.getStatus())
                || task.getExitCode() == null || task.getExitCode() != 0) {
            throw new ConflictException("Remote-control start command is not completed successfully");
        }
        LocalDateTime oldest = LocalDateTime.now(clock).minus(properties.getCompletedTaskMaxAge());
        if (task.getCompletedAt() == null || task.getCompletedAt().isBefore(oldest)) {
            throw new ConflictException("Remote-control start result is too old");
        }
        return task;
    }

    private void applyValidatedResult(SessionEntry entry, CommandQueue task) {
        try {
            JsonNode result = objectMapper.readTree(task.getStdout() == null ? "" : task.getStdout());
            long resultTaskId = result.path("taskId").asLong(-1L);
            int port = result.path("port").asInt(-1);
            String password = result.path("oneTimePassword").asText("");
            if (resultTaskId != task.getTaskId()) {
                throw new InvalidRequestException("Remote-control result taskId mismatch");
            }
            if (properties.getAllowedPorts() == null || !properties.getAllowedPorts().contains(port)) {
                throw new InvalidRequestException("Remote-control result port is not allowed");
            }
            if (!password.matches("^[A-Za-z0-9]{8}$")) {
                throw new InvalidRequestException("Remote-control result password is invalid");
            }
            PcMaster pc = requireOnlinePc(entry.macAddress);
            InetAddress address = validateDestination(pc.getIpAddress());
            entry.targetAddress = address;
            entry.targetPort = port;
            entry.oneTimePassword = password;
            entry.readyDeadline = min(clock.instant().plus(properties.getReadyTtl()), entry.expiresAt);
            entry.state = State.READY;
        } catch (ApplicationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidRequestException("Malformed remote-control command result", exception);
        }
    }

    InetAddress validateDestination(String ipAddress) {
        InetAddress address = parseIpv4Literal(ipAddress);
        byte[] raw = address.getAddress();
        int first = raw[0] & 0xff;
        boolean unsafe = address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || first == 0
                || first >= 224;
        try {
            unsafe = unsafe || NetworkInterface.getByInetAddress(address) != null;
        } catch (Exception exception) {
            throw new InvalidRequestException("Unable to validate remote-control destination", exception);
        }
        if (unsafe) {
            throw new InvalidRequestException("Remote-control destination is not allowed");
        }
        List<String> cidrs = properties.getAllowedCidrs();
        if (cidrs != null && !cidrs.isEmpty()
                && cidrs.stream().filter(value -> value != null && !value.isBlank())
                .noneMatch(value -> isInCidr(raw, value.trim()))) {
            throw new InvalidRequestException("Remote-control destination is outside allowed CIDRs");
        }
        return address;
    }

    private InetAddress parseIpv4Literal(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("Registered PC does not have an IP address");
        }
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) {
            throw new InvalidRequestException("Registered PC IP must be an IPv4 literal");
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
                throw new InvalidRequestException("Registered PC IP must be an IPv4 literal");
            }
            int octet;
            try {
                octet = Integer.parseInt(parts[i]);
            } catch (NumberFormatException exception) {
                throw new InvalidRequestException("Registered PC IP must be an IPv4 literal", exception);
            }
            if (octet > 255) {
                throw new InvalidRequestException("Registered PC IP must be an IPv4 literal");
            }
            bytes[i] = (byte) octet;
        }
        try {
            return Inet4Address.getByAddress(bytes);
        } catch (Exception exception) {
            throw new InvalidRequestException("Registered PC IP is invalid", exception);
        }
    }

    private boolean isInCidr(byte[] address, String cidr) {
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2) {
            throw new InvalidRequestException("Invalid remote-control allowed CIDR: " + cidr);
        }
        byte[] network = parseIpv4LiteralForCidr(parts[0]).getAddress();
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException("Invalid remote-control allowed CIDR: " + cidr, exception);
        }
        if (prefix < 0 || prefix > 32) {
            throw new InvalidRequestException("Invalid remote-control allowed CIDR: " + cidr);
        }
        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    private InetAddress parseIpv4LiteralForCidr(String value) {
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 4) throw new InvalidRequestException("Invalid IPv4 CIDR address");
        byte[] bytes = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(parts[i]);
                if (octet < 0 || octet > 255) throw new NumberFormatException();
                bytes[i] = (byte) octet;
            }
            return Inet4Address.getByAddress(bytes);
        } catch (Exception exception) {
            throw new InvalidRequestException("Invalid IPv4 CIDR address", exception);
        }
    }

    private PcMaster requireOnlinePc(String requestedMac) {
        PcMaster pc = findPc(requestedMac)
                .orElseThrow(() -> new ResourceNotFoundException("PC not found: " + requestedMac));
        if (!"ONLINE".equalsIgnoreCase(pc.getStatus())) {
            throw new ConflictException("PC is not online");
        }
        LocalDateTime oldest = LocalDateTime.now(clock).minus(properties.getOnlineFreshness());
        if (pc.getLastSeen() == null || pc.getLastSeen().isBefore(oldest)) {
            throw new ConflictException("PC heartbeat is stale");
        }
        return pc;
    }

    private Optional<PcMaster> findPc(String requestedMac) {
        Optional<PcMaster> exact = pcMasterRepository.findById(requestedMac);
        if (exact.isPresent()) return exact;
        String canonical = canonicalMac(requestedMac);
        return pcMasterRepository.findAll().stream()
                .filter(pc -> canonical.equals(canonicalMac(pc.getMacAddress())))
                .findFirst();
    }

    private long enqueue(String macAddress, String type, UUID sessionId) {
        CommandRequestDto request = new CommandRequestDto();
        request.setScope("SINGLE");
        request.setMacAddress(macAddress);
        request.setType(type);
        request.setPayloadJson("{\"sessionId\":\"" + sessionId + "\"}");
        Map<String, Object> response = commandLifecycleService.enqueue(request);
        Object taskIds = response.get("taskIds");
        if (!(taskIds instanceof List<?> ids) || ids.size() != 1 || !(ids.getFirst() instanceof Number taskId)) {
            throw new ConflictException("Remote-control command could not be queued");
        }
        return taskId.longValue();
    }

    private void closeEntry(SessionEntry entry, String reason) {
        boolean changed = false;
        synchronized (entry) {
            if (entry.state != State.CLOSED) {
                entry.state = State.CLOSED;
                entry.failureReason = reason;
                entry.closedAt = clock.instant();
                entry.ticketHash = null;
                entry.oneTimePassword = null;
                activeSessionByMac.remove(entry.canonicalMac, entry.sessionId);
                changed = true;
            }
        }
        if (changed) relayRegistry.close(entry.sessionId);
        tryIssueStop(entry);
    }

    private void expire(SessionEntry entry, String reason) {
        closeEntry(entry, reason);
    }

    private void fail(SessionEntry entry, String reason) {
        entry.state = State.FAILED;
        entry.failureReason = reason;
        entry.closedAt = clock.instant();
        entry.ticketHash = null;
        entry.oneTimePassword = null;
        activeSessionByMac.remove(entry.canonicalMac, entry.sessionId);
        relayRegistry.close(entry.sessionId);
        tryIssueStop(entry);
    }

    private void tryIssueStop(SessionEntry entry) {
        synchronized (entry) {
            if (entry.stopIssued) return;
            try {
                enqueue(entry.macAddress, STOP, entry.sessionId);
                entry.stopIssued = true;
            } catch (RuntimeException exception) {
                log.warn("Failed to queue remote-control stop for session {} (will retry): {}",
                        entry.sessionId, exception.getMessage());
            }
        }
    }

    private SessionEntry requireOwnedSession(UUID sessionId, String owner) {
        SessionEntry entry = requireSession(sessionId);
        if (owner == null || !owner.equals(entry.owner)) {
            throw new ApplicationException("FORBIDDEN", "Remote-control session owner mismatch", 403);
        }
        return entry;
    }

    private SessionEntry requireSession(UUID sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            throw new ResourceNotFoundException("Remote-control session not found: " + sessionId);
        }
        return entry;
    }

    private RemoteControlSessionView toView(SessionEntry entry) {
        return new RemoteControlSessionView(
                entry.sessionId,
                entry.taskId,
                entry.macAddress,
                entry.state.name(),
                entry.expiresAt,
                entry.failureReason);
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new ApplicationException("REMOTE_CONTROL_DISABLED", "Remote control relay is disabled", 503);
        }
    }

    private String newTicket() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] hashTicket(String ticket) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((ticket == null ? "" : ticket).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String canonicalMac(String value) {
        if (value == null) return "";
        return value.replace(":", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private boolean isActive(State state) {
        return state == State.PENDING || state == State.READY || state == State.TICKET_ISSUED
                || state == State.CONNECTING || state == State.CONNECTED;
    }

    private boolean isWaitingForConnection(State state) {
        return state == State.READY || state == State.TICKET_ISSUED || state == State.CONNECTING;
    }

    private enum State {
        PENDING,
        READY,
        TICKET_ISSUED,
        CONNECTING,
        CONNECTED,
        CLOSED,
        FAILED
    }

    private static final class SessionEntry {
        private final UUID sessionId;
        private final String macAddress;
        private final String canonicalMac;
        private final String owner;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final Instant commandDeadline;
        private Long taskId;
        private State state = State.PENDING;
        private InetAddress targetAddress;
        private int targetPort;
        private String oneTimePassword;
        private byte[] ticketHash;
        private Instant ticketExpiresAt;
        private Instant readyDeadline;
        private String failureReason;
        private Instant closedAt;
        private boolean stopIssued;

        private SessionEntry(UUID sessionId, String macAddress, String canonicalMac, String owner,
                             Instant createdAt, Instant expiresAt, Instant commandDeadline) {
            this.sessionId = sessionId;
            this.macAddress = macAddress;
            this.canonicalMac = canonicalMac;
            this.owner = owner;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.commandDeadline = commandDeadline;
        }
    }
}
