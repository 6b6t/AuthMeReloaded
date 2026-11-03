package fr.xephi.authme.platform;

import fr.xephi.authme.listener.FoliaChatListener;
import fr.xephi.authme.listener.FoliaPlayerSpawnLocationListener;
import fr.xephi.authme.listener.PaperLoginValidationListener;
import fr.xephi.authme.listener.PlayerOpenSignListener;
import fr.xephi.authme.process.register.RegisterSecondaryArgument;
import fr.xephi.authme.process.register.RegistrationType;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class FoliaPlatformAdapterTest {

    private final FoliaPlatformAdapter adapter = new FoliaPlatformAdapter();

    @Test
    public void getPlatformNameReturnsExpectedValue() {
        assertThat(adapter.getPlatformName(), is("folia-1.21"));
    }

    @Test
    public void getCompatibilityErrorReturnsNullForCompatibleClasspath() {
        assertThat(adapter.getCompatibilityError(), is((String) null));
    }

    @Test
    public void isDialogSupportedReturnsTrueWhenApiIsOnClasspath() {
        // The Paper dialog API is a test dependency, so DIALOG_AVAILABLE is true
        assertThat(adapter.isDialogSupported(), is(true));
    }

    @Test
    public void getAdditionalListenersContainsBothFoliaListeners() {
        List<Class<? extends Listener>> listeners = adapter.getAdditionalListeners();

        assertThat(listeners, containsInAnyOrder(
            FoliaChatListener.class,
            FoliaPlayerSpawnLocationListener.class,
            PaperLoginValidationListener.class,
            PlayerOpenSignListener.class,
            PaperDialogController.class));
    }

    @Test
    public void shouldDisableLegacyPlayerLoginEventHandling() {
        assertThat(adapter.shouldHandlePlayerLoginEvent(), is(false));
    }

    @Test
    public void shouldDisableLegacyPlayerSpawnLocationEventHandling() {
        assertThat(adapter.shouldHandlePlayerSpawnLocationEvent(), is(false));
    }

    @Test
    public void showLoginDialogDelegatesToController() {
        Player player = mock(Player.class);
        PaperDialogController controller = installDialogController();
        adapter.showLoginDialog(player);
        verify(controller).showLogin(player);
    }

    @Test
    public void showRegisterDialogDelegatesToController() {
        Player player = mock(Player.class);
        PaperDialogController controller = installDialogController();
        adapter.showRegisterDialog(player, RegistrationType.EMAIL, RegisterSecondaryArgument.CONFIRMATION);
        verify(controller).showRegister(player, RegistrationType.EMAIL, RegisterSecondaryArgument.CONFIRMATION);
    }

    private PaperDialogController installDialogController() {
        PaperDialogController controller = mock(PaperDialogController.class);
        adapter.setDialogController(controller);
        return controller;
    }

    @Test
    public void teleportPlayerCallsTeleportAsync() {
        // given
        Player player = mock(Player.class);
        Location location = mock(Location.class);

        // when
        adapter.teleportPlayer(player, location);

        // then
        verify(player).teleportAsync(location);
    }

    @Test
    public void getKickReasonReturnsPlainTextFromComponent() {
        // given
        // PlayerKickEvent is not final; reason() is a Paper API method, stubable with Mockito 5
        PlayerKickEvent event = mock(PlayerKickEvent.class);
        given(event.reason()).willReturn(Component.text("You were kicked"));

        // when
        String reason = adapter.getKickReason(event);

        // then
        assertThat(reason, is("You were kicked"));
    }
}


