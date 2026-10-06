package fr.xephi.authme.util;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Computes comparison keys using validator.js 13.15.35's default normalizeEmail rules.
 * The caller must validate the input and preserve the original delivery address.
 * Provider lists and rules are covered by email/validator-LICENSE.txt.
 */
public final class EmailAddressNormalizer {

    private static final Pattern DOTS = Pattern.compile("\\.+");
    private static final Set<String> ICLOUD_DOMAINS = Set.of("icloud.com", "me.com");
    private static final Set<String> OUTLOOK_DOMAINS = Set.of(
        "hotmail.at", "hotmail.be", "hotmail.ca", "hotmail.cl", "hotmail.co.il", "hotmail.co.nz",
        "hotmail.co.th", "hotmail.co.uk", "hotmail.com", "hotmail.com.ar", "hotmail.com.au",
        "hotmail.com.br", "hotmail.com.gr", "hotmail.com.mx", "hotmail.com.pe", "hotmail.com.tr",
        "hotmail.com.vn", "hotmail.cz", "hotmail.de", "hotmail.dk", "hotmail.es", "hotmail.fr",
        "hotmail.hu", "hotmail.id", "hotmail.ie", "hotmail.in", "hotmail.it", "hotmail.jp",
        "hotmail.kr", "hotmail.lv", "hotmail.my", "hotmail.ph", "hotmail.pt", "hotmail.sa",
        "hotmail.sg", "hotmail.sk", "live.be", "live.co.uk", "live.com", "live.com.ar", "live.com.mx",
        "live.de", "live.es", "live.eu", "live.fr", "live.it", "live.nl", "msn.com", "outlook.at",
        "outlook.be", "outlook.cl", "outlook.co.il", "outlook.co.nz", "outlook.co.th", "outlook.com",
        "outlook.com.ar", "outlook.com.au", "outlook.com.br", "outlook.com.gr", "outlook.com.pe",
        "outlook.com.tr", "outlook.com.vn", "outlook.cz", "outlook.de", "outlook.dk", "outlook.es",
        "outlook.fr", "outlook.hu", "outlook.id", "outlook.ie", "outlook.in", "outlook.it",
        "outlook.jp", "outlook.kr", "outlook.lv", "outlook.my", "outlook.ph", "outlook.pt",
        "outlook.sa", "outlook.sg", "outlook.sk", "passport.com");
    private static final Set<String> YAHOO_DOMAINS = Set.of(
        "rocketmail.com", "yahoo.ca", "yahoo.co.uk", "yahoo.com", "yahoo.de", "yahoo.fr", "yahoo.in",
        "yahoo.it", "ymail.com");
    private static final Set<String> YANDEX_DOMAINS = Set.of(
        "yandex.ru", "yandex.ua", "yandex.kz", "yandex.com", "yandex.by", "ya.ru");

    private EmailAddressNormalizer() {
    }

    /**
     * Returns a canonical comparison key, never a replacement delivery address.
     *
     * @param email the input address
     * @return the key, or empty when no mailbox remains after normalization
     */
    public static Optional<String> normalize(String email) {
        if (Utils.isEmailEmpty(email)) {
            return Optional.empty();
        }
        int separator = email.indexOf('@');
        if (separator < 1 || separator != email.lastIndexOf('@') || separator == email.length() - 1) {
            return Optional.empty();
        }
        String local = email.substring(0, separator).toLowerCase(Locale.ROOT);
        String domain = email.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) {
            local = removeSubaddress(local, '+');
            local = DOTS.matcher(local).replaceAll(match -> match.group().length() == 1 ? "" : match.group());
            domain = "gmail.com";
        } else if (ICLOUD_DOMAINS.contains(domain) || OUTLOOK_DOMAINS.contains(domain)) {
            local = removeSubaddress(local, '+');
        } else if (YAHOO_DOMAINS.contains(domain)) {
            int subaddress = local.lastIndexOf('-');
            if (subaddress >= 0) {
                local = local.substring(0, subaddress);
            }
        } else if (YANDEX_DOMAINS.contains(domain)) {
            domain = "yandex.ru";
        }
        return local.isEmpty() ? Optional.empty() : Optional.of(local + "@" + domain);
    }

    public static boolean isSameMailbox(String firstEmail, String secondEmail) {
        Optional<String> first = normalize(firstEmail);
        return first.isPresent() && first.equals(normalize(secondEmail));
    }

    private static String removeSubaddress(String local, char delimiter) {
        int subaddress = local.indexOf(delimiter);
        return subaddress < 0 ? local : local.substring(0, subaddress);
    }
}
