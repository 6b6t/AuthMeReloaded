package fr.xephi.authme.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailAddressValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "player@gmail.com", "Player.Name+game@GMAIL.COM", "player@googlemail.com",
        "player@outlook.com", "player@proton.me", "player@icloud.com", "player@example.co.uk",
        "player@mail.example.org", "player@xn--bcher-kva.de", "o'connor@example.com"
    })
    void acceptsPublicEmailAddresses(String email) {
        assertTrue(EmailAddressValidator.isValid(email));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "player", "@gmail.com", "player@", "player@@gmail.com", "player@gmail.com@evil.com",
        "player@gmail", "player@gmail.con", "player@gmail.coms", "player@gmail.2013",
        "player@12345", "player@minecraft.accounts.invalid", "player@localhost",
        "player@127.0.0.1", "player@[127.0.0.1]", "player@-example.com", "player@example-.com",
        "player@example..com", "player@exam_ple.com", "player@gmail.com.", "player@.gmail.com",
        ".player@gmail.com", "player.@gmail.com", "play..er@gmail.com", "play er@gmail.com",
        "player@gmail.com\n", "player\r\nBcc:victim@gmail.com", "Player <player@gmail.com>",
        " player@gmail.com", "player@gmail.com ", "\"player\"@gmail.com"
    })
    void rejectsMalformedAddresses(String email) {
        assertFalse(EmailAddressValidator.isValid(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "player@mailinator.com", "player@MAILINATOR.COM", "player@sub.mailinator.com",
        "player@deep.sub.mailinator.com", "player@10minutemail.com", "player@guerrillamail.com"
    })
    void rejectsDisposableDomainsAndSubdomains(String email) {
        assertFalse(EmailAddressValidator.isValid(email));
    }

    @Test
    void enforcesAddressLengthLimits() {
        assertTrue(EmailAddressValidator.isValid("a".repeat(64) + "@example.com"));
        assertFalse(EmailAddressValidator.isValid("a".repeat(65) + "@example.com"));
        assertFalse(EmailAddressValidator.isValid("player@" + "a".repeat(64) + ".com"));
        String domain = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(57) + ".com";
        assertTrue(EmailAddressValidator.isValid("a".repeat(64) + "@" + domain));
        assertFalse(EmailAddressValidator.isValid("a".repeat(64) + "@x" + domain));
    }
}
