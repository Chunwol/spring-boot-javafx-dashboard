package com.example.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 트래픽 임계값 설정을 관리하는 컴포넌트
 * .env 파일에서 값을 읽어 사용합니다.
 */
@Component
public class TrafficThresholdConfig {

    @Value("${TRAFFIC_ALERT_THRESHOLD_GLOBAL_MBPS:5000}")
    private double globalThresholdMbps;

    @Value("${TRAFFIC_ALERT_THRESHOLD_CLASSROOM_MBPS:1000}")
    private double classroomThresholdMbps;

    @Value("${TRAFFIC_ALERT_THRESHOLD_PC_MBPS:300}")
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
