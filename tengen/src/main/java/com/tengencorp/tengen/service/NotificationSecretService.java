package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.NotificationDestination;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/** AES-GCM storage for provider credentials using the existing connector master key. */
@Service
public class NotificationSecretService {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_VERSION = 1;

    private final String encodedMasterKey;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public NotificationSecretService(
            @Value("${tengen.connector.master-key:}") String encodedMasterKey,
            ObjectMapper objectMapper) {
        this.encodedMasterKey = encodedMasterKey == null ? "" : encodedMasterKey.trim();
        this.objectMapper = objectMapper;
    }

    public EncryptedSecret encrypt(String destinationKey, Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            throw new IllegalArgumentException("Provider credentials are required");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(destinationKey));
            byte[] plaintext = objectMapper.writeValueAsString(credentials)
                .getBytes(StandardCharsets.UTF_8);
            return new EncryptedSecret(cipher.doFinal(plaintext), nonce, KEY_VERSION);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Provider credentials could not be encrypted", exception);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Provider credentials could not be serialized", exception);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> decrypt(NotificationDestination destination) {
        if (!destination.hasCredentials()) {
            throw new IllegalArgumentException("Provider credentials are not configured");
        }
        if (destination.getEncryptionKeyVersion() != KEY_VERSION) {
            throw new IllegalArgumentException("Provider credentials use an unsupported key version");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS,
                destination.getCredentialNonce()));
            cipher.updateAAD(aad(destination.getDestinationKey()));
            String plaintext = new String(
                cipher.doFinal(destination.getCredentialCiphertext()), StandardCharsets.UTF_8);
            return objectMapper.readValue(plaintext, Map.class);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Provider credentials could not be decrypted", exception);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Provider credentials could not be parsed", exception);
        }
    }

    private SecretKeySpec key() {
        if (encodedMasterKey.isBlank()) {
            throw new IllegalArgumentException(
                "TENGEN_CONNECTOR_MASTER_KEY must be configured before saving provider credentials");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedMasterKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("TENGEN_CONNECTOR_MASTER_KEY is invalid", exception);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException("TENGEN_CONNECTOR_MASTER_KEY must decode to 32 bytes");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private byte[] aad(String destinationKey) {
        return destinationKey.getBytes(StandardCharsets.UTF_8);
    }

    public record EncryptedSecret(byte[] ciphertext, byte[] nonce, int keyVersion) {
    }
}
