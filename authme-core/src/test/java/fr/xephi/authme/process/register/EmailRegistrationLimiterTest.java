package fr.xephi.authme.process.register;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.EmailSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static fr.xephi.authme.process.register.EmailRegistrationLimiter.Admission.ALLOWED;
import static fr.xephi.authme.process.register.EmailRegistrationLimiter.Admission.LIMIT_REACHED;
import static fr.xephi.authme.process.register.EmailRegistrationLimiter.Admission.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailRegistrationLimiterTest {

    @TempDir
    private Path dataFolder;

    private Settings settings;
    private Clock clock;
    private ConsoleLogger logger;
    private AtomicLong now;
    private EmailRegistrationLimiter limiter;

    @BeforeEach
    void setUp() {
        settings = mock(Settings.class);
        clock = mock(Clock.class);
        logger = mock(ConsoleLogger.class);
        now = new AtomicLong(Instant.parse("2026-11-03T23:59:00Z").toEpochMilli());
        when(clock.millis()).thenAnswer(invocation -> now.get());
        when(settings.getProperty(EmailSettings.REGISTRATION_LIMITS_ENABLED)).thenReturn(true);
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(10);
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_UNTRUSTED_TOTAL)).thenReturn(30);
        when(settings.getProperty(EmailSettings.REGISTRATION_TRUSTED_DOMAINS)).thenReturn(List.of("gmail.com"));
        limiter = newLimiter();
    }

    @Test
    void shouldLimitOneDomainAcrossDifferentMailboxes() {
        for (int i = 0; i < 10; ++i) {
            assertEquals(ALLOWED, limiter.tryAcquire("player" + i + "@example.com"));
        }
        assertEquals(LIMIT_REACHED, limiter.tryAcquire("another@example.com"));
        assertEquals(ALLOWED, limiter.tryAcquire("player@example.net"));
    }

    @Test
    void shouldLimitRotationAcrossDomains() {
        for (int i = 0; i < 30; ++i) {
            assertEquals(ALLOWED, limiter.tryAcquire("player@domain" + i + ".com"));
        }
        assertEquals(LIMIT_REACHED, limiter.tryAcquire("player@new-domain.org"));
        assertEquals(ALLOWED, limiter.tryAcquire("player@gmail.com"));
    }

    @Test
    void shouldMatchTrustedDomainsExactlyWithoutConsumingSlots() throws Exception {
        when(settings.getProperty(EmailSettings.REGISTRATION_TRUSTED_DOMAINS)).thenReturn(List.of("GMAIL.COM"));
        for (int i = 0; i < 40; ++i) {
            assertEquals(ALLOWED, limiter.tryAcquire("player" + i + "@Gmail.Com"));
        }
        assertFalse(Files.exists(statePath()));
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(1);
        assertEquals(ALLOWED, limiter.tryAcquire("player@mail.gmail.com"));
        assertEquals(LIMIT_REACHED, limiter.tryAcquire("other@MAIL.GMAIL.COM"));
        assertEquals(ALLOWED, limiter.tryAcquire("player@gmail.com.example.org"));
        assertEquals("example.org", Files.readAllLines(statePath()).get(1).split("\t")[1]);
    }

    @Test
    void shouldGroupSubdomainsByRegistrableParent() {
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(1);
        assertEquals(ALLOWED, limiter.tryAcquire("first@a.example.co.uk"));
        assertEquals(LIMIT_REACHED, limiter.tryAcquire("second@b.example.co.uk"));
        assertEquals(ALLOWED, limiter.tryAcquire("third@other.co.uk"));
    }

    @Test
    void shouldNormalizeInternationalDomains() {
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(1);
        assertEquals(ALLOWED, limiter.tryAcquire("first@b\u00fccher.de"));
        assertEquals(LIMIT_REACHED, limiter.tryAcquire("second@xn--bcher-kva.de"));
    }

    @Test
    void shouldKeepCountersAfterRestartAndPruneAtRollingBoundary() throws Exception {
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(1);
        assertEquals(ALLOWED, limiter.tryAcquire("first@example.com"));
        now.addAndGet(Duration.ofMinutes(2).toMillis());
        assertEquals(LIMIT_REACHED, newLimiter().tryAcquire("second@example.com"));
        now.addAndGet(Duration.ofDays(1).minusMinutes(2).toMillis() - 1);
        assertEquals(LIMIT_REACHED, newLimiter().tryAcquire("second@example.com"));
        now.incrementAndGet();
        assertEquals(ALLOWED, newLimiter().tryAcquire("second@example.com"));
        assertEquals(List.of(now.get() + "\texample.com"), Files.readAllLines(statePath()));
    }

    @Test
    void shouldNotResetCountersWhenClockMovesBackwards() {
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(1);
        assertEquals(ALLOWED, limiter.tryAcquire("first@example.com"));
        now.addAndGet(-Duration.ofHours(1).toMillis());
        assertEquals(LIMIT_REACHED, newLimiter().tryAcquire("second@example.com"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldSerializeConcurrentReservations(boolean rotateDomains) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<EmailRegistrationLimiter.Admission>> attempts = new ArrayList<>();
            for (int i = 0; i < 50; ++i) {
                String email = rotateDomains ? "player@domain" + i + ".com" : "player@example.com";
                attempts.add(() -> limiter.tryAcquire(email));
            }
            int admitted = 0;
            for (Future<EmailRegistrationLimiter.Admission> result : executor.invokeAll(attempts)) {
                if (result.get() == ALLOWED) {
                    ++admitted;
                }
            }
            int expected = rotateDomains ? 30 : 10;
            assertEquals(expected, admitted);
            assertEquals(expected, Files.readAllLines(statePath()).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldShareStateBetweenInstancesUsingTheSameDirectory() {
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(1);
        assertEquals(ALLOWED, newLimiter().tryAcquire("first@example.com"));
        assertEquals(LIMIT_REACHED, newLimiter().tryAcquire("second@example.com"));
    }

    @Test
    void shouldRejectWhenAnotherInstanceHoldsTheLock() throws Exception {
        try (FileChannel channel = FileChannel.open(dataFolder.resolve("email-registration-limits.lock"),
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            assertTrue(lock.isValid());
            assertEquals(UNAVAILABLE, limiter.tryAcquire("player@example.com"));
            assertFalse(Files.exists(statePath()));
        }
        assertEquals(ALLOWED, limiter.tryAcquire("player@example.com"));
    }

    @Test
    void shouldRejectCorruptStateWithoutResettingIt() throws Exception {
        String corrupt = "not-a-timestamp\texample.com\n";
        Files.writeString(statePath(), corrupt);
        assertEquals(UNAVAILABLE, limiter.tryAcquire("player@example.com"));
        assertEquals(corrupt, Files.readString(statePath()));
        assertEquals(ALLOWED, limiter.tryAcquire("player@gmail.com"));
    }

    @Test
    void shouldRejectMalformedStateRows() throws Exception {
        for (String malformed : List.of("123\n", "-1\texample.com\n", "123\tcom\n",
            "123\tEXAMPLE.COM\n", "123\texample.com\textra\n")) {
            Files.writeString(statePath(), malformed);
            assertEquals(UNAVAILABLE, limiter.tryAcquire("player@example.com"));
        }
    }

    @Test
    void shouldRejectUnreadableAndOversizedState() throws Exception {
        Files.createDirectory(statePath());
        assertEquals(UNAVAILABLE, limiter.tryAcquire("player@example.com"));
        Files.delete(statePath());
        Files.writeString(statePath(), "x".repeat(1024 * 1024 + 1));
        assertEquals(UNAVAILABLE, limiter.tryAcquire("player@example.com"));
    }

    @Test
    void shouldRejectWhenPersistenceFails() throws Exception {
        Files.createDirectory(dataFolder.resolve("email-registration-limits.lock"));
        assertEquals(UNAVAILABLE, limiter.tryAcquire("player@example.com"));
        assertFalse(Files.exists(statePath()));
    }

    @Test
    void shouldAllowDisabledLimitsAndRegistrationsWithoutEmail() {
        when(settings.getProperty(EmailSettings.REGISTRATION_LIMITS_ENABLED)).thenReturn(false);
        assertEquals(ALLOWED, limiter.tryAcquire("player@example.com"));
        when(settings.getProperty(EmailSettings.REGISTRATION_LIMITS_ENABLED)).thenReturn(true);
        assertEquals(ALLOWED, limiter.tryAcquire(null));
        assertEquals(ALLOWED, limiter.tryAcquire(""));
        assertEquals(ALLOWED, limiter.tryAcquire("your@email.com"));
        assertFalse(Files.exists(statePath()));
    }

    @Test
    void shouldDisableEachLimitIndependently() {
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(0);
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_UNTRUSTED_TOTAL)).thenReturn(1);
        assertEquals(ALLOWED, limiter.tryAcquire("first@example.com"));
        assertEquals(LIMIT_REACHED, limiter.tryAcquire("second@example.org"));
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_UNTRUSTED_TOTAL)).thenReturn(0);
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(1);
        assertEquals(LIMIT_REACHED, limiter.tryAcquire("second@example.com"));
        assertEquals(ALLOWED, limiter.tryAcquire("second@example.org"));
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(0);
        assertEquals(ALLOWED, limiter.tryAcquire("third@example.com"));
    }

    @Test
    void shouldReadConfigurationChangesWithoutClearingCounters() {
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(1);
        assertEquals(ALLOWED, limiter.tryAcquire("first@example.com"));
        assertEquals(LIMIT_REACHED, limiter.tryAcquire("second@example.com"));
        when(settings.getProperty(EmailSettings.REGISTRATION_TRUSTED_DOMAINS)).thenReturn(List.of("example.com"));
        assertEquals(ALLOWED, limiter.tryAcquire("second@example.com"));
        when(settings.getProperty(EmailSettings.REGISTRATION_TRUSTED_DOMAINS)).thenReturn(List.of("gmail.com"));
        assertEquals(LIMIT_REACHED, limiter.tryAcquire("third@example.com"));
    }

    @Test
    void shouldRejectInvalidConfigurationAndDomains() {
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(-1);
        assertEquals(UNAVAILABLE, limiter.tryAcquire("player@example.com"));
        when(settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN)).thenReturn(10);
        for (String invalid : List.of("gmail.com", "player@gmail.com.", "player@com", "player@@gmail.com")) {
            assertEquals(UNAVAILABLE, limiter.tryAcquire(invalid));
        }
        when(settings.getProperty(EmailSettings.REGISTRATION_TRUSTED_DOMAINS)).thenReturn(List.of("*.example.com"));
        assertEquals(UNAVAILABLE, limiter.tryAcquire("player@example.com"));
        assertFalse(Files.exists(statePath()));
    }

    @Test
    void shouldPersistOnlyDomainsAndTimestamps() throws Exception {
        assertEquals(ALLOWED, limiter.tryAcquire("private-player@example.com"));
        assertEquals(now.get() + "\texample.com\n", Files.readString(statePath()));
        try (var files = Files.list(dataFolder)) {
            assertEquals(2, files.count());
        }
    }

    private EmailRegistrationLimiter newLimiter() {
        return new EmailRegistrationLimiter(settings, dataFolder, clock, logger);
    }

    private Path statePath() {
        return dataFolder.resolve("email-registration-limits.tsv");
    }
}
