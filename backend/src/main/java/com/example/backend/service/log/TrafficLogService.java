package com.example.backend.service.log;

import com.example.backend.model.traffic.TrafficLog;
import com.example.backend.repository.traffic.TrafficLogsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrafficLogService {

    private final TrafficLogsRepository trafficLogsRepository;

    public Page<TrafficLog> findAllByOrderByTimestampDesc(Pageable pageable) {
        return trafficLogsRepository.findAllByOrderByTimestampDesc(pageable);
    }
}
