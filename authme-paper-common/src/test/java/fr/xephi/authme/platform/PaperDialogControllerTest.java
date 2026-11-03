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
import fr.xephi.authme.process.register.RegisterSecondaryArgument;
import fr.xephi.authme.process.register.RegistrationType;
import fr.xephi.authme.security.HashAlgorithm;
import fr.xephi.authme.service.BukkitService;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.RegistrationSettings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperDialogControllerTest {

    private final List<Window> windows = new ArrayList<>();
    private Settings settings;
    private Messages messages;
    private PlayerCache cache;
    private LimboService limbo;
    private LoginCaptchaManager loginCaptcha;
    private RegistrationCaptchaManager registerCaptcha;
    private BukkitService scheduler;
    private Server server;
    private Player player;
    private PaperDialogController controller;
    private MockedStatic<PaperDialogHelper> renderer;

    @BeforeEach
    void setUp() {
        ConsoleLogger.initialize(mock(Logger.class), null);
        settings = mock(Settings.class);
        messages = mock(Messages.class);
        cache = mock(PlayerCache.class);
        limbo = mock(LimboService.class);
        loginCaptcha = mock(LoginCaptchaManager.class);
        registerCaptcha = mock(RegistrationCaptchaManager.class);
        scheduler = mock(BukkitService.class);
        server = mock(Server.class);
        player = player(UUID.randomUUID());
        when(server.getPlayer(player.getUniqueId())).thenReturn(player);
        when(settings.getProperty(RegistrationSettings.USE_DIALOG_UI)).thenReturn(true);
        when(settings.getProperty(RegistrationSettings.REGISTRATION_TYPE)).thenReturn(RegistrationType.EMAIL);
        when(settings.getProperty(RegistrationSettings.REGISTER_SECOND_ARGUMENT))
            .thenReturn(RegisterSecondaryArgument.CONFIRMATION);
        when(settings.getProperty(SecuritySettings.MAX_PASSWORD_LENGTH)).thenReturn(64);
        when(messages.retrieveSingle(eq(player), any(MessageKey.class)))
            .thenAnswer(invocation -> "translated:" + ((MessageKey) invocation.getArgument(1)).getKey());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(scheduler).scheduleSyncTaskFromOptionallyAsyncTask(any(Player.class), any(Runnable.class));
        controller = new PaperDialogController(settings, messages, cache, limbo, loginCaptcha, registerCaptcha, scheduler, mock(AbstractPaperPlatformAdapter.class));
        renderer = mockStatic(PaperDialogHelper.class);
        renderer.when(() -> PaperDialogHelper.show(any(), any(), any())).thenAnswer(invocation -> {
            windows.add(new Window(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
            return null;
        });
        renderer.when(() -> PaperDialogHelper.commandTemplate(any(), any())).thenCallRealMethod();
    }

    @AfterEach
    void tearDown() {
        renderer.close();
        ConsoleLogger.initialize(null, null);
    }

    @Test
    void shouldConnectTheListenerToTheServiceLoadedAdapter() {
        AbstractPaperPlatformAdapter adapter = new AbstractPaperPlatformAdapter() {
            @Override
            public String getPlatformName() {
                return "test-paper";
            }
        };
        new PaperDialogController(settings, messages, cache, limbo, loginCaptcha, registerCaptcha, scheduler, adapter);

        adapter.showLoginDialog(player);
        adapter.onAuthMessage(player, MessageKey.WRONG_PASSWORD, "Try again");

        assertEquals(2, windows.size());
        assertEquals("Try again", last().form().description());
    }

    @Test
    void shouldSubmitLoginThroughTheNativeCommandPipeline() {
        controller.showLogin(player);
        assertEquals("login $(password)", command());
        assertEquals("translated:dialog.login_title", last().form().title());
        assertEquals(64, last().form().fields().getFirst().maxLength());
    }

    /** Covers every supported registration argument contract, including optional email and email confirmation. */
    @ParameterizedTest
    @CsvSource({
        "EMAIL,CONFIRMATION,register $(email) $(confirm)",
        "EMAIL,NONE,register $(email)",
        "PASSWORD,CONFIRMATION,register $(password) $(confirm)",
        "PASSWORD,EMAIL_MANDATORY,register $(password) $(email)",
        "PASSWORD,EMAIL_OPTIONAL,register $(password) $(email)",
        "PASSWORD,NONE,register $(password)"
    })
    void shouldPreserveRegistrationArguments(RegistrationType type, RegisterSecondaryArgument argument,
                                             String expectedCommand) {
        controller.showRegister(player, type, argument);
        assertEquals(expectedCommand, command());
        if (type == RegistrationType.EMAIL) {
            assertEquals(254, last().form().fields().getFirst().maxLength());
            assertEquals("translated:dialog.email_register_description", last().form().description());
        }
    }

    @Test
    void shouldPreservePasswordlessTwoFactorRegistration() {
        when(settings.getProperty(SecuritySettings.PASSWORD_HASH)).thenReturn(HashAlgorithm.TWO_FACTOR);
        controller.showRegister(player, RegistrationType.PASSWORD, RegisterSecondaryArgument.CONFIRMATION);
        assertTrue(last().form().fields().isEmpty());
        assertEquals("register", command());
    }

    @Test
    void shouldShowCaptchaBeforeLoginOrRegistration() {
        when(loginCaptcha.isCaptchaRequired(player.getName())).thenReturn(true);
        when(loginCaptcha.getCaptchaCodeOrGenerateNew(player.getName())).thenReturn("ABCD");
        when(messages.retrieveSingle(player, MessageKey.USAGE_CAPTCHA, "ABCD")).thenReturn("Solve ABCD");
        controller.showLogin(player);
        assertEquals("captcha $(code)", command());
        assertEquals("translated:dialog.captcha_description\nSolve ABCD", last().form().description());
        when(loginCaptcha.isCaptchaRequired(player.getName())).thenReturn(false);
        when(registerCaptcha.isCaptchaRequired(player.getName())).thenReturn(true);
        when(registerCaptcha.getCaptchaCodeOrGenerateNew(player.getName())).thenReturn("ABCD");
        controller.showRegister(player, RegistrationType.EMAIL, RegisterSecondaryArgument.NONE);
        assertEquals("captcha $(code)", command());
        assertEquals("translated:dialog.captcha_description\nSolve ABCD", last().form().description());
        controller.onMessage(player, MessageKey.REGISTER_CAPTCHA_SUCCESS, "Solved");
        assertEquals("register $(email)", command());
    }

    @Test
    void shouldShowTotpWhenTheExistingLimboStateRequiresIt() {
        LimboPlayer state = mock(LimboPlayer.class);
        when(limbo.getLimboPlayer(player.getName())).thenReturn(state);
        when(state.getState()).thenReturn(LimboPlayerState.TOTP_REQUIRED);
        controller.showLogin(player);
        assertEquals("totp code $(code)", command());
    }

    @Test
    void shouldFollowNormalAuthenticationFeedbackWithoutGrantingLogin() {
        controller.showLogin(player);
        controller.onMessage(player, MessageKey.WRONG_PASSWORD, "Incorrect password");
        assertEquals("login $(password)", command());
        assertEquals("Incorrect password", last().form().description());
        controller.onMessage(player, MessageKey.USAGE_CAPTCHA, "Solve 1234");
        assertEquals("captcha $(code)", command());
        controller.onMessage(player, MessageKey.CAPTCHA_SUCCESS, "Solved");
        assertEquals("login $(password)", command());
        controller.onMessage(player, MessageKey.TWO_FACTOR_CODE_REQUIRED, "Authenticator code required");
        assertEquals("totp code $(code)", command());
        controller.onMessage(player, MessageKey.TWO_FACTOR_INVALID_CODE, "Incorrect code");
        assertEquals("Incorrect code", last().form().description());
        verify(cache, never()).updatePlayer(any());
    }

    @Test
    void shouldCarryQuotaErrorsAndEmailRegistrationIntoTheLoginScreen() {
        controller.showRegister(player, RegistrationType.EMAIL, RegisterSecondaryArgument.CONFIRMATION);
        controller.onMessage(player, MessageKey.EMAIL_REGISTRATION_LIMIT, "Provider limit reached");
        assertEquals("Provider limit reached", last().form().description());
        assertEquals("register $(email) $(confirm)", command());
        controller.onMessage(player, MessageKey.ACCOUNT_NOT_ACTIVATED, "Check your email for the password");
        assertEquals("login $(password)", command());
        assertEquals("Check your email for the password", last().form().description());
    }

    @Test
    void shouldRouteEachRecoveryStepThroughExistingCommands() {
        controller.showLogin(player);
        navigate(last(), player, PostJoinDialog.Screen.RECOVERY);
        assertEquals("email recovery $(email)", command());
        controller.onMessage(player, MessageKey.RECOVERY_CODE_SENT, "Code sent");
        assertEquals("email code $(code)", command());
        controller.onMessage(player, MessageKey.INCORRECT_RECOVERY_CODE, "Two attempts left");
        assertEquals("Two attempts left", last().form().description());
        controller.onMessage(player, MessageKey.RECOVERY_CHANGE_PASSWORD, "Choose a password");
        assertEquals("email setpassword $(password)", command());
        controller.onMessage(player, MessageKey.CHANGE_PASSWORD_EXPIRED, "Recovery expired");
        assertEquals("email recovery $(email)", command());
        controller.onMessage(player, MessageKey.PASSWORD_CHANGED_SUCCESS, "Password changed");
        assertEquals("login $(password)", command());
    }

    @Test
    void shouldNotResetInputsForPeriodicReminders() {
        controller.showLogin(player);
        controller.onMessage(player, MessageKey.LOGIN_MESSAGE, "Use login");
        assertEquals(1, windows.size());
        controller.onMessage(player, MessageKey.TWO_FACTOR_CODE_REQUIRED, "Enter code");
        int count = windows.size();
        controller.onMessage(player, MessageKey.TWO_FACTOR_CODE_REQUIRED, "Enter code");
        assertEquals(count, windows.size());
    }

    @Test
    void shouldShowRepeatedErrorsAfterCommandsCloseTheWindow() {
        controller.showLogin(player);
        controller.onCommand(new PlayerCommandPreprocessEvent(player, "/login first"));
        controller.onMessage(player, MessageKey.WRONG_PASSWORD, "Incorrect password");
        controller.onCommand(new PlayerCommandPreprocessEvent(player, "/login second"));
        controller.onMessage(player, MessageKey.WRONG_PASSWORD, "Incorrect password");
        assertEquals(3, windows.size());
    }

    @Test
    void shouldRejectNavigationFromAnotherPlayerAndConsumeEachWindowOnlyOnce() {
        controller.showLogin(player);
        Window original = last();
        navigate(original, player(UUID.randomUUID()), PostJoinDialog.Screen.RECOVERY);
        assertEquals(1, windows.size());
        navigate(original, player, PostJoinDialog.Screen.RECOVERY);
        navigate(original, player, PostJoinDialog.Screen.RECOVERY);
        assertEquals(2, windows.size());
    }

    @Test
    void shouldIgnoreOldConnectionCallbacksMessagesAndQuitWithTheSameUuid() {
        controller.showLogin(player);
        Window original = last();
        Player replacement = player(player.getUniqueId());
        when(server.getPlayer(player.getUniqueId())).thenReturn(replacement);
        controller.showLogin(replacement);
        controller.onQuit(new PlayerQuitEvent(player, Component.empty()));
        controller.onMessage(player, MessageKey.WRONG_PASSWORD, "Old failure");
        navigate(original, player, PostJoinDialog.Screen.RECOVERY);
        assertEquals(2, windows.size());
        navigate(last(), replacement, PostJoinDialog.Screen.RECOVERY);
        assertEquals(3, windows.size());
    }

    @Test
    void shouldRecheckOwnershipWhenScheduledNavigationRuns() {
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(invocation -> { tasks.add(invocation.getArgument(1)); return null; })
            .when(scheduler).scheduleSyncTaskFromOptionallyAsyncTask(any(Player.class), any(Runnable.class));
        controller.showLogin(player);
        navigate(last(), player, PostJoinDialog.Screen.RECOVERY);
        controller.onQuit(new PlayerQuitEvent(player, Component.empty()));
        tasks.getFirst().run();
        assertEquals(1, windows.size());
    }

    @Test
    void shouldKeepChatFallbackAvailableAndIgnoreLaterFeedback() {
        controller.showLogin(player);
        last().navigation().accept(player,
            new PostJoinDialog.Button("Use chat", PostJoinDialog.Screen.CHAT, List.of()));
        controller.onMessage(player, MessageKey.WRONG_PASSWORD, "Incorrect password");
        assertEquals(1, windows.size());
    }

    @Test
    void shouldCloseOwnedDialogsOnLoginButLeaveOtherDialogsAloneAfterCommandSubmission() {
        controller.showLogin(player);
        controller.onLogin(new LoginEvent(player));
        verify(player).closeDialog();
        controller.showLogin(player);
        controller.onCommand(new PlayerCommandPreprocessEvent(player, "/login secret"));
        controller.onLogin(new LoginEvent(player));
        verify(player).closeDialog();
    }

    @Test
    void shouldNotOfferDialogsToAuthenticatedDisconnectedOrDisabledPlayers() {
        when(cache.isAuthenticated(player.getName())).thenReturn(true);
        controller.showLogin(player);
        when(cache.isAuthenticated(player.getName())).thenReturn(false);
        when(player.isOnline()).thenReturn(false);
        controller.showLogin(player);
        when(player.isOnline()).thenReturn(true);
        when(settings.getProperty(RegistrationSettings.USE_DIALOG_UI)).thenReturn(false);
        controller.showLogin(player);
        assertTrue(windows.isEmpty());
    }

    @Test
    void shouldFallBackToChatIfTheNativeRendererFails() {
        renderer.when(() -> PaperDialogHelper.show(any(), any(), any()))
            .thenThrow(new IllegalStateException("No native renderer"));
        windows.clear();
        controller.showLogin(player);
        controller.onMessage(player, MessageKey.WRONG_PASSWORD, "Incorrect password");
        assertTrue(windows.isEmpty());
    }

    private Player player(UUID id) {
        Player actor = mock(Player.class);
        when(actor.getUniqueId()).thenReturn(id);
        when(actor.getName()).thenReturn("player");
        when(actor.getServer()).thenReturn(server);
        when(actor.isOnline()).thenReturn(true);
        return actor;
    }

    private Window last() {
        return windows.getLast();
    }

    private String command() {
        Window window = last();
        return PaperDialogHelper.commandTemplate(window.form(), window.form().buttons().getFirst());
    }

    private void navigate(Window window, Player actor, PostJoinDialog.Screen target) {
        PostJoinDialog.Button button = window.form().buttons().stream()
            .filter(candidate -> candidate.target() == target).findFirst().orElseThrow();
        window.navigation().accept(actor, button);
    }

    private record Window(Player player, PostJoinDialog form, PaperDialogHelper.NavigationHandler navigation) {
    }
}
