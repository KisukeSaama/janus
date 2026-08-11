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
    public static final int MIN_LENGTH = 8;

    // Every value the repository ships as an example, including the one in `.env.example` that
    // deliberately has the shape a real password needs: showing the required form is useful, and a
    // published password being accepted anywhere is not.
    private static final Set<String> FORBIDDEN = Set.of(
            "change-me-in-production",
            "admin",
            "password",
            "changeme",
            "janus",
            "replace-with-a-long-random-value",
            "replace-with-a-long-random-value-9!");

    private PasswordPolicy() {}

    public static void check(String username, String password) {
        if (password == null || password.isBlank()) throw new IllegalArgumentException("A password is required");
        if (password.length() < MIN_LENGTH)
            throw new IllegalArgumentException("A password must be at least " + MIN_LENGTH + " characters");
        if (FORBIDDEN.contains(password.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("That password is a well-known placeholder and must be replaced");
        if (password.equalsIgnoreCase(username))
            throw new IllegalArgumentException("A password must differ from the username");
        requireVariety(password);
    }

    /**
     * Requires lower case, upper case and digits without imposing where they must appear.
     * Character classes are judged by {@link Character}, so accented letters count as letters.
     */
    private static void requireVariety(String password) {
        boolean lower = false, upper = false, digit = false;
        for (int i = 0; i < password.length(); ) {
            int c = password.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
        }
        var missing = new ArrayList<String>();
        if (!lower) missing.add("a lower-case letter");
        if (!upper) missing.add("an upper-case letter");
        if (!digit) missing.add("a digit");
        if (!missing.isEmpty())
            throw new IllegalArgumentException("A password must contain " + String.join(", ", missing));
    }
}
