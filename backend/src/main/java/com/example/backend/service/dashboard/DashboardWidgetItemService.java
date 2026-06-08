package com.example.backend.service.dashboard;

import com.example.backend.model.dashboard.DashboardWidgetItem;
import com.example.backend.repository.dashboard.DashboardWidgetItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardWidgetItemService {

    private final DashboardWidgetItemRepository widgetItemRepository;

    public List<DashboardWidgetItem> findAll() {
        return widgetItemRepository.findAll();
    }

    public Optional<DashboardWidgetItem> findById(Long id) {
        return widgetItemRepository.findById(id);
    }

    public boolean existsById(Long id) {
        return widgetItemRepository.existsById(id);
    }

    public List<DashboardWidgetItem> findByType(String type, Long dashboardId) {
        if (dashboardId != null && dashboardId > 0) {
            return widgetItemRepository.findByTypeIgnoreCaseAndDashboardId(type, dashboardId);
        }
        return widgetItemRepository.findByTypeIgnoreCase(type);
    }

    @Transactional
    public DashboardWidgetItem save(DashboardWidgetItem item) {
        return widgetItemRepository.save(item);
    }

    @Transactional
    public void deleteById(Long id) {
        widgetItemRepository.deleteById(id);
    }
}
