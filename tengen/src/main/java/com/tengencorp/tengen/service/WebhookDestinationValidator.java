package com.tengencorp.tengen.service;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Enforces the public-HTTPS-only callback policy. */
@Component
public class WebhookDestinationValidator {

    public URI validateSyntax(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalArgumentException("Callback URL is required for webhook rules");
        }
        final URI uri;
        try {
            uri = URI.create(callbackUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Callback URL is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Callback URL must use HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Callback URL must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Callback URL must not contain credentials");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("Callback URL must not contain a fragment");
        }
        if (uri.getPort() == 0) {
            throw new IllegalArgumentException("Callback URL contains an invalid port");
        }
        if ("localhost".equals(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Callback URL must use a public host");
        }
        if (isIpLiteral(uri.getHost())) {
            try {
                if (!isPublic(InetAddress.getByName(uri.getHost()))) {
                    throw new IllegalArgumentException("Callback URL must use a public address");
                }
            } catch (java.net.UnknownHostException exception) {
                throw new IllegalArgumentException("Callback URL contains an invalid IP address");
            }
        }
        return uri;
    }

    private boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    public URI validateForDelivery(String callbackUrl) {
        URI uri = validateSyntax(callbackUrl);
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (Exception exception) {
            throw new DestinationResolutionException("Callback host could not be resolved", exception);
        }
        if (addresses.length == 0) {
            throw new IllegalArgumentException("Callback host did not resolve to an address");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IllegalArgumentException("Callback host resolves to a non-public address");
            }
        }
        return uri;
    }

    public static class DestinationResolutionException extends RuntimeException {
        public DestinationResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            return a != 0 && a != 10 && a != 127
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && b == 168)
                && !(a == 192 && b == 0)
                && !(a == 192 && b == 2)
                && !(a == 198 && (b == 18 || b == 19))
                && !(a == 198 && b == 51 && Byte.toUnsignedInt(bytes[2]) == 100)
                && !(a == 203 && b == 0 && Byte.toUnsignedInt(bytes[2]) == 113)
                && a < 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return (first & 0xfe) != 0xfc
                && !(first == 0xfe && (second & 0xc0) == 0x80)
                && !isIpv6Prefix(bytes, 0x20, 0x01, 0x0d, 0xb8)
                && !isIpv6Prefix(bytes, 0x20, 0x01, 0x00, 0x00)
                && !(first == 0x20 && second == 0x02);
        }
        return false;
    }

    private static boolean isIpv6Prefix(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(bytes[index]) != prefix[index]) return false;
        }
        return true;
    }
}
