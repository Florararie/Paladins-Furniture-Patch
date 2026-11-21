package eu.pb4.paladinsfurniturepatch.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a vanilla client see the hand-use animation on PFM's clickable blocks.
 *
 * <p>These blocks return {@link InteractionResult#CONSUME} on the server, which consumes the click
 * but does not send the swing packet. A crafting table returns {@code SUCCESS_SERVER}, which makes
 * the client swing while the server still opens the menu, so the same result is used here.
 */
@Mixin(targets = {
        "com.unlikepaladin.pfm.blocks.BasicShowerHandleBlock",
        "com.unlikepaladin.pfm.blocks.LightSwitchBlock",
        "com.unlikepaladin.pfm.blocks.WorkingTableBlock"
})
public class UseSwingMixin {
    @ModifyReturnValue(method = "useWithoutItem", at = @At("RETURN"))
    private InteractionResult paladinsFurniturePatch$swing(InteractionResult original) {
        return original == InteractionResult.CONSUME ? InteractionResult.SUCCESS_SERVER : original;
    }
}
