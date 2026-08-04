package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.exception.RabbitMqConnectorException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM storage for the connector password. */
@Service
public class RabbitMqSecretService {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_VERSION = 1;

    private final String encodedMasterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public RabbitMqSecretService(
            @Value("${tengen.connector.master-key:}") String encodedMasterKey) {
        this.encodedMasterKey = encodedMasterKey == null ? "" : encodedMasterKey.trim();
    }

    public boolean isConfigured() {
        return !encodedMasterKey.isBlank();
    }

    public EncryptedSecret encrypt(String connectorKey, String password) {
        if (password == null || password.isBlank()) {
            throw new RabbitMqConnectorException("PASSWORD_REQUIRED", "A RabbitMQ password is required");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(connectorKey));
            return new EncryptedSecret(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)), nonce, KEY_VERSION);
        } catch (GeneralSecurityException exception) {
            throw new RabbitMqConnectorException("SECRET_ENCRYPTION_FAILED",
                "The connector password could not be encrypted", exception);
        }
    }

    public String decrypt(RabbitMqConnector connector) {
        if (connector.getPasswordCiphertext() == null || connector.getPasswordNonce() == null) {
            throw new RabbitMqConnectorException("PASSWORD_NOT_CONFIGURED",
                "A RabbitMQ password has not been configured");
        }
        if (connector.getEncryptionKeyVersion() == null
                || connector.getEncryptionKeyVersion() != KEY_VERSION) {
            throw new RabbitMqConnectorException("PASSWORD_KEY_VERSION_UNSUPPORTED",
                "The saved RabbitMQ password uses an unsupported key version");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(TAG_BITS, connector.getPasswordNonce()));
            cipher.updateAAD(aad(connector.getConnectorKey()));
            return new String(cipher.doFinal(connector.getPasswordCiphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new RabbitMqConnectorException("PASSWORD_DECRYPTION_FAILED",
                "The saved RabbitMQ password could not be decrypted", exception);
        }
    }

    private SecretKeySpec key() {
        if (encodedMasterKey.isBlank()) {
            throw new RabbitMqConnectorException("MASTER_KEY_MISSING",
                "The connector encryption master key is not configured");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedMasterKey);
        } catch (IllegalArgumentException exception) {
            throw new RabbitMqConnectorException("MASTER_KEY_INVALID",
                "The connector encryption master key is invalid", exception);
        }
        if (decoded.length != KEY_BYTES) {
            throw new RabbitMqConnectorException("MASTER_KEY_INVALID",
                "The connector encryption master key is invalid");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private byte[] aad(String connectorKey) {
        return (connectorKey == null ? RabbitMqConnector.DEFAULT_CONNECTOR_KEY : connectorKey)
            .getBytes(StandardCharsets.UTF_8);
    }

    public record EncryptedSecret(byte[] ciphertext, byte[] nonce, int keyVersion) {
    }
}
