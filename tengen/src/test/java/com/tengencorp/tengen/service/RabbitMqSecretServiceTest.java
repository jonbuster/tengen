package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.exception.RabbitMqConnectorException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RabbitMqSecretServiceTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsAndDecryptsWithoutReturningPlaintextMaterial() {
        RabbitMqSecretService service = new RabbitMqSecretService(MASTER_KEY);
        RabbitMqSecretService.EncryptedSecret first = service.encrypt("rabbitmq-primary", "broker-password");
        RabbitMqSecretService.EncryptedSecret second = service.encrypt("rabbitmq-primary", "broker-password");

        RabbitMqConnector connector = new RabbitMqConnector();
        connector.setConnectorKey("rabbitmq-primary");
        connector.setPasswordCiphertext(first.ciphertext());
        connector.setPasswordNonce(first.nonce());
        connector.setEncryptionKeyVersion(first.keyVersion());

        assertEquals("broker-password", service.decrypt(connector));
        assertNotEquals(Base64.getEncoder().encodeToString(first.nonce()),
            Base64.getEncoder().encodeToString(second.nonce()));
    }

    @Test
    void rejectsTamperedCiphertext() {
        RabbitMqSecretService service = new RabbitMqSecretService(MASTER_KEY);
        RabbitMqSecretService.EncryptedSecret encrypted = service.encrypt("rabbitmq-primary", "secret");
        encrypted.ciphertext()[0] ^= 1;

        RabbitMqConnector connector = new RabbitMqConnector();
        connector.setConnectorKey("rabbitmq-primary");
        connector.setPasswordCiphertext(encrypted.ciphertext());
        connector.setPasswordNonce(encrypted.nonce());
        connector.setEncryptionKeyVersion(encrypted.keyVersion());

        RabbitMqConnectorException error = assertThrows(RabbitMqConnectorException.class,
            () -> service.decrypt(connector));
        assertEquals("PASSWORD_DECRYPTION_FAILED", error.category());
    }
}
