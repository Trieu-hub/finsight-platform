package com.pm.authservice.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rejects the passwords an attacker tries first.
 *
 * <p>Three checks, and each earns its place:
 * <ol>
 *   <li><b>Length.</b> Eight characters, the NIST floor. Not raised to twelve: that would lock
 *       out nobody who matters (an attacker does not care) while pushing real people toward
 *       writing it down.</li>
 *   <li><b>Blocklist.</b> Compared after lower-casing <em>and</em> after stripping trailing
 *       digits, so {@code Password2026} is caught by the entry {@code password}. Appending the
 *       year is the single most common way of "strengthening" a password.</li>
 *   <li><b>Not the account's own name.</b> A password containing the username or the email's
 *       local part is guessable by anyone who knows the address — which is everyone, since the
 *       address is how you log in.</li>
 * </ol>
 *
 * <p>What it deliberately does <b>not</b> do is demand a digit, a symbol and a capital. Those
 * rules produce {@code Password1!} — which passes all of them and sits near the top of every
 * cracking list.
 */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private static final Logger log = LoggerFactory.getLogger(StrongPasswordValidator.class);

    static final int MIN_LENGTH = 8;
    private static final String BLOCKLIST = "common-passwords.txt";

    /**
     * Loaded once. A failure to read it must not make every password valid, so the field stays
     * empty and the other two checks still run — but it is logged loudly, because a silently
     * absent blocklist is a security control that looks present and is not.
     */
    private static final Set<String> COMMON = load();

    private static Set<String> load() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(BLOCKLIST).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(java.util.Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            log.error("Could not read {} — the common-password check is DISABLED", BLOCKLIST, e);
            return Set.of();
        }
    }

    /** Package-private for the tests, which assert the list actually loaded. */
    static int blocklistSize() {
        return COMMON.size();
    }

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        // Absence is @NotBlank's business, not this validator's. Reporting it here too would
        // show the user two messages for one empty field.
        if (password == null) return true;

        if (password.length() < MIN_LENGTH) {
            return fail(context, "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (isCommon(password)) {
            return fail(context, "That password is too common — pick something less guessable");
        }
        return true;
    }

    private boolean isCommon(String password) {
        String lower = password.toLowerCase(java.util.Locale.ROOT);
        if (COMMON.contains(lower)) return true;
        // `password2026`, `qwerty123` — the year or a counter tacked on the end.
        String withoutTrailingDigits = lower.replaceAll("\\d+$", "");
        return withoutTrailingDigits.length() >= 4 && COMMON.contains(withoutTrailingDigits);
    }

    /**
     * True when the password gives away, or is given away by, the account's own identifiers.
     * Called from the request-level check, which is the only place that can see both fields.
     */
    public static boolean echoesIdentity(String password, String username, String email) {
        if (password == null) return false;
        String lower = password.toLowerCase(java.util.Locale.ROOT);
        return containsPart(lower, username) || containsPart(lower, localPart(email));
    }

    private static boolean containsPart(String lowerPassword, String part) {
        if (part == null) return false;
        String candidate = part.toLowerCase(java.util.Locale.ROOT).trim();
        // Below four characters this would reject far too much: an account called `an` would
        // forbid every password containing those two letters.
        return candidate.length() >= 4 && lowerPassword.contains(candidate);
    }

    /**
     * The identifying half of an address: everything before the {@code @}, and before any
     * {@code +tag}. Sub-addressing is a routing hint, not part of who the person is — without
     * stripping it, {@code loadtest+9271@x} would let {@code LoadTest123!} through, which is
     * exactly the password this check exists to refuse.
     */
    private static String localPart(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        int plus = local.indexOf('+');
        return plus > 0 ? local.substring(0, plus) : local;
    }

    private static boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
