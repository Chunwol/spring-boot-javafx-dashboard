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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final Map<UUID, ViewerEntry> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, StreamEntry> streams = new ConcurrentHashMap<>();
    private final Map<String, UUID> activeStreamByMac = new ConcurrentHashMap<>();
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
        return createSession(requestedMac, completedTaskId, owner, null, null);
    }

    public RemoteControlSessionView createSession(
            String requestedMac,
            Long completedTaskId,
            String owner,
            String sourceIp,
            String userAgent) {
        ensureEnabled();
        PcMaster pc = requireOnlinePc(requestedMac);
        String canonicalMac = canonicalMac(pc.getMacAddress());
        Instant now = clock.instant();
        UUID viewerId = UUID.randomUUID();
        StreamEntry stream;
        boolean createdStream = false;

        synchronized (registryLock) {
            if (activeViewerCount() >= Math.max(1, properties.getMaxViewerSessions())) {
                throw new ConflictException("Maximum remote-control viewer sessions reached");
            }
            UUID streamId = activeStreamByMac.get(canonicalMac);
            stream = streamId == null ? null : streams.get(streamId);
            if (stream != null && !isActive(stream.state)) {
                activeStreamByMac.remove(canonicalMac, stream.streamId);
                stream = null;
            }
            if (stream == null) {
                if (activeStreamByMac.size() >= Math.max(1, properties.getMaxSessions())) {
                    throw new ConflictException("Maximum remote-control PC streams reached");
                }
                stream = new StreamEntry(
                        viewerId,
                        pc.getMacAddress(),
                        canonicalMac,
                        pc.getPcName(),
                        pc.getIpAddress(),
                        owner,
                        sanitize(sourceIp, 64),
                        now,
                        now.plus(properties.getSessionTtl()),
                        now.plus(properties.getCommandTimeout()));
                streams.put(stream.streamId, stream);
                activeStreamByMac.put(canonicalMac, stream.streamId);
                createdStream = true;
            } else if (stream.viewerIds.size() >= Math.max(1, properties.getMaxViewersPerPc())) {
                throw new ConflictException("Maximum viewers reached for this PC");
            }

            ViewerEntry viewer = new ViewerEntry(
                    viewerId, owner, sanitize(sourceIp, 64), sanitize(userAgent, 500),
                    stream, now, now.plus(properties.getReadyTtl()));
            stream.viewerIds.add(viewerId);
            stream.noViewerDeadline = null;
            sessions.put(viewerId, viewer);
        }

        if (createdStream) {
            try {
                if (completedTaskId == null) {
                    stream.taskId = enqueue(
                            pc.getMacAddress(), START, stream.streamId, stream.startedBy, stream.startedFromIp);
                } else {
                    stream.taskId = completedTaskId;
                    applyValidatedResult(stream, requireCompletedStartTask(stream, completedTaskId, pc));
                }
            } catch (RuntimeException exception) {
                failStream(stream, exception.getMessage());
                sessions.remove(viewerId);
                throw exception;
            }
        }
        return toView(requireSession(viewerId));
    }

    public RemoteControlSessionView getSession(UUID sessionId, String owner) {
        ViewerEntry viewer = requireOwnedSession(sessionId, owner);
        refreshPending(viewer.stream);
        refreshExpiredTicket(viewer);
        return toView(viewer);
    }

    public RemoteControlTicketResponse issueTicket(UUID sessionId, String owner) {
        ViewerEntry viewer = requireOwnedSession(sessionId, owner);
        StreamEntry stream = viewer.stream;
        refreshPending(stream);

        String ticket;
        Instant ticketExpiresAt;
        String password;
        synchronized (stream) {
            Instant now = clock.instant();
            if (stream.state != StreamState.READY && stream.state != StreamState.CONNECTED) {
                throw new ConflictException("Remote-control stream is not ready: " + stream.state);
            }
            if (!now.isBefore(stream.expiresAt)
                    || (stream.state == StreamState.READY
                    && stream.readyDeadline != null
                    && !now.isBefore(stream.readyDeadline))) {
                closeStream(stream, "Remote-control stream expired");
                throw new ConflictException("Remote-control stream expired");
            }
            synchronized (viewer) {
                if (!viewer.active) {
                    throw new ConflictException("Remote-control viewer session is closed");
                }
                ticket = newTicket();
                viewer.ticketHash = hashTicket(ticket);
                viewer.ticketExpiresAt = min(now.plus(properties.getTicketTtl()), stream.expiresAt);
                viewer.state = ViewerState.TICKET_ISSUED;
                ticketExpiresAt = viewer.ticketExpiresAt;
                password = stream.oneTimePassword;
            }
        }
        return new RemoteControlTicketResponse(
                viewer.sessionId,
                stream.taskId,
                "/vnc-relay/" + viewer.sessionId + "?ticket=" + ticket,
                password,
                ticketExpiresAt);
    }

    public RemoteControlRelayGrant consumeTicket(UUID sessionId, String ticket) {
        ViewerEntry viewer = requireSession(sessionId);
        StreamEntry stream = viewer.stream;
        synchronized (stream) {
            if (stream.state != StreamState.READY && stream.state != StreamState.CONNECTED) {
                throw new ApplicationException("REMOTE_CONTROL_STREAM_UNAVAILABLE",
                        "Remote-control stream is unavailable", 409);
            }
            synchronized (viewer) {
                Instant now = clock.instant();
                if (!viewer.active
                        || viewer.state != ViewerState.TICKET_ISSUED
                        || viewer.ticketHash == null
                        || viewer.ticketExpiresAt == null
                        || !now.isBefore(viewer.ticketExpiresAt)
                        || !MessageDigest.isEqual(viewer.ticketHash, hashTicket(ticket))) {
                    throw new ApplicationException("REMOTE_CONTROL_TICKET_INVALID",
                            "Invalid or expired remote-control ticket", 401);
                }
                viewer.ticketHash = null;
                viewer.ticketExpiresAt = null;
                viewer.state = ViewerState.CONNECTING;
            }
            return new RemoteControlRelayGrant(
                    viewer.sessionId,
                    stream.macAddress,
                    stream.targetAddress,
                    stream.targetPort,
                    stream.expiresAt);
        }
    }

    public void markConnected(UUID sessionId) {
        ViewerEntry viewer = requireSession(sessionId);
        StreamEntry stream = viewer.stream;
        synchronized (stream) {
            synchronized (viewer) {
                if (!viewer.active || viewer.state != ViewerState.CONNECTING) {
                    throw new ConflictException("Remote-control relay is not connecting");
                }
                if (stream.state != StreamState.READY && stream.state != StreamState.CONNECTED) {
                    throw new ConflictException("Remote-control stream is unavailable");
                }
                viewer.state = ViewerState.CONNECTED;
                stream.state = StreamState.CONNECTED;
                stream.readyDeadline = null;
            }
        }
    }

    public void connectionFailed(UUID sessionId, String reason) {
        ViewerEntry viewer = sessions.get(sessionId);
        if (viewer == null) return;
        String failure = reason == null || reason.isBlank()
                ? "Unable to connect to the target VNC server" : reason;
        synchronized (viewer) {
            if (!viewer.active || viewer.state != ViewerState.CONNECTING) return;
        }
        failViewer(viewer, failure);
        if (!hasConnectedViewer(viewer.stream)) {
            failStream(viewer.stream, failure);
        }
    }

    public void closeSession(UUID sessionId, String owner) {
        ViewerEntry viewer = requireOwnedSession(sessionId, owner);
        if (!closeViewer(viewer, ViewerState.CLOSED, "Closed by administrator")
                && !isActive(viewer.stream.state)) {
            tryIssueStop(viewer.stream);
        }
    }

    public void closeSessions(List<UUID> sessionIds, String owner) {
        List<ViewerEntry> ownedEntries = new LinkedHashSet<>(sessionIds).stream()
                .map(sessionId -> requireOwnedSession(sessionId, owner))
                .toList();
        ownedEntries.forEach(viewer -> closeViewer(viewer, ViewerState.CLOSED, "Closed by administrator"));
    }

    public void closeOwnerSessions(String owner) {
        if (owner == null || owner.isBlank()) return;
        sessions.values().stream()
                .filter(viewer -> viewer.active && owner.equalsIgnoreCase(viewer.owner))
                .toList()
                .forEach(viewer -> closeViewer(viewer, ViewerState.CLOSED, "Login session replaced"));
    }

    public List<ActiveRemoteControlSessionView> listActiveSessions() {
        return sessions.values().stream()
                .filter(viewer -> viewer.active)
                .map(viewer -> new ActiveRemoteControlSessionView(
                        viewer.sessionId,
                        viewer.owner,
                        viewer.sourceIp,
                        viewer.userAgent,
                        viewer.stream.macAddress,
                        viewer.stream.pcName,
                        viewer.stream.targetIp,
                        toView(viewer).status(),
                        viewer.createdAt))
                .sorted((left, right) -> right.startedAt().compareTo(left.startedAt()))
                .toList();
    }

    public void relayClosed(UUID sessionId) {
        ViewerEntry viewer = sessions.get(sessionId);
        if (viewer != null) {
            closeViewer(viewer, ViewerState.CLOSED, "Relay disconnected");
        }
    }

    @Scheduled(fixedDelayString = "${app.remote-control.cleanup-interval-ms:5000}")
    public void cleanupExpiredSessions() {
        Instant now = clock.instant();
        for (StreamEntry stream : streams.values()) {
            StreamState state = stream.state;
            if (state == StreamState.PENDING && !now.isBefore(stream.commandDeadline)) {
                failStream(stream, "Remote-control start command timed out");
            } else if (isActive(state) && !now.isBefore(stream.expiresAt)) {
                closeStream(stream, "Remote-control stream expired");
            } else if (state == StreamState.READY
                    && stream.readyDeadline != null
                    && !now.isBefore(stream.readyDeadline)) {
                closeStream(stream, "Remote-control connection window expired");
            } else if (isActive(state)
                    && stream.noViewerDeadline != null
                    && !now.isBefore(stream.noViewerDeadline)) {
                closeStream(stream, "No remote-control viewers remain");
            } else if (state == StreamState.CLOSED || state == StreamState.FAILED) {
                tryIssueStop(stream);
                if (stream.closedAt != null
                        && ((stream.stopIssued && now.isAfter(stream.closedAt.plusSeconds(300)))
                        || now.isAfter(stream.closedAt.plusSeconds(3600)))) {
                    streams.remove(stream.streamId, stream);
                }
            }
        }

        for (ViewerEntry viewer : sessions.values()) {
            refreshExpiredTicket(viewer);
            boolean idle;
            synchronized (viewer) {
                idle = viewer.active
                        && (viewer.state == ViewerState.OPEN || viewer.state == ViewerState.TICKET_ISSUED)
                        && !now.isBefore(viewer.readyDeadline);
            }
            if (idle) closeViewer(viewer, ViewerState.CLOSED, "Remote-control viewer expired");
            if (!viewer.active && viewer.closedAt != null && now.isAfter(viewer.closedAt.plusSeconds(3600))) {
                sessions.remove(viewer.sessionId, viewer);
            }
        }
    }

    private void refreshPending(StreamEntry stream) {
        synchronized (stream) {
            if (stream.state != StreamState.PENDING) return;
            if (!clock.instant().isBefore(stream.commandDeadline)) {
                failStream(stream, "Remote-control start command timed out");
                return;
            }
            if (stream.taskId == null) return;
            CommandQueue task = commandQueueRepository.findById(stream.taskId)
                    .orElseThrow(() -> new ResourceNotFoundException("taskId not found: " + stream.taskId));
            String status = normalize(task.getStatus());
            if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                failStream(stream, task.getStderr() == null || task.getStderr().isBlank()
                        ? "Remote-control start command failed" : task.getStderr());
                return;
            }
            if ("COMPLETED".equals(status)) {
                try {
                    PcMaster pc = requireOnlinePc(stream.macAddress);
                    applyValidatedResult(stream, validateCompletedStartTask(stream, task, pc));
                } catch (ApplicationException exception) {
                    failStream(stream, exception.getMessage());
                }
            }
        }
    }

    private void refreshExpiredTicket(ViewerEntry viewer) {
        synchronized (viewer) {
            if (viewer.active
                    && viewer.state == ViewerState.TICKET_ISSUED
                    && viewer.ticketExpiresAt != null
                    && !clock.instant().isBefore(viewer.ticketExpiresAt)) {
                viewer.ticketHash = null;
                viewer.ticketExpiresAt = null;
                viewer.state = ViewerState.OPEN;
            }
        }
    }

    private CommandQueue requireCompletedStartTask(StreamEntry stream, Long taskId, PcMaster pc) {
        CommandQueue task = commandQueueRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("taskId not found: " + taskId));
        return validateCompletedStartTask(stream, task, pc);
    }

    private CommandQueue validateCompletedStartTask(StreamEntry stream, CommandQueue task, PcMaster pc) {
        if (!START.equalsIgnoreCase(task.getCommandType())) {
            throw new InvalidRequestException("taskId is not a REMOTE_CONTROL_START command");
        }
        if (!stream.canonicalMac.equals(canonicalMac(task.getMacAddress()))
                || !stream.canonicalMac.equals(canonicalMac(pc.getMacAddress()))) {
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

    private void applyValidatedResult(StreamEntry stream, CommandQueue task) {
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
            PcMaster pc = requireOnlinePc(stream.macAddress);
            InetAddress address = validateDestination(pc.getIpAddress());
            stream.targetAddress = address;
            stream.targetPort = port;
            stream.oneTimePassword = password;
            stream.readyDeadline = min(clock.instant().plus(properties.getReadyTtl()), stream.expiresAt);
            stream.state = StreamState.READY;
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

    private long enqueue(String macAddress, String type, UUID streamId, String requestedBy, String requestedIp) {
        CommandRequestDto request = new CommandRequestDto();
        request.setScope("SINGLE");
        request.setMacAddress(macAddress);
        request.setType(type);
        request.setPayloadJson("{\"sessionId\":\"" + streamId + "\"}");
        Map<String, Object> response = commandLifecycleService.enqueue(request);
        Object taskIds = response.get("taskIds");
        if (!(taskIds instanceof List<?> ids) || ids.size() != 1 || !(ids.getFirst() instanceof Number taskId)) {
            throw new ConflictException("Remote-control command could not be queued");
        }
        long id = taskId.longValue();
        commandQueueRepository.findById(id).ifPresent(command -> {
            command.setRequestedBy(requestedBy == null || requestedBy.isBlank() ? "system" : requestedBy);
            command.setRequestedIp(requestedIp);
            commandQueueRepository.save(command);
        });
        return id;
    }

    private boolean closeViewer(ViewerEntry viewer, ViewerState state, String reason) {
        synchronized (viewer) {
            if (!viewer.active) return false;
            viewer.active = false;
            viewer.state = state;
            viewer.failureReason = reason;
            viewer.closedAt = clock.instant();
            viewer.ticketHash = null;
            viewer.ticketExpiresAt = null;
        }
        relayRegistry.close(viewer.sessionId);
        synchronized (registryLock) {
            StreamEntry stream = viewer.stream;
            stream.viewerIds.remove(viewer.sessionId);
            if (stream.viewerIds.isEmpty() && isActive(stream.state)) {
                stream.noViewerDeadline = clock.instant().plus(properties.getViewerGraceTtl());
            }
        }
        return true;
    }

    private void failViewer(ViewerEntry viewer, String reason) {
        closeViewer(viewer, ViewerState.FAILED, reason);
    }

    private void closeStream(StreamEntry stream, String reason) {
        finishStream(stream, StreamState.CLOSED, ViewerState.CLOSED, reason);
    }

    private void failStream(StreamEntry stream, String reason) {
        finishStream(stream, StreamState.FAILED, ViewerState.FAILED,
                reason == null || reason.isBlank() ? "Remote-control stream failed" : reason);
    }

    private void finishStream(StreamEntry stream, StreamState streamState, ViewerState viewerState, String reason) {
        boolean changed;
        synchronized (stream) {
            changed = isActive(stream.state);
            if (changed) {
                stream.state = streamState;
                stream.failureReason = reason;
                stream.closedAt = clock.instant();
                stream.oneTimePassword = null;
                stream.noViewerDeadline = null;
            }
        }
        if (!changed) {
            tryIssueStop(stream);
            return;
        }
        activeStreamByMac.remove(stream.canonicalMac, stream.streamId);
        List<UUID> viewerIds;
        synchronized (registryLock) {
            viewerIds = new ArrayList<>(stream.viewerIds);
        }
        for (UUID viewerId : viewerIds) {
            ViewerEntry viewer = sessions.get(viewerId);
            if (viewer != null) closeViewer(viewer, viewerState, reason);
        }
        tryIssueStop(stream);
    }

    private boolean hasConnectedViewer(StreamEntry stream) {
        List<UUID> viewerIds;
        synchronized (registryLock) {
            viewerIds = new ArrayList<>(stream.viewerIds);
        }
        for (UUID viewerId : viewerIds) {
            ViewerEntry viewer = sessions.get(viewerId);
            if (viewer == null) continue;
            synchronized (viewer) {
                if (viewer.active && viewer.state == ViewerState.CONNECTED) return true;
            }
        }
        return false;
    }

    private void tryIssueStop(StreamEntry stream) {
        synchronized (stream) {
            if (stream.stopIssued) return;
            try {
                enqueue(stream.macAddress, STOP, stream.streamId, stream.startedBy, stream.startedFromIp);
                stream.stopIssued = true;
            } catch (RuntimeException exception) {
                log.warn("Failed to queue remote-control stop for stream {} (will retry): {}",
                        stream.streamId, exception.getMessage());
            }
        }
    }

    private ViewerEntry requireOwnedSession(UUID sessionId, String owner) {
        ViewerEntry viewer = requireSession(sessionId);
        if (owner == null || !owner.equals(viewer.owner)) {
            throw new ApplicationException("FORBIDDEN", "Remote-control session owner mismatch", 403);
        }
        return viewer;
    }

    private ViewerEntry requireSession(UUID sessionId) {
        ViewerEntry viewer = sessions.get(sessionId);
        if (viewer == null) {
            throw new ResourceNotFoundException("Remote-control session not found: " + sessionId);
        }
        return viewer;
    }

    private RemoteControlSessionView toView(ViewerEntry viewer) {
        ViewerState viewerState;
        boolean active;
        String viewerFailure;
        synchronized (viewer) {
            viewerState = viewer.state;
            active = viewer.active;
            viewerFailure = viewer.failureReason;
        }
        StreamEntry stream = viewer.stream;
        StreamState streamState;
        String streamFailure;
        synchronized (stream) {
            streamState = stream.state;
            streamFailure = stream.failureReason;
        }

        String status;
        if (!active) {
            status = viewerState.name();
        } else if (streamState == StreamState.PENDING) {
            status = "PENDING";
        } else if (streamState == StreamState.CLOSED || streamState == StreamState.FAILED) {
            status = streamState.name();
        } else {
            status = viewerState == ViewerState.OPEN ? "READY" : viewerState.name();
        }
        return new RemoteControlSessionView(
                viewer.sessionId,
                stream.taskId,
                stream.macAddress,
                status,
                stream.expiresAt,
                viewerFailure == null ? streamFailure : viewerFailure);
    }

    private int activeViewerCount() {
        return activeStreamByMac.values().stream()
                .map(streams::get)
                .filter(java.util.Objects::nonNull)
                .mapToInt(stream -> stream.viewerIds.size())
                .sum();
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

    private String sanitize(String value, int maxLength) {
        if (value == null) return null;
        String sanitized = value.replaceAll("[\\r\\n\\u0000]", " ").trim();
        if (sanitized.isBlank()) return null;
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }

    private Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private boolean isActive(StreamState state) {
        return state == StreamState.PENDING || state == StreamState.READY || state == StreamState.CONNECTED;
    }

    private enum StreamState {
        PENDING,
        READY,
        CONNECTED,
        CLOSED,
        FAILED
    }

    private enum ViewerState {
        OPEN,
        TICKET_ISSUED,
        CONNECTING,
        CONNECTED,
        CLOSED,
        FAILED
    }

    private static final class StreamEntry {
        private final UUID streamId;
        private final String macAddress;
        private final String canonicalMac;
        private final String pcName;
        private final String targetIp;
        private final String startedBy;
        private final String startedFromIp;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final Instant commandDeadline;
        private final LinkedHashSet<UUID> viewerIds = new LinkedHashSet<>();
        private volatile StreamState state = StreamState.PENDING;
        private volatile Instant noViewerDeadline;
        private Long taskId;
        private InetAddress targetAddress;
        private int targetPort;
        private String oneTimePassword;
        private Instant readyDeadline;
        private String failureReason;
        private Instant closedAt;
        private boolean stopIssued;

        private StreamEntry(UUID streamId, String macAddress, String canonicalMac,
                            String pcName, String targetIp, String startedBy, String startedFromIp,
                            Instant createdAt, Instant expiresAt, Instant commandDeadline) {
            this.streamId = streamId;
            this.macAddress = macAddress;
            this.canonicalMac = canonicalMac;
            this.pcName = pcName;
            this.targetIp = targetIp;
            this.startedBy = startedBy;
            this.startedFromIp = startedFromIp;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.commandDeadline = commandDeadline;
        }
    }

    private static final class ViewerEntry {
        private final UUID sessionId;
        private final String owner;
        private final String sourceIp;
        private final String userAgent;
        private final StreamEntry stream;
        private final Instant createdAt;
        private final Instant readyDeadline;
        private volatile boolean active = true;
        private ViewerState state = ViewerState.OPEN;
        private byte[] ticketHash;
        private Instant ticketExpiresAt;
        private String failureReason;
        private Instant closedAt;

        private ViewerEntry(UUID sessionId, String owner, String sourceIp, String userAgent,
                            StreamEntry stream,
                            Instant createdAt, Instant readyDeadline) {
            this.sessionId = sessionId;
            this.owner = owner;
            this.sourceIp = sourceIp;
            this.userAgent = userAgent;
            this.stream = stream;
            this.createdAt = createdAt;
            this.readyDeadline = readyDeadline;
        }
    }
}
