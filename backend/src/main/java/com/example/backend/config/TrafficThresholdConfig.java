package com.example.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 트래픽 이상 감지(Alert) 임계값 설정 컴포넌트.
 *
 * <p>세 가지 범위의 인바운드 트래픽(Mbps) 임계값을 관리한다.
 * 임계값을 초과하면 AlertService가 알림 이벤트를 발행한다.
 *
 * <p>값 출처: {@code .env} 또는 OS 환경 변수 (EnvConfig에 의해 Spring Environment에 주입됨)
 *
 * <p>설정 키:
 * <pre>
 * TRAFFIC_ALERT_THRESHOLD_GLOBAL_MBPS     (기본 2000 Mbps, 전체 네트워크 임계값)
 * TRAFFIC_ALERT_THRESHOLD_CLASSROOM_MBPS  (기본 200 Mbps, 강의실 단위 임계값)
 * TRAFFIC_ALERT_THRESHOLD_PC_MBPS         (기본 50 Mbps, 단일 PC 임계값)
 * </pre>
 */
@Component
public class TrafficThresholdConfig {

    /** 전체 네트워크 인바운드 트래픽 경보 임계값(Mbps). 이 값 초과 시 GLOBAL 레벨 알림 발행. */
    @Value("${TRAFFIC_ALERT_THRESHOLD_GLOBAL_MBPS:2000}")
    private double globalThresholdMbps;

    /** 강의실 단위 인바운드 트래픽 경보 임계값(Mbps). 이 값 초과 시 CLASSROOM 레벨 알림 발행. */
    @Value("${TRAFFIC_ALERT_THRESHOLD_CLASSROOM_MBPS:200}")
    private double classroomThresholdMbps;

    /** 단일 PC 인바운드 트래픽 경보 임계값(Mbps). 이 값 초과 시 PC 레벨 알림 발행. */
    @Value("${TRAFFIC_ALERT_THRESHOLD_PC_MBPS:50}")
    private double pcThresholdMbps;

    // Getters
    public double getGlobalThresholdMbps() {
        return globalThresholdMbps;
    }

    public double getClassroomThresholdMbps() {
        return classroomThresholdMbps;
    }

    public double getPcThresholdMbps() {
        return pcThresholdMbps;
    }

    @Override
    public String toString() {
        return "TrafficThresholdConfig{" +
                "globalThresholdMbps=" + globalThresholdMbps +
                ", classroomThresholdMbps=" + classroomThresholdMbps +
                ", pcThresholdMbps=" + pcThresholdMbps +
                '}';
    }
}
