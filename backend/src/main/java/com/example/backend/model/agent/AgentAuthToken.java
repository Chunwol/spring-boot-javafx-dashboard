package com.example.backend.model.agent;

import com.example.backend.common.persistence.LenientLocalDateTimeStringConverter;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_auth_tokens")
public class AgentAuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mac_address", nullable = false, unique = true)
    private String macAddress;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime issuedAt;

    @Column(name = "last_used_at")
    @Convert(converter = LenientLocalDateTimeStringConverter.class)
    private LocalDateTime lastUsedAt;

    @Column
    private boolean revoked;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(LocalDateTime issuedAt) {
        this.issuedAt = issuedAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }
}
