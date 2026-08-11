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
     * Requires all four character classes, which is what pays for a short minimum length.
     *
     * <p>Eight characters drawn from one class is a search space an offline attacker walks through;
     * the same eight spanning lower case, upper case, digits and punctuation is roughly a thousand
     * times larger. The rule buys back what the length concession gives away, and it is deliberately
     * the only composition requirement: rules that dictate *where* the classes may appear push people
     * towards the same handful of shapes, which is how a longer password becomes an easier guess.
     *
     * <p>A class is judged by {@link Character}, so accented letters count as letters and anything
     * that is neither letter nor digit — punctuation, symbols, a space — counts as special.
     */
    private static void requireVariety(String password) {
        boolean lower = false, upper = false, digit = false, special = false;
        for (int i = 0; i < password.length(); ) {
            int c = password.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
            else if (!Character.isLetter(c)) special = true;
        }
        var missing = new ArrayList<String>();
        if (!lower) missing.add("a lower-case letter");
        if (!upper) missing.add("an upper-case letter");
        if (!digit) missing.add("a digit");
        if (!special) missing.add("a special character");
        if (!missing.isEmpty())
            throw new IllegalArgumentException("A password must contain " + String.join(", ", missing));
    }
}
