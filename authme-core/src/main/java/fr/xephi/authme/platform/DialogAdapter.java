package fr.xephi.authme.platform;

import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.process.register.RegisterSecondaryArgument;
import fr.xephi.authme.process.register.RegistrationType;
import org.bukkit.entity.Player;

/**
 * Platform adapter for showing graphical dialog UIs to players on supported platforms (1.21.6+).
 * The default implementation is a no-op (dialog not supported).
 */
public interface DialogAdapter {

    /**
     * Returns whether this platform supports showing dialog UIs.
     *
     * @return true if dialog UI is supported, false otherwise
     */
    default boolean isDialogSupported() {
        return false;
    }

    /**
     * Shows the login dialog to the given player.
     *
     * @param player the player to show the dialog to
     */
    default void showLoginDialog(Player player) {
    }

    /**
     * Shows the register dialog to the given player.
     *
     * @param player    the player to show the dialog to
     * @param type      the registration type (password or email)
     * @param secondArg the secondary argument type for registration
     */
    default void showRegisterDialog(Player player, RegistrationType type, RegisterSecondaryArgument secondArg) {
    }

    /**
     * Updates an open authentication dialog after the normal command handler sends feedback.
     * This hook runs on the player's thread and does not authenticate the player.
     *
     * @param player the recipient of the authentication message
     * @param key the message that was sent
     * @param message the translated message with replacements applied
     */
    default void onAuthMessage(Player player, MessageKey key, String message) {
    }
}
