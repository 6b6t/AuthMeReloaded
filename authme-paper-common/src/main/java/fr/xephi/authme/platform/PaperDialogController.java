package fr.xephi.authme.platform;

import fr.xephi.authme.ConsoleLogger;
import fr.xephi.authme.data.auth.PlayerCache;
import fr.xephi.authme.data.captcha.LoginCaptchaManager;
import fr.xephi.authme.data.captcha.RegistrationCaptchaManager;
import fr.xephi.authme.data.limbo.LimboPlayer;
import fr.xephi.authme.data.limbo.LimboPlayerState;
import fr.xephi.authme.data.limbo.LimboService;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.message.Messages;
import fr.xephi.authme.output.ConsoleLoggerFactory;
import fr.xephi.authme.process.register.RegisterSecondaryArgument;
import fr.xephi.authme.process.register.RegistrationType;
import fr.xephi.authme.security.HashAlgorithm;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.RegistrationSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static fr.xephi.authme.platform.PostJoinDialog.Screen;
import static fr.xephi.authme.message.MessageKey.*;

/** Owns presentation windows for joined connections, never authentication decisions or credentials. */
final class PaperDialogController implements Listener {

    private static final EnumSet<MessageKey> ERRORS = EnumSet.of(
        WRONG_PASSWORD, INVALID_EMAIL, INVALID_NEW_EMAIL, INVALID_OLD_EMAIL, MAX_REGISTER_EXCEEDED,
        PASSWORD_MATCH_ERROR, PASSWORD_IS_USERNAME_ERROR, PASSWORD_UNSAFE_ERROR, PASSWORD_PWNED_ERROR,
        PASSWORD_CHARACTERS_ERROR, INVALID_PASSWORD_LENGTH, EMAIL_ALREADY_USED_ERROR, EMAIL_SEND_FAILURE,
        EMAIL_REGISTRATION_LIMIT, EMAIL_REGISTRATION_UNAVAILABLE, INCOMPLETE_EMAIL_SETTINGS,
        EMAIL_COOLDOWN_ERROR, TWO_FACTOR_INVALID_CODE, INCORRECT_RECOVERY_CODE, NO_PERMISSION,
        USAGE_LOGIN, USAGE_REGISTER, USAGE_RECOVER_EMAIL, ERROR, REGISTRATION_DISABLED);

    private final ConsoleLogger logger = ConsoleLoggerFactory.get(PaperDialogController.class);
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Settings settings;
    private final Messages messages;
    private final PlayerCache playerCache;
    private final LimboService limboService;
    private final LoginCaptchaManager loginCaptcha;
    private final RegistrationCaptchaManager registerCaptcha;
    private final BukkitService bukkitService;

    @Inject
    PaperDialogController(Settings settings, Messages messages, PlayerCache playerCache, LimboService limboService,
                          LoginCaptchaManager loginCaptcha, RegistrationCaptchaManager registerCaptcha,
                          BukkitService bukkitService, DialogAdapter dialogAdapter) {
        this.settings = settings;
        this.messages = messages;
        this.playerCache = playerCache;
        this.limboService = limboService;
        this.loginCaptcha = loginCaptcha;
        this.registerCaptcha = registerCaptcha;
        this.bukkitService = bukkitService;
        // AuthMe constructs this listener before players can join. The ServiceLoader adapter is not injected.
        ((AbstractPaperPlatformAdapter) dialogAdapter).setDialogController(this);
    }

    void showLogin(Player player) {
        open(player, true, settings.getProperty(RegistrationSettings.REGISTRATION_TYPE),
            settings.getProperty(RegistrationSettings.REGISTER_SECOND_ARGUMENT));
    }

    void showRegister(Player player, RegistrationType type, RegisterSecondaryArgument secondArg) {
        open(player, false, type, secondArg);
    }

