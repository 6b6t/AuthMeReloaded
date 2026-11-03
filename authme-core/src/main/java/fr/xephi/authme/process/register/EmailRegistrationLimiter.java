package fr.xephi.authme.process.register;

import com.google.common.net.InternetDomainName;
import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.initialization.DataFolder;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.EmailSettings;
import fr.xephi.authme.util.Utils;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.IDN;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reserves persistent daily registration slots before account storage or email delivery. */
class EmailRegistrationLimiter {

    private static final long WINDOW_MILLIS = Duration.ofDays(1).toMillis();
    private static final long MAX_STATE_BYTES = 1024 * 1024;

    private final Settings settings;
    private final Path dataFolder;
    private final Clock clock;
    private final ConsoleLogger logger;

    @Inject
    EmailRegistrationLimiter(Settings settings, @DataFolder File dataFolder) {
        this(settings, dataFolder.toPath(), Clock.systemUTC(), ConsoleLoggerFactory.get(EmailRegistrationLimiter.class));
    }

    EmailRegistrationLimiter(Settings settings, Path dataFolder, Clock clock, ConsoleLogger logger) {
        this.settings = settings;
        this.dataFolder = dataFolder;
        this.clock = clock;
        this.logger = logger;
    }

    synchronized Admission tryAcquire(String email) {
        if (!settings.getProperty(EmailSettings.REGISTRATION_LIMITS_ENABLED) || Utils.isEmailEmpty(email)) {
            return Admission.ALLOWED;
        }
        int perDomain = settings.getProperty(EmailSettings.REGISTRATION_MAX_PER_DOMAIN);
        int total = settings.getProperty(EmailSettings.REGISTRATION_MAX_UNTRUSTED_TOTAL);
        if (perDomain == 0 && total == 0) {
            return Admission.ALLOWED;
        }
        try {
            if (perDomain < 0 || total < 0) {
                throw new IllegalArgumentException("Negative registration limit");
            }
            int separator = email.indexOf('@');
            if (separator < 1 || separator != email.lastIndexOf('@')) {
                throw new IllegalArgumentException("Invalid email address");
            }
            String domain = normalizeDomain(email.substring(separator + 1));
            for (String trusted : settings.getProperty(EmailSettings.REGISTRATION_TRUSTED_DOMAINS)) {
                if (domain.equals(normalizeDomain(trusted))) {
                    return Admission.ALLOWED;
                }
            }
            InternetDomainName parsed = InternetDomainName.from(domain);
            String bucket = parsed.topPrivateDomain().toString();
            Files.createDirectories(dataFolder);
            // Lock a separate file because the state file is replaced atomically after each reservation.
            Path lockPath = dataFolder.resolve("email-registration-limits.lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    return Admission.UNAVAILABLE;
                }
                long now = clock.millis();
                Path statePath = dataFolder.resolve("email-registration-limits.tsv");
                List<Entry> entries = readEntries(statePath, now - WINDOW_MILLIS);
                long domainCount = entries.stream().filter(entry -> entry.domain().equals(bucket)).count();
                if ((total > 0 && entries.size() >= total) || (perDomain > 0 && domainCount >= perDomain)) {
                    return Admission.LIMIT_REACHED;
                }
                entries.add(new Entry(now, bucket));
                writeEntries(statePath, entries);
                return Admission.ALLOWED;
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            // Do not log addresses, state contents or exception messages that can contain them.
            logger.warning("Email registration limits are unavailable. Check their configuration and persistent files.");
            return Admission.UNAVAILABLE;
        }
    }

    private static String normalizeDomain(String domain) {
        String normalized = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        InternetDomainName parsed = InternetDomainName.from(normalized);
        if (normalized.endsWith(".") || !parsed.isUnderPublicSuffix()) {
            throw new IllegalArgumentException("Invalid email domain");
        }
        return normalized;
    }

    private static List<Entry> readEntries(Path path, long cutoff) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.exists(path)) {
            return entries;
        }
        if (Files.size(path) > MAX_STATE_BYTES) {
            throw new IOException("Registration state is too large");
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 2) {
                throw new IOException("Invalid registration state");
            }
            long timestamp = Long.parseLong(fields[0]);
            String domain = normalizeDomain(fields[1]);
            if (timestamp < 0 || !domain.equals(fields[1])) {
                throw new IOException("Invalid registration state");
            }
            if (timestamp > cutoff) {
                entries.add(new Entry(timestamp, domain));
            }
        }
        return entries;
    }

    private static void writeEntries(Path statePath, List<Entry> entries) throws IOException {
        StringBuilder state = new StringBuilder();
        for (Entry entry : entries) {
            state.append(entry.timestamp()).append('\t').append(entry.domain()).append('\n');
        }
        byte[] bytes = state.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STATE_BYTES) {
            throw new IOException("Registration state is too large");
        }
        Path temporary = Files.createTempFile(statePath.getParent(), "email-registration-limits-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    enum Admission {
        ALLOWED,
        LIMIT_REACHED,
        UNAVAILABLE
    }

    private record Entry(long timestamp, String domain) {
    }
}
