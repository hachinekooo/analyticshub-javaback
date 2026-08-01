package com.github.analyticshub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.security.project-credentials")
public class ProjectCredentialEncryptionProperties {

    private String encryptionKey = "";
    private String previousEncryptionKey = "";

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getPreviousEncryptionKey() {
        return previousEncryptionKey;
    }

    public void setPreviousEncryptionKey(String previousEncryptionKey) {
        this.previousEncryptionKey = previousEncryptionKey;
    }
}
