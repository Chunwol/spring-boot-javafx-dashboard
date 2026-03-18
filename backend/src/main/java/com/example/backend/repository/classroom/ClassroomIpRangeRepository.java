package com.example.backend.repository.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClassroomIpRangeRepository extends JpaRepository<ClassroomIpRange, Long> {
    List<ClassroomIpRange> findByGroupId(Long groupId);
}

