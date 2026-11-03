package fr.xephi.authme.platform;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.lang.ref.WeakReference;
import java.util.List;

/** Renders post-join forms without validating credentials or granting authentication. */
final class PaperDialogHelper {

    private static final LegacyComponentSerializer TEXT = LegacyComponentSerializer.legacySection();

    private PaperDialogHelper() {
    }

    /** Shows a form with single-use navigation bound to its current window. */
    static void show(Player player, PostJoinDialog form, NavigationHandler submit) {
        List<DialogInput> inputs = form.fields().stream()
            .map(field -> (DialogInput) DialogInput.text(field.key(), TEXT.deserialize(field.label()))
                .maxLength(field.maxLength()).build())
            .toList();
        List<ActionButton> buttons = form.buttons().stream()
            .map(button -> button(player, form, button, submit))
            .toList();
        PostJoinDialog.Button exit = new PostJoinDialog.Button(form.closeLabel(), PostJoinDialog.Screen.CHAT, List.of());
        DialogBase base = DialogBase.builder(TEXT.deserialize(form.title()))
            .body(List.of(DialogBody.plainMessage(TEXT.deserialize(form.description()))))
            .inputs(inputs)
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(base)
            .type(DialogType.multiAction(buttons).columns(1).exitAction(button(player, form, exit, submit)).build())));
    }

    /** Uses native actions for credentials and weakly bound callbacks for navigation only. */
    private static ActionButton button(Player player, PostJoinDialog form, PostJoinDialog.Button button,
                                       NavigationHandler navigate) {
        DialogAction action;
        if (button.target() == null) {
            action = DialogAction.commandTemplate(commandTemplate(form, button));
        } else {
            WeakReference<Player> owner = new WeakReference<>(player);
            action = DialogAction.customClick((view, audience) -> {
                if (audience instanceof Player actor && actor == owner.get()) {
                    navigate.accept(actor, button);
                }
            }, ClickCallback.Options.builder().uses(1).build());
        }
        return ActionButton.builder(TEXT.deserialize(button.label())).action(action).build();
    }

    /** Builds a fixed command template. Only native input placeholders reach the normal command pipeline. */
    static String commandTemplate(PostJoinDialog form, PostJoinDialog.Button button) {
        StringBuilder command = new StringBuilder(String.join(" ", button.command()));
        for (PostJoinDialog.Field field : form.fields()) {
            command.append(" $(").append(field.key()).append(')');
        }
        return command.toString();
    }

    @FunctionalInterface
    interface NavigationHandler {
        void accept(Player player, PostJoinDialog.Button button);
    }
}
