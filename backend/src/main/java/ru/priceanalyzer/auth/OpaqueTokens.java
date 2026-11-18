package ru.priceanalyzer.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

final class OpaqueTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final String FORMAT = "[A-Za-z0-9_-]{43}";
    private static final Pattern TOKEN = Pattern.compile(FORMAT);

    private OpaqueTokens() {}

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean valid(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
