package com.example.backend.repository.alert;

import com.example.backend.model.alert.OperationalAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationalAlertRepository extends JpaRepository<OperationalAlert, Long> {

    Page<OperationalAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<OperationalAlert> findByAcknowledgedFalseOrderByCreatedAtDesc(Pageable pageable);

    long countByAcknowledgedFalse();
}
