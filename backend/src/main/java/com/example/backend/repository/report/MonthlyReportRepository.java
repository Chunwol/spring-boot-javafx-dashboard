package com.example.backend.repository.report;

import com.example.backend.model.report.MonthlyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, Long> {
    Optional<MonthlyReport> findByYearMonth(String yearMonth);
    List<MonthlyReport> findAllByOrderByYearMonthDesc();
}
