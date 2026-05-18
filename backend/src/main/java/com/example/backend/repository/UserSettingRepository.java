package com.example.backend.repository;

import com.example.backend.model.UserSetting;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * UserSetting 엔티티 Repository.
 * 시스템 환경 설정(키-값 쌍)의 CRUD를 제공한다.
 * 설정 키(String)가 PK이다.
 */
public interface UserSettingRepository extends JpaRepository<UserSetting, String> {}
