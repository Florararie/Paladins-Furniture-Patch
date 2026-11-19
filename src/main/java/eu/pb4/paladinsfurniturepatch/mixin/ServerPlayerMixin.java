package eu.pb4.paladinsfurniturepatch.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.paladinsfurniturepatch.impl.ui.FurnitureGui;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Shows the mod's containers through SGui screens a vanilla client can read.
 *
 * <p>Paladin's Furniture registers its menus on the server, so the client's menu registry has no
 * entry for them and the open-screen packet fails to decode - that is the protocol error that kicks
 * a player out. The menu itself is still created here, which is what we want: it holds the block
 * entity's slots and the mod's own craft logic. It is handed to {@link FurnitureGui}, which puts
 * those very slots on a chest-sized grid and paints the mod's own art over the chest background.
 *
 * <p>Opening is done by the screen itself, because the menu the client is told about is not the one
 * the mod created; the mod's menu is only ever used for its slots.
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {
    @Inject(method = "openMenu", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
            shift = At.Shift.BEFORE), cancellable = true)
    private void openFurnitureMenu(MenuProvider provider, CallbackInfoReturnable<OptionalInt> callback,
                                   @Local AbstractContainerMenu menu) {
        var id = BuiltInRegistries.MENU.getKey(menu.getType());
        if (id == null || !id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
            return;
        }

        var player = (ServerPlayer) (Object) this;
        var path = id.getPath();
        // PFM registers the workbench menu as pfm:furniture, not pfm:workbench. The source class
        // name is the reliable fallback for any other version that renames it again.
        boolean workbench = path.contains("workbench") || path.equals("furniture")
                || menu.getClass().getSimpleName().toLowerCase().contains("workbench");

        var gui = FurnitureGui.open(player, menu, path, workbench, provider.getDisplayName());
        if (gui == null) {
            // Nothing sensible to show; close what was created rather than send a menu type the
            // client cannot decode.
            menu.removed(player);
            callback.setReturnValue(OptionalInt.empty());
            return;
        }

        PaladinsFurniturePatch.LOGGER.info("Opening {} for {} as an SGui screen",
                id, player.getScoreboardName());
        callback.setReturnValue(OptionalInt.of(gui.getSyncId()));
    }
}
