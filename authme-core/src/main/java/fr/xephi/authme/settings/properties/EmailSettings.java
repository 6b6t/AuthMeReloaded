package fr.xephi.authme.settings.properties;

import ch.jalu.configme.Comment;
import ch.jalu.configme.SettingsHolder;
import ch.jalu.configme.properties.Property;

import java.util.List;

import static ch.jalu.configme.properties.PropertyInitializer.newListProperty;
import static ch.jalu.configme.properties.PropertyInitializer.newProperty;

public final class EmailSettings implements SettingsHolder {

    @Comment("Email SMTP server host")
    public static final Property<String> SMTP_HOST =
        newProperty("Email.mailSMTP", "smtp.gmail.com");

    @Comment({"Email SMTP server port. The port determines the encryption mode:",
              "  25  -> plain SMTP; optional STARTTLS via 'useTls' (see below)",
              "  465 -> implicit SSL/TLS (SMTPS); 'useTls' is ignored",
              "  587 -> STARTTLS required (submission); 'useTls' is ignored",
              "  other -> STARTTLS required; 'useTls' is ignored"})
    public static final Property<Integer> SMTP_PORT =
        newProperty("Email.mailPort", 465);

    @Comment({"Only applies to port 25: enable STARTTLS on the plain SMTP connection?",
              "Has no effect when using port 465 (SSL) or 587 (STARTTLS), which enforce",
              "their own encryption and cannot be overridden by this setting."})
    public static final Property<Boolean> PORT25_USE_TLS =
        newProperty("Email.useTls", true);

    @Comment("Email account which sends the mails")
    public static final Property<String> MAIL_ACCOUNT =
        newProperty("Email.mailAccount", "");

    @Comment("Email account password")
    public static final Property<String> MAIL_PASSWORD =
        newProperty("Email.mailPassword", "");

    @Comment("Email address, fill when mailAccount is not the email address of the account")
    public static final Property<String> MAIL_ADDRESS =
        newProperty("Email.mailAddress", "");

    @Comment("Custom sender name, replacing the mailAccount name in the email")
    public static final Property<String> MAIL_SENDER_NAME =
        newProperty("Email.mailSenderName", "");

    @Comment("Recovery password length")
    public static final Property<Integer> RECOVERY_PASSWORD_LENGTH =
        newProperty("Email.RecoveryPasswordLength", 8);

    @Comment("Mail Subject")
    public static final Property<String> RECOVERY_MAIL_SUBJECT =
        newProperty("Email.mailSubject", "Your new AuthMe password");

    @Comment("Like maxRegPerIP but with email")
    public static final Property<Integer> MAX_REG_PER_EMAIL =
        newProperty("Email.maxRegPerEmail", 1);

    @Comment("Recall players to add an email?")
    public static final Property<Boolean> RECALL_PLAYERS =
        newProperty("Email.recallPlayers", false);

    @Comment("Delay in minute for the recall scheduler")
    public static final Property<Integer> DELAY_RECALL =
        newProperty("Email.delayRecall", 5);

    @Comment("Blacklist these domains for emails")
    public static final Property<List<String>> DOMAIN_BLACKLIST =
        newListProperty("Email.emailBlacklisted", "10minutemail.com");

    @Comment("Whitelist ONLY these domains for emails")
    public static final Property<List<String>> DOMAIN_WHITELIST =
        newListProperty("Email.emailWhitelisted");

    @Comment({"Limit new registrations from email domains outside trustedDomains over a rolling 24 hours.",
              "Counters persist in the AuthMe data folder. Separate data folders have separate limits.",
              "Existing email validation, account limits and recovery rules still apply."})
    public static final Property<Boolean> REGISTRATION_LIMITS_ENABLED =
        newProperty("Email.registrationLimits.enabled", true);

    @Comment({"Exact email domains exempt from these daily limits. Subdomains are not exempt.",
              "This list does not prove that an account is safe or override disposable-domain checks.",
              "Add a work, school or custom domain here to exempt it. Wildcards are not supported."})
    public static final Property<List<String>> REGISTRATION_TRUSTED_DOMAINS =
        newListProperty("Email.registrationLimits.trustedDomains",
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com", "msn.com",
            "yahoo.com", "yahoo.co.uk", "yahoo.de", "yahoo.fr", "yahoo.ca", "yahoo.co.jp",
            "ymail.com", "rocketmail.com", "icloud.com", "me.com", "mac.com",
            "proton.me", "protonmail.com", "pm.me", "aol.com", "gmx.com", "gmx.de", "gmx.net",
            "web.de", "fastmail.com", "mail.com", "yandex.com", "yandex.ru", "ya.ru", "qq.com", "163.com");

    @Comment({"Maximum admitted registrations per untrusted domain in 24 hours.",
              "Set to 0 to disable this limit.",
              "Subdomains share the limit of their registrable parent domain.",
              "Each admitted attempt consumes a slot, even if account storage or email delivery fails."})
    public static final Property<Integer> REGISTRATION_MAX_PER_DOMAIN =
        newProperty("Email.registrationLimits.maxPerDomain", 10);

    @Comment({"Maximum admitted registrations across all untrusted domains in 24 hours.",
              "This limit prevents domain rotation from exhausting the email quota.",
              "Set to 0 to disable this limit."})
    public static final Property<Integer> REGISTRATION_MAX_UNTRUSTED_TOTAL =
        newProperty("Email.registrationLimits.maxUntrustedTotal", 30);

    @Comment("Send the new password drawn in an image?")
    public static final Property<Boolean> PASSWORD_AS_IMAGE =
        newProperty("Email.generateImage", false);

    @Comment("The OAuth2 token")
    public static final Property<String> OAUTH2_TOKEN =
        newProperty("Email.emailOauth2Token", "");

    @Comment({"Verify the SSL/TLS server certificate hostname?",
              "Only applies when an SSL/TLS connection is active (port 465, port 587,",
              "port 25 with useTls=true, or any other port).",
              "Set to false only if your SMTP server uses a self-signed certificate.",
              "Note: if you previously used port 465, this check was not enforced;",
              "set to false to restore the old behavior with a self-signed certificate."})
    public static final Property<Boolean> SSL_CHECK_SERVER_IDENTITY =
        newProperty("Email.sslCheckServerIdentity", true);

    private EmailSettings() {
    }

}
