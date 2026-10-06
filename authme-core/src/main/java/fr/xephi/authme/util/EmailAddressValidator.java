package fr.xephi.authme.util;

import com.google.common.net.InternetDomainName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates public email addresses without network requests or changing the delivery address.
 */
public final class EmailAddressValidator {

    private static final Pattern LOCAL_PART = Pattern.compile(
        "[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+)*");
    private static final Pattern DOMAIN_LABEL = Pattern.compile(
        "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?");
    private static final Set<String> DISPOSABLE_DOMAINS = loadDisposableDomains();

    private EmailAddressValidator() {
    }

    /**
     * Checks syntax, domain suffixes and Mailchecker's disposable-domain list.
     * This does not establish whether the mailbox exists or belongs to the player.
     *
     * @param email the address to check
     * @return whether the address is suitable for receiving authentication mail
     */
    public static boolean isValid(String email) {
        if (email == null || email.length() > 254) {
            return false;
        }
        int separator = email.indexOf('@');
        if (separator < 1 || separator > 64 || separator != email.lastIndexOf('@')) {
            return false;
        }
        if (!LOCAL_PART.matcher(email.substring(0, separator)).matches()) {
            return false;
        }

        String domain = email.substring(separator + 1).toLowerCase(Locale.ROOT);
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        try {
            if (!InternetDomainName.from(domain).hasPublicSuffix()) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }

        // Match parent domains too, as Mailchecker does, to prevent subdomain bypasses.
        for (String candidate = domain; ; candidate = candidate.substring(candidate.indexOf('.') + 1)) {
            if (DISPOSABLE_DOMAINS.contains(candidate)) {
                return false;
            }
            if (candidate.indexOf('.') < 0) {
                return true;
            }
        }
    }

    private static Set<String> loadDisposableDomains() {
        InputStream resource = EmailAddressValidator.class.getResourceAsStream("/email/disposable-domains.txt");
        if (resource == null) {
            throw new IllegalStateException("Missing disposable email domain list");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            return reader.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new IllegalStateException("Could not load disposable email domain list", e);
        }
    }
}
