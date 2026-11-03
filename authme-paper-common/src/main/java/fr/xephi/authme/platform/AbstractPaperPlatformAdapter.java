package fr.xephi.authme.platform;

import fr.xephi.authme.AuthMe;
import fr.xephi.authme.command.CommandDescription;
import fr.xephi.authme.command.CommandHandler;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.register.RegisterSecondaryArgument;
import fr.xephi.authme.process.register.RegistrationType;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

import java.util.Collection;

/**
 * Shared platform adapter behavior for Paper-derived servers.
 */
public abstract class AbstractPaperPlatformAdapter extends AbstractSpigotPlatformAdapter {

    private static final boolean DIALOG_AVAILABLE = hasDialogApi();

    private PaperDialogController dialogs;

    /** Connects the injected listener to this adapter, which ServiceLoader creates outside the injector. */
    void setDialogController(PaperDialogController controller) {
        this.dialogs = controller;
    }

    @Override
    public void teleportPlayer(Player player, Location location) {
        player.teleportAsync(location);
    }

    @Override
    public Location getPlayerRespawnLocation(Player player) {
        return player.getRespawnLocation();
    }

    @Override
    public String getKickReason(PlayerKickEvent event) {
        return PlainTextComponentSerializer.plainText().serialize(event.reason());
    }

    @Override
    public boolean isDialogSupported() {
        return DIALOG_AVAILABLE;
    }

    @Override
    public void showLoginDialog(Player player) {
        if (DIALOG_AVAILABLE && dialogs != null) {
            dialogs.showLogin(player);
        }
    }

    @Override
    public void showRegisterDialog(Player player, RegistrationType type, RegisterSecondaryArgument secondArg) {
        if (DIALOG_AVAILABLE && dialogs != null) {
            dialogs.showRegister(player, type, secondArg);
        }
    }

    @Override
    public void onAuthMessage(Player player, MessageKey key, String message) {
        if (DIALOG_AVAILABLE && dialogs != null) {
            dialogs.onMessage(player, key, message);
        }
    }

    @Override
    public void registerCommands(AuthMe plugin, CommandHandler commandHandler, Collection<CommandDescription> commands) {
        new PaperBrigadierCommandRegistrar(commandHandler::processCommand).registerCommands(plugin, commands);
    }

    @Override
    public boolean shouldHandlePlayerLoginEvent() {
        return false;
    }

    @Override
    public boolean shouldHandlePlayerSpawnLocationEvent() {
        return false;
    }

    private static boolean hasDialogApi() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog", false, AbstractPaperPlatformAdapter.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
