package io.janus.security;

import java.util.*;

/**
 * The single statement of what makes a console password acceptable.
 *
 * <p>It lives here rather than inside the startup validator because the same rule now has two
 * callers: the bootstrap password a deployment is configured with, and every account an
 * administrator creates from the console. A deployment that refuses to start on a weak password and
 * then lets one be typed into a form would be enforcing nothing.
 *
 * <p>Failures are {@link IllegalArgumentException}, which the API answers as 400. The startup
 * validator translates them into a refusal to start, because at that point there is nobody to
 * answer.
 */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 16;

    private static final Set<String> FORBIDDEN = Set.of(
            "change-me-in-production", "admin", "password", "changeme", "janus", "replace-with-a-long-random-value");

    private PasswordPolicy() {}

    public static void check(String username, String password) {
        if (password == null || password.isBlank()) throw new IllegalArgumentException("A password is required");
        if (password.length() < MIN_LENGTH)
            throw new IllegalArgumentException("A password must be at least " + MIN_LENGTH + " characters");
        if (FORBIDDEN.contains(password.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("That password is a well-known placeholder and must be replaced");
        if (password.equalsIgnoreCase(username))
            throw new IllegalArgumentException("A password must differ from the username");
    }
}
