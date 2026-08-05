package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.NotificationConnectionTestResponse;
import com.tengencorp.tengen.dto.NotificationDestinationRequest;
import com.tengencorp.tengen.dto.NotificationDestinationResponse;
import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationDestination;
import com.tengencorp.tengen.repository.NotificationDestinationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Admin lifecycle for reusable email and SMS provider connections. */
@Service
public class NotificationDestinationService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final NotificationDestinationRepository repository;
    private final NotificationSecretService secretService;
    private final NotificationProviderService providerService;

    public NotificationDestinationService(NotificationDestinationRepository repository,
                                           NotificationSecretService secretService,
                                           NotificationProviderService providerService) {
        this.repository = repository;
        this.secretService = secretService;
        this.providerService = providerService;
    }

    @Transactional(readOnly = true)
    public List<NotificationDestinationResponse> list(NotificationChannel channel) {
        List<NotificationDestination> destinations = channel == null
            ? repository.findAllByOrderByDisplayNameAsc()
            : repository.findByChannelAndEnabledTrueOrderByDisplayNameAsc(channel);
        return destinations.stream().map(NotificationDestinationResponse::from).toList();
    }

    @Transactional
    public NotificationDestinationResponse create(NotificationDestinationRequest request) {
        NotificationChannel channel = request.channel();
        String provider = normalizeProvider(request.provider());
        Map<String, Object> configuration = copyConfiguration(request.configuration());
        Map<String, String> credentials = copyCredentials(request.credentials());
        validate(channel, provider, configuration, credentials);

        NotificationDestination destination = new NotificationDestination();
        destination.setDestinationKey(UUID.randomUUID().toString());
        destination.setDisplayName(request.displayName().trim());
        destination.setChannel(channel);
        destination.setProvider(provider);
        destination.setConfiguration(configuration);
        destination.setEnabled(request.enabled());
        NotificationSecretService.EncryptedSecret secret = secretService.encrypt(
            destination.getDestinationKey(), credentials);
        destination.setCredentialCiphertext(secret.ciphertext());
        destination.setCredentialNonce(secret.nonce());
        destination.setEncryptionKeyVersion(secret.keyVersion());
        return NotificationDestinationResponse.from(repository.save(destination));
    }

    @Transactional
    public NotificationConnectionTestResponse test(Long id) {
        NotificationDestination destination = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Notification destination " + id + " not found"));
        NotificationProviderResult result = providerService.test(destination);
        Instant testedAt = Instant.now();
        destination.setLastTestedAt(testedAt);
        destination.setLastTestSucceeded(result.successful());
        destination.setLastTestErrorCategory(result.successful() ? null : result.category());
        repository.save(destination);
        return new NotificationConnectionTestResponse(
            result.successful(),
            result.successful() ? "SUCCESS" : result.category(),
            result.successful() ? "Provider connection test succeeded" : result.error(),
            testedAt);
    }

    @Transactional(readOnly = true)
    public NotificationDestination findEnabled(Long id, NotificationChannel channel) {
        NotificationDestination destination = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Notification destination " + id + " not found"));
        if (!destination.isEnabled()) {
            throw new IllegalArgumentException("Notification destination is disabled");
        }
        if (destination.getChannel() != channel) {
            throw new IllegalArgumentException("Notification destination channel does not match the rule action");
        }
        return destination;
    }

    private void validate(NotificationChannel channel, String provider,
                          Map<String, Object> configuration,
                          Map<String, String> credentials) {
        if (channel == NotificationChannel.EMAIL) {
            if (!(provider.equals("SMTP") || provider.equals("AMAZON_SES_SMTP"))) {
                throw new IllegalArgumentException("Email provider must be SMTP or AMAZON_SES_SMTP");
            }
            required(configuration, "host");
            required(configuration, "fromAddress");
            integer(configuration, "port", 587);
            String tlsMode = stringValue(configuration.getOrDefault("tlsMode", "STARTTLS"));
            if (!(tlsMode.equalsIgnoreCase("NONE") || tlsMode.equalsIgnoreCase("STARTTLS")
                    || tlsMode.equalsIgnoreCase("SSL"))) {
                throw new IllegalArgumentException("Email tlsMode must be NONE, STARTTLS, or SSL");
            }
            required(credentials, "username");
            required(credentials, "password");
            return;
        }

        if (!provider.equals("TWILIO")) {
            throw new IllegalArgumentException("SMS provider must be TWILIO");
        }
        String fromNumber = required(configuration, "fromNumber");
        if (!E164.matcher(fromNumber).matches()) {
            throw new IllegalArgumentException("SMS fromNumber must use E.164 format");
        }
        required(credentials, "accountSid");
        required(credentials, "authToken");
    }

    private Map<String, Object> copyConfiguration(Map<String, Object> configuration) {
        return configuration == null ? new LinkedHashMap<>() : new LinkedHashMap<>(configuration);
    }

    private Map<String, String> copyCredentials(Map<String, String> credentials) {
        return credentials == null ? Map.of() : new LinkedHashMap<>(credentials);
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
    }

    private String required(Map<String, ?> values, String key) {
        String value = stringValue(values.get(key));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider setting is required: " + key);
        }
        return value.trim();
    }

    private int integer(Map<String, ?> values, String key, int fallback) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            if (parsed < 1 || parsed > 65_535) {
                throw new IllegalArgumentException("Provider setting must be a valid port: " + key);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Provider setting must be a valid integer: " + key);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
