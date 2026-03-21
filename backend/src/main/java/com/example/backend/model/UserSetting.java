package com.example.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "user_settings")
public class UserSetting {

    @Id
    @Column(nullable = false)
    private String key;

    @Column(nullable = false)
    private String value = "";

    public UserSetting() {}

    public UserSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey()              { return key; }
    public void   setKey(String key)    { this.key = key; }
    public String getValue()            { return value; }
    public void   setValue(String value){ this.value = value; }
}
