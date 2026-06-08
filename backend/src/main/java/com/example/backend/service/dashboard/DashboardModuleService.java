package com.example.backend.service.dashboard;

import com.example.backend.model.dashboard.DashboardModule;
import com.example.backend.repository.dashboard.DashboardModuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardModuleService {

    private final DashboardModuleRepository moduleRepository;

    public List<DashboardModule> findAll() {
        return moduleRepository.findAll();
    }

    public List<DashboardModule> findByGroupId(Long groupId) {
        return moduleRepository.findByGroupId(groupId);
    }

    @Transactional
    public DashboardModule save(DashboardModule m) {
        return moduleRepository.save(m);
    }

    @Transactional
    public Optional<DashboardModule> update(Long id, DashboardModule m) {
        return moduleRepository.findById(id).map(existing -> {
            existing.setType(m.getType());
            existing.setConfig(m.getConfig());
            existing.setX(m.getX());
            existing.setY(m.getY());
            existing.setGroupId(m.getGroupId());
            return moduleRepository.save(existing);
        });
    }

    @Transactional
    public boolean delete(Long id) {
        if (!moduleRepository.existsById(id)) {
            return false;
        }
        moduleRepository.deleteById(id);
        return true;
    }
}
