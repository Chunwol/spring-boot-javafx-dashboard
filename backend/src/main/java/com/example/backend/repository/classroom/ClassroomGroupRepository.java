package com.example.backend.repository.classroom;

import com.example.backend.model.classroom.ClassroomGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClassroomGroupRepository extends JpaRepository<ClassroomGroup, Long> {
    ClassroomGroup findByName(String name);
}
