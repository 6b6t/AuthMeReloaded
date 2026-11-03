package fr.xephi.authme.platform;

import java.util.List;

/** Immutable presentation data. Commands still pass through AuthMe's existing command handler. */
record PostJoinDialog(String title, String description, List<Field> fields, List<Button> buttons,
                      String closeLabel) {

    record Field(String key, String label, int maxLength) {
    }

    record Button(String label, Screen target, List<String> command) {
    }

    enum Screen {
        LOGIN, REGISTER, CAPTCHA, TOTP, RECOVERY, RECOVERY_CODE, RESET_PASSWORD, CHAT
    }
}
