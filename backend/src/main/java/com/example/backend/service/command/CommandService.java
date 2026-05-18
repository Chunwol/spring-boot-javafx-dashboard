package com.example.backend.service.command;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.status.OperationalMetricsService;
import com.example.backend.util.IpRangeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 에이전트 명령 큐(CommandQueue) 관리 서비스.
 * <p>
 * 명령 등록(enqueue), 조회(getPendingCommands), 전달 완료 마킹(markCommandDelivered),
 * Wake-on-LAN 릴레이 명령 발행(sendGroupWol) 기능을 제공한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class CommandService {

    private final CommandQueueRepository commandQueueRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomIpRangeRepository classroomIpRangeRepository;
    private final OperationalMetricsService operationalMetricsService;

    /**
     * 새 명령을 QUEUED 상태로 큐에 등록한다.
     *
     * @param request 명령 요청 DTO (macAddress, type, scriptUrl, payloadJson)
     */
    @Transactional
    public void enqueueCommand(CommandRequestDto request) {
        CommandQueue command = new CommandQueue();
        command.setMacAddress(request.getMacAddress());
        command.setCommandType(request.getType());
        command.setScriptUrl(request.getScriptUrl());
        command.setPayloadJson(request.getPayloadJson());
        command.setStatus("QUEUED");
        command.setCreatedAt(LocalDateTime.now());
        commandQueueRepository.save(command);
    }

    /**
     * 특정 에이전트의 대기 중인 명령 목록을 반환한다.
     * QUEUED, DELIVERED, ACKED 상태인 명령을 생성 시각 오름차순으로 반환한다.
     *
     * @param macAddress 에이전트 MAC 주소
     * @return 대기 명령 리스트 (없으면 빈 리스트)
     */
    @Transactional(readOnly = true)
    public List<CommandQueue> getPendingCommands(String macAddress) {
        return commandQueueRepository.findByMacAddressAndStatusInOrderByCreatedAtAsc(
                macAddress,
                List.of("QUEUED", "DELIVERED", "ACKED"));
    }

    /**
     * 명령을 DELIVERED 상태로 전환하고 전달 시각을 기록한다.
     * 명령 발급 시각이 있으면 디스패치 지연 시간을 운영 메트릭에 기록한다.
     *
     * @param commandId 대상 명령 ID
     */
    @Transactional
    public void markCommandDelivered(Long commandId) {
        CommandQueue command = commandQueueRepository.findById(commandId).orElse(null);
        if (command != null) {
            LocalDateTime deliveredAt = LocalDateTime.now();
            command.setStatus("DELIVERED");
            command.setDeliveredAt(deliveredAt);
            commandQueueRepository.save(command);
            if (command.getCreatedAt() != null) {
                long dispatchLatencyMs = Duration.between(command.getCreatedAt(), deliveredAt).toMillis();
                operationalMetricsService.recordCommandDispatchLatencyMillis(dispatchLatencyMs);
            }
        }
    }

    /**
     * 그룹 내 온라인 PC 1대를 릴레이로 사용해 오프라인 PC에 Wake-on-LAN 매직 패킷을 전송한다.
     * 온라인 PC가 없으면 false를 반환한다.
     *
     * @param groupId 대상 그룹 ID
     * @return 명령 등록 성공 여부
     */
    @Transactional
    public boolean sendGroupWol(Long groupId) {
        // 릴레이 역할: 그룹 내 온라인 PC 1대 (WoL 매직 패킷 발신 대리)
        var relays = pcMasterRepository.findByGroupIdAndStatus(groupId, "ONLINE");
        if (relays.isEmpty()) {
            return false;
        }

        // 깨울 대상: 그룹 내 오프라인 PC들의 MAC 주소 (17자 형식만 포함)
        String targetMacs = pcMasterRepository.findByGroupIdAndStatus(groupId, "OFFLINE")
                .stream()
                .map(pc -> pc.getMacAddress())
                .filter(mac -> mac != null && mac.length() == 17)
                .collect(Collectors.joining(","));

        // 서브넷 브로드캐스트 IP: CIDR → 계산값, 없으면 릴레이 IP /24 추정, 최후 255.255.255.255
        String broadcastIp = resolveBroadcastIp(groupId, relays.get(0).getIpAddress());

        // JSON 직렬화 (IP·MAC 값은 사전 검증된 형식만 통과)
        String payloadJson = String.format(
                "{\"broadcastIp\":\"%s\",\"targetMacs\":\"%s\"}", broadcastIp, targetMacs);

        CommandQueue command = new CommandQueue();
        command.setMacAddress(relays.get(0).getMacAddress());
        command.setCommandType("WOL_RELAY");
        command.setPayloadJson(payloadJson);
        command.setStatus("QUEUED");
        command.setCreatedAt(LocalDateTime.now());
        commandQueueRepository.save(command);
        return true;
    }

    /**
     * 그룹 IP 범위의 CIDR에서 서브넷 브로드캐스트 IP를 계산한다.
     * CIDR 없으면 릴레이 PC IP 기준 /24 서브넷 브로드캐스트로 추정한다.
     */
    private String resolveBroadcastIp(Long groupId, String relayIp) {
        for (var range : classroomIpRangeRepository.findByGroupId(groupId)) {
            String broadcast = IpRangeUtil.broadcastFromCidr(range.getCidr());
            if (broadcast != null) return broadcast;
        }
        // /24 추정: 릴레이 PC IP의 마지막 옥텟을 255로
        if (relayIp != null && IpRangeUtil.isIpv4(relayIp)) {
            String[] p = relayIp.split("\\.");
            return p[0] + "." + p[1] + "." + p[2] + ".255";
        }
        return "255.255.255.255";
    }
}
