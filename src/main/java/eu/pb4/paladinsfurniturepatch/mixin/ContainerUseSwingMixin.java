package eu.pb4.paladinsfurniturepatch.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sends the hand-use animation when a PFM container opens.
 *
 * <p>PFM's container blocks return {@link InteractionResult#CONSUME} after calling
 * {@code player.openMenu(...)}. That consumes the click but does not request a swing, so the
 * player's arm stays still even though the container opened. A vanilla container returns
 * {@code SUCCESS_SERVER}, which asks the server to send the swing while still consuming the action.
 */
@Mixin(targets = {
        "com.unlikepaladin.pfm.blocks.BasicDeskCabinetBlock",
        "com.unlikepaladin.pfm.blocks.ClassicDeskCabinetBlock",
        "com.unlikepaladin.pfm.blocks.FridgeBlock",
        "com.unlikepaladin.pfm.blocks.XboxFridgeBlock",
        "com.unlikepaladin.pfm.blocks.KitchenCabinetBlock",
        "com.unlikepaladin.pfm.blocks.KitchenDrawerBlock",
        "com.unlikepaladin.pfm.blocks.KitchenWallDrawerSmallBlock",
        "com.unlikepaladin.pfm.blocks.TrashcanBlock",
        "com.unlikepaladin.pfm.blocks.InnerTrashcanBlock"
})
public class ContainerUseSwingMixin {
    @ModifyReturnValue(method = "useWithoutItem", at = @At("RETURN"))
    private InteractionResult paladinsFurniturePatch$swingContainer(InteractionResult original) {
        return original == InteractionResult.CONSUME ? InteractionResult.SUCCESS_SERVER : original;
    }
}
