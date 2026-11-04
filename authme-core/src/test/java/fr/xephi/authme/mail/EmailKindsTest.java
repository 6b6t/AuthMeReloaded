package fr.xephi.authme.mail;

import ch.jalu.configme.properties.Property;
import fr.xephi.authme.TestHelper;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.EmailSettings;
import fr.xephi.authme.settings.properties.PluginSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailKindsTest {

    @Mock
    private Settings settings;
    @Mock
    private SendMailSsl sendMailSsl;
    @TempDir
    File dataFolder;

    @BeforeAll
    static void initLogger() {
        TestHelper.setupLogger();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "registration | Welcome to 6b6t | /login ABC123 | registration_email.html",
        "passwordReset | Your new 6b6t password | /login ABC123 | email.html",
        "recoveryCode | Reset your 6b6t password | /email code ABC123 | recovery_code_email.html",
        "verification | Verify your 6b6t account | /verification ABC123 | verification_code_email.html"
    })
    void shouldRenderEachEmailKind(String kind, String subject, String command, String templateFile)
        throws IOException, EmailException {
        Property<String> subjectProperty = subjectProperty(kind);
        given(settings.getProperty(subjectProperty)).willReturn(subjectProperty.getDefaultValue());
        given(settings.getProperty(PluginSettings.SERVER_NAME)).willReturn("6b6t");
        String template = Files.readString(TestHelper.getJarFile("/" + templateFile).toPath(),
            StandardCharsets.UTF_8);
        prepareTemplate(kind, template);
        HtmlEmail email = new HtmlEmail();
        given(sendMailSsl.initializeMail("user@example.com")).willReturn(email);
        given(sendMailSsl.sendEmail(anyString(), eq(email))).willReturn(true);

        assertThat(sendEmail(kind), equalTo(true));

        assertThat(email.getSubject(), equalTo(subject));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sendMailSsl).sendEmail(body.capture(), eq(email));
        assertThat(body.getValue(), containsString("Hi Player,"));
        assertThat(body.getValue(), containsString(command));
        assertThat(body.getValue(), not(containsString("<servername />")));
        assertThat(body.getValue(), not(containsString("<playername />")));
        if (kind.equals("verification")) {
            assertThat(body.getValue(), containsString("expires in 5 minutes"));
        } else if (kind.equals("recoveryCode")) {
            assertThat(body.getValue(), containsString("expires in 2 hours"));
        }
    }

    @ParameterizedTest
    @CsvSource({"registration", "passwordReset", "recoveryCode", "verification"})
    void shouldFallBackToLegacySubjectForEachKind(String kind) throws EmailException {
        given(settings.getProperty(subjectProperty(kind))).willReturn("  ");
        given(settings.getProperty(EmailSettings.RECOVERY_MAIL_SUBJECT))
            .willReturn("<servername /> mail for <playername />");
        given(settings.getProperty(PluginSettings.SERVER_NAME)).willReturn("6b6t");
        prepareTemplate(kind, "Hi <playername />");
        HtmlEmail email = new HtmlEmail();
        given(sendMailSsl.initializeMail("user@example.com")).willReturn(email);

        sendEmail(kind);

        assertThat(email.getSubject(), equalTo("6b6t mail for Player"));
    }

    @Test
    void shouldEscapeTemplateValuesWithoutEscapingTheSubject() throws EmailException {
        given(settings.getProperty(PluginSettings.SERVER_NAME)).willReturn("Friends & <friends>");
        given(settings.getProperty(EmailSettings.REGISTRATION_MAIL_SUBJECT)).willReturn("Join <servername />");
        prepareTemplate("registration", "<h1><servername /></h1><p><playername />: <generatedpass /></p>");
        HtmlEmail email = new HtmlEmail();
        given(sendMailSsl.initializeMail("user@example.com")).willReturn(email);

        new EmailService(dataFolder, settings, sendMailSsl)
            .sendRegistrationMail("Player", "user@example.com", "a<&\"'");

        assertThat(email.getSubject(), equalTo("Join Friends & <friends>"));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sendMailSsl).sendEmail(body.capture(), eq(email));
        assertThat(body.getValue(),
            equalTo("<h1>Friends &amp; &lt;friends&gt;</h1><p>Player: a&lt;&amp;&quot;&#39;</p>"));
    }

    private void prepareTemplate(String kind, String template) {
        switch (kind) {
            case "registration":
                given(settings.getRegistrationEmailMessage()).willReturn(template);
                given(settings.getProperty(EmailSettings.PASSWORD_AS_IMAGE)).willReturn(false);
                given(sendMailSsl.hasAllInformation()).willReturn(true);
                break;
            case "passwordReset":
                given(settings.getPasswordEmailMessage()).willReturn(template);
                given(settings.getProperty(EmailSettings.PASSWORD_AS_IMAGE)).willReturn(false);
                given(sendMailSsl.hasAllInformation()).willReturn(true);
                break;
            case "recoveryCode":
                given(settings.getRecoveryCodeEmailMessage()).willReturn(template);
                given(settings.getProperty(SecuritySettings.RECOVERY_CODE_HOURS_VALID)).willReturn(2);
                break;
            case "verification":
                given(settings.getVerificationEmailMessage()).willReturn(template);
                given(settings.getProperty(SecuritySettings.VERIFICATION_CODE_EXPIRATION_MINUTES)).willReturn(5);
                given(sendMailSsl.hasAllInformation()).willReturn(true);
                break;
            default:
                throw new IllegalArgumentException(kind);
        }
    }

    private boolean sendEmail(String kind) {
        EmailService service = new EmailService(dataFolder, settings, sendMailSsl);
        return switch (kind) {
            case "registration" -> service.sendRegistrationMail("Player", "user@example.com", "ABC123");
            case "passwordReset" -> service.sendPasswordMail("Player", "user@example.com", "ABC123");
            case "recoveryCode" -> service.sendRecoveryCode("Player", "user@example.com", "ABC123");
            case "verification" -> service.sendVerificationMail("Player", "user@example.com", "ABC123");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static Property<String> subjectProperty(String kind) {
        return switch (kind) {
            case "registration" -> EmailSettings.REGISTRATION_MAIL_SUBJECT;
            case "passwordReset" -> EmailSettings.PASSWORD_MAIL_SUBJECT;
            case "recoveryCode" -> EmailSettings.RECOVERY_CODE_MAIL_SUBJECT;
            case "verification" -> EmailSettings.VERIFICATION_MAIL_SUBJECT;
            default -> throw new IllegalArgumentException(kind);
        };
    }
}