    /** Replaces presentation state for the live connection without carrying over credentials or approvals. */
    private void open(Player player, boolean registered, RegistrationType type, RegisterSecondaryArgument secondArg) {
        if (!enabled(player)) {
            return;
        }
        Session session = new Session(player, registered, type, secondArg);
        sessions.put(player.getUniqueId(), session);
        render(session, initialScreen(session), "");
    }

    /** Mirrors normal command feedback only while this connection still owns an active dialog session. */
    void onMessage(Player player, MessageKey key, String feedback) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.owner != player) {
            return;
        }
        if (key == LOGIN_SUCCESS || key == ALREADY_LOGGED_IN_ERROR || !enabled(player)) {
            close(session);
            return;
        }
        Screen next = switch (key) {
            case TWO_FACTOR_CODE_REQUIRED, TWO_FACTOR_INVALID_CODE -> Screen.TOTP;
            case USAGE_CAPTCHA, CAPTCHA_FOR_REGISTRATION_REQUIRED, CAPTCHA_WRONG_ERROR -> Screen.CAPTCHA;
            case CAPTCHA_SUCCESS, REGISTER_CAPTCHA_SUCCESS -> session.registered ? Screen.LOGIN : Screen.REGISTER;
            case RECOVERY_CODE_SENT -> Screen.RECOVERY_CODE;
            case RECOVERY_TRIES_EXCEEDED, CHANGE_PASSWORD_EXPIRED -> Screen.RECOVERY;
            case RECOVERY_CODE_CORRECT, RECOVERY_CHANGE_PASSWORD -> Screen.RESET_PASSWORD;
            case RECOVERY_EMAIL_SENT_MESSAGE, PASSWORD_CHANGED_SUCCESS -> Screen.LOGIN;
            case NAME_ALREADY_REGISTERED, ACCOUNT_NOT_ACTIVATED, REGISTER_SUCCESS -> {
                session.registered = true;
                yield Screen.LOGIN;
            }
            default -> ERRORS.contains(key) ? session.screen : null;
        };
        if (next != null) {
            // Periodic challenge reminders must not erase input while the player is typing.
            if (session.window.get() != null && session.screen == next
                && (key == TWO_FACTOR_CODE_REQUIRED || key == USAGE_CAPTCHA
                    || key == CAPTCHA_FOR_REGISTRATION_REQUIRED)) {
                return;
            }
            render(session, next, feedback);
        }
    }

    /** Invalidates navigation buttons when a command takes over, without inspecting or retaining its arguments. */
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.owner == event.getPlayer()) {
            session.window.set(null);
        }
    }

    @EventHandler
    public void onLogin(LoginEvent event) {
        forget(event.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer(), false);
    }

    /** Removes only the departing connection, optionally closing a window still owned by AuthMe. */
    private void forget(Player player, boolean closeWindow) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.owner == player) {
            if (closeWindow) {
                close(session);
            } else {
                sessions.remove(player.getUniqueId(), session);
                session.window.set(null);
            }
        }
    }

    /** Closes the UI only if its window was not already consumed by command submission. */
    private void close(Session session) {
        if (sessions.remove(session.owner.getUniqueId(), session) && session.window.getAndSet(null) != null
            && currentPlayer(session.owner)) {
            session.owner.closeDialog();
        }
    }

    /** Limits presentation to current, unauthenticated connections while the setting is enabled. */
    private boolean enabled(Player player) {
        return settings.getProperty(RegistrationSettings.USE_DIALOG_UI) && currentPlayer(player)
            && !playerCache.isAuthenticated(player.getName());
    }

    /** Rejects disconnected or replaced Player objects even when their offline UUIDs match. */
    private static boolean currentPlayer(Player player) {
        return player.isOnline() && player.getServer().getPlayer(player.getUniqueId()) == player;
    }

    /** Selects a prompt from existing security state. The command handler still enforces that state. */
    private Screen initialScreen(Session session) {
        LimboPlayer limbo = limboService.getLimboPlayer(session.owner.getName());
        if (limbo != null && limbo.getState() == LimboPlayerState.TOTP_REQUIRED) {
            return Screen.TOTP;
        }
        if (loginCaptcha.isCaptchaRequired(session.owner.getName())
            || (!session.registered && registerCaptcha.isCaptchaRequired(session.owner.getName()))) {
            return Screen.CAPTCHA;
        }
        return session.registered ? Screen.LOGIN : Screen.REGISTER;
    }

    /** Replaces the current window and keeps renderer failures from interrupting authentication. */
    private void render(Session session, Screen screen, String feedback) {
        if (sessions.get(session.owner.getUniqueId()) != session || !enabled(session.owner)) {
            return;
        }
        session.screen = screen;
        session.feedback = feedback;
        Object window = new Object();
        session.window.set(window);
        try {
            PostJoinDialog form = form(session);
            // Resolve the session when clicked so unused callbacks do not retain a disconnected Player.
            PaperDialogHelper.show(session.owner, form, (actor, button) ->
                bukkitService.scheduleSyncTaskFromOptionallyAsyncTask(actor,
                    () -> navigate(actor, window, button)));
        } catch (RuntimeException | LinkageError e) {
            sessions.remove(session.owner.getUniqueId(), session);
            session.window.set(null);
            logger.warning("Could not show an authentication dialog. Players can continue with chat commands.");
        }
    }

    /** Consumes a navigation window once. Authentication commands are submitted by the client through Bukkit. */
    private void navigate(Player actor, Object window, PostJoinDialog.Button button) {
        Session session = sessions.get(actor.getUniqueId());
        if (session == null || session.owner != actor || !enabled(actor)
            || !session.window.compareAndSet(window, null)) {
            return;
        }
        if (button.target() == Screen.CHAT) {
            sessions.remove(session.owner.getUniqueId(), session);
        } else if (button.target() != null) {
            render(session, button.target(), "");
        }
    }

    /** Describes native command inputs in the exact order accepted by the existing commands. */
    private PostJoinDialog form(Session session) {
        Player player = session.owner;
        List<PostJoinDialog.Field> fields = new ArrayList<>();
        List<PostJoinDialog.Button> buttons = new ArrayList<>();
        MessageKey title;
        MessageKey description;
        MessageKey submit;
        List<String> command;
        switch (session.screen) {
            case LOGIN -> {
                title = DIALOG_LOGIN_TITLE;
                description = DIALOG_LOGIN_DESCRIPTION;
                submit = DIALOG_LOGIN_BUTTON;
                command = List.of("login");
                fields.add(field(player, "password", DIALOG_PASSWORD, passwordLength()));
                buttons.add(new PostJoinDialog.Button(text(player, DIALOG_RECOVERY_BUTTON), Screen.RECOVERY, List.of()));
            }
            case REGISTER -> {
                title = DIALOG_REGISTER_TITLE;
                description = session.type == RegistrationType.EMAIL
                    ? DIALOG_EMAIL_REGISTER_DESCRIPTION : DIALOG_REGISTER_DESCRIPTION;
                submit = DIALOG_REGISTER_BUTTON;
                command = List.of("register");
                boolean email = session.type == RegistrationType.EMAIL;
                fields.add(field(player, email ? "email" : "password", email ? DIALOG_EMAIL : DIALOG_PASSWORD,
                    email ? 254 : passwordLength()));
                if (settings.getProperty(SecuritySettings.PASSWORD_HASH) == HashAlgorithm.TWO_FACTOR) {
                    fields.clear();
                } else if (session.secondArg == RegisterSecondaryArgument.CONFIRMATION) {
                    fields.add(field(player, "confirm", email ? DIALOG_CONFIRM_EMAIL : DIALOG_CONFIRM_PASSWORD,
                        email ? 254 : passwordLength()));
                } else if (!email && (session.secondArg == RegisterSecondaryArgument.EMAIL_MANDATORY
                    || session.secondArg == RegisterSecondaryArgument.EMAIL_OPTIONAL)) {
                    boolean required = session.secondArg == RegisterSecondaryArgument.EMAIL_MANDATORY;
                    fields.add(field(player, "email", required ? DIALOG_EMAIL : DIALOG_OPTIONAL_EMAIL, 254));
                }
            }
            case CAPTCHA -> {
                title = DIALOG_CAPTCHA_TITLE;
                description = DIALOG_CAPTCHA_DESCRIPTION;
                submit = DIALOG_VERIFY_BUTTON;
                command = List.of("captcha");
                fields.add(field(player, "code", DIALOG_CODE, 64));
            }
            case TOTP -> {
                title = DIALOG_TOTP_TITLE;
                description = DIALOG_TOTP_DESCRIPTION;
                submit = DIALOG_VERIFY_BUTTON;
                command = List.of("totp", "code");
                fields.add(field(player, "code", DIALOG_CODE, 64));
            }
            case RECOVERY -> {
                title = DIALOG_RECOVERY_TITLE;
                description = DIALOG_RECOVERY_DESCRIPTION;
                submit = DIALOG_RECOVERY_BUTTON;
                command = List.of("email", "recovery");
                fields.add(field(player, "email", DIALOG_EMAIL, 254));
            }
            case RECOVERY_CODE -> {
                title = DIALOG_RECOVERY_CODE_TITLE;
                description = DIALOG_RECOVERY_CODE_DESCRIPTION;
                submit = DIALOG_VERIFY_BUTTON;
                command = List.of("email", "code");
                fields.add(field(player, "code", DIALOG_CODE, 64));
            }
            case RESET_PASSWORD -> {
                title = DIALOG_RESET_TITLE;
                description = DIALOG_RESET_DESCRIPTION;
                submit = DIALOG_RESET_BUTTON;
                command = List.of("email", "setpassword");
                fields.add(field(player, "password", DIALOG_PASSWORD, passwordLength()));
            }
            default -> throw new IllegalStateException("No form for the chat fallback");
        }
        buttons.add(0, new PostJoinDialog.Button(text(player, submit), null, command));
        if (session.screen == Screen.RECOVERY || session.screen == Screen.RECOVERY_CODE) {
            buttons.add(new PostJoinDialog.Button(text(player, DIALOG_BACK_BUTTON), Screen.LOGIN, List.of()));
        }
        String body = session.feedback.isEmpty() ? text(player, description) : session.feedback;
        if (session.screen == Screen.CAPTCHA && session.feedback.isEmpty()) {
            String name = player.getName();
            String code = loginCaptcha.isCaptchaRequired(name) ? loginCaptcha.getCaptchaCodeOrGenerateNew(name)
                : registerCaptcha.getCaptchaCodeOrGenerateNew(name);
            body += "\n" + messages.retrieveSingle(player, USAGE_CAPTCHA, code);
        }
        return new PostJoinDialog(text(player, title), body, List.copyOf(fields), List.copyOf(buttons),
            text(player, DIALOG_CHAT_BUTTON));
    }

    private PostJoinDialog.Field field(Player player, String key, MessageKey label, int maxLength) {
        return new PostJoinDialog.Field(key, text(player, label), maxLength);
    }

    private int passwordLength() {
        return Math.max(1, Math.min(32767, settings.getProperty(SecuritySettings.MAX_PASSWORD_LENGTH)));
    }

    private String text(Player player, MessageKey key) {
        return messages.retrieveSingle(player, key);
    }

    private static final class Session {
        private final Player owner;
        private final RegistrationType type;
        private final RegisterSecondaryArgument secondArg;
        private final AtomicReference<Object> window = new AtomicReference<>();
        private boolean registered;
        private Screen screen;
        private String feedback = "";

        private Session(Player owner, boolean registered, RegistrationType type, RegisterSecondaryArgument secondArg) {
            this.owner = owner;
            this.registered = registered;
            this.type = type;
            this.secondArg = secondArg;
        }
    }
}
