package fr.xephi.authme.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailAddressNormalizerTest {

    @ParameterizedTest
    @CsvSource({
        "Player.Name+game@GMAIL.COM, playername@gmail.com",
        "Player.Name@googlemail.com, playername@gmail.com",
        "Player.Name+game@outlook.com, player.name@outlook.com",
        "Player.Name+game@hotmail.co.uk, player.name@hotmail.co.uk",
        "Player+game@live.com, player@live.com",
        "Player+game@passport.com, player@passport.com",
        "Player+game@icloud.com, player@icloud.com",
        "Player+game@me.com, player@me.com",
        "Player-game@yahoo.com, player@yahoo.com",
        "Player-Name-game@ymail.com, player-name@ymail.com",
        "Player+game@yahoo.com, player+game@yahoo.com",
        "Player@ya.ru, player@yandex.ru",
        "Player+game@yandex.com, player+game@yandex.ru",
        "Player.Name+game@EXAMPLE.COM, player.name+game@example.com",
        "Player.Name+game@mail.gmail.com, player.name+game@mail.gmail.com",
        "Player-Name@example.org, player-name@example.org",
        "Player..Name@gmail.com, player..name@gmail.com"
    })
    void matchesHarmonyNormalizationRules(String email, String expected) {
        assertEquals(expected, EmailAddressNormalizer.normalize(email).orElseThrow());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "your@email.com", "player", "@gmail.com", "player@", "player@@gmail.com",
        "+game@gmail.com", "+game@hotmail.com", "+game@icloud.com", "-game@yahoo.com", ".@gmail.com"
    })
    void rejectsMissingCanonicalMailboxes(String email) {
        assertTrue(EmailAddressNormalizer.normalize(email).isEmpty());
    }
}
