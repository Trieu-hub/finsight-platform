package com.pm.authservice.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The password rules, as rules — no Spring, no database.
 *
 * <p>Each test says which attack the rule is against, because a password policy that cannot name
 * its threat tends to grow into "must contain a symbol" and make things worse.
 */
class StrongPasswordValidatorTest {

    private final StrongPasswordValidator validator = new StrongPasswordValidator();

    /** The context is only used to replace the message; a lenient stub keeps the tests readable. */
    private ConstraintValidatorContext context() {
        ConstraintValidatorContext context =
                Mockito.mock(ConstraintValidatorContext.class, Mockito.RETURNS_DEEP_STUBS);
        return context;
    }

    private boolean valid(String password) {
        return validator.isValid(password, context());
    }

    @Test
    @DisplayName("the blocklist actually loaded — an empty one would pass everything")
    void blocklistIsPresent() {
        // A resource that fails to load leaves this check silently disabled: every password would
        // pass and the policy would look enforced. Worth asserting rather than assuming.
        assertThat(StrongPasswordValidator.blocklistSize()).isGreaterThan(50);
    }

    @Test
    @DisplayName("accepts a long, unremarkable passphrase")
    void acceptsAReasonablePassword() {
        assertThat(valid("correct horse battery staple")).isTrue();
        assertThat(valid("mua thu ha noi 1998")).isTrue();
    }

    @Test
    @DisplayName("rejects anything shorter than the floor")
    void rejectsShortPasswords() {
        assertThat(valid("Ab3$xY")).isFalse();
        assertThat(valid("1234567")).isFalse();
    }

    @Test
    @DisplayName("rejects the passwords a credential-stuffing run tries first")
    void rejectsCommonPasswords() {
        // These are not hypothetical: they top every leaked-credential list, which is exactly why
        // stuffing works at all.
        assertThat(valid("password")).isFalse();
        assertThat(valid("12345678")).isFalse();
        assertThat(valid("qwertyuiop")).isFalse();
        assertThat(valid("matkhau")).isFalse();
    }

    @Test
    @DisplayName("sees through a year or counter tacked on the end")
    void rejectsCommonPasswordsWithDigitsAppended() {
        // The single most common way of "strengthening" a password, and it adds nothing: a
        // cracker appends digits by default.
        assertThat(valid("password2026")).isFalse();
        assertThat(valid("welcome123")).isFalse();
        assertThat(valid("iloveyou1")).isFalse();
    }

    @Test
    @DisplayName("is case-insensitive, because crackers are")
    void rejectsCommonPasswordsInAnyCase() {
        assertThat(valid("PassWord")).isFalse();
        assertThat(valid("LetMeIn")).isFalse();
    }

    @Test
    @DisplayName("does not demand a symbol, a digit and a capital")
    void imposesNoCompositionRules() {
        // NIST dropped composition rules because they produce `Password1!` — which satisfies
        // every one of them and sits near the top of the cracking lists.
        assertThat(valid("aardvark lantern rope")).isTrue();
    }

    @Test
    @DisplayName("leaves an absent password to @NotBlank")
    void ignoresNull() {
        // Two messages for one empty field is worse than one.
        assertThat(valid(null)).isTrue();
    }

    @Test
    @DisplayName("refuses a password that echoes the account's own name or address")
    void rejectsIdentityEchoes() {
        assertThat(StrongPasswordValidator.echoesIdentity("trieu-vernfy-2026", "trieu", "t@x.com"))
                .isTrue();
        assertThat(StrongPasswordValidator.echoesIdentity("myquocbao!!", "other", "quocbao@x.com"))
                .isTrue();
        assertThat(StrongPasswordValidator.echoesIdentity("unrelated phrase", "trieu", "t@x.com"))
                .isFalse();
    }

    @Test
    @DisplayName("sees past a +tag in the address, which is routing and not identity")
    void stripsSubAddressingBeforeComparing() {
        // Found while checking what the change would break: the load test signs up as
        // `loadtest+<stamp>@…` with `LoadTest123!`. Comparing the whole local part let that
        // through, and it is precisely the pattern this check exists to catch.
        assertThat(StrongPasswordValidator.echoesIdentity(
                "LoadTest123!", "lt9271", "loadtest+9271@loadtest.local")).isTrue();
    }

    @Test
    @DisplayName("ignores a very short username, which would forbid almost everything")
    void shortIdentifiersAreNotMatched() {
        // An account called `an` must not make every password containing those two letters
        // invalid — "banana" would be refused.
        assertThat(StrongPasswordValidator.echoesIdentity("banana lantern", "an", "an@x.com"))
                .isFalse();
    }
}
