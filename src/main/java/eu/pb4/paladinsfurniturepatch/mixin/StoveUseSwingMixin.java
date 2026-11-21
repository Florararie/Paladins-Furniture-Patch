package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swings the player's hand when a stove opens.
 *
 * <p>The stove opens from {@code useItemOn} rather than {@code useWithoutItem}: it has to inspect
 * the held item first, then falls back to opening the oven. That open branch returns
 * {@link InteractionResult#CONSUME}, so the click is consumed without the arm moving. The swing is
 * sent right after the container is opened, rather than changing every CONSUME result, so placing
 * or failing to place an item does not add an extra animation.
 */
@Mixin(targets = "com.unlikepaladin.pfm.blocks.StoveBlock")
public class StoveUseSwingMixin {
    @Inject(method = "useItemOn", at = @At(value = "INVOKE",
            target = "Lcom/unlikepaladin/pfm/blocks/StoveBlock;openContainer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V",
            shift = At.Shift.AFTER))
    private void paladinsFurniturePatch$swingStove(ItemStack stack, BlockState state, Level world,
                                                   BlockPos pos, Player player, InteractionHand hand,
                                                   BlockHitResult hit,
                                                   CallbackInfoReturnable<InteractionResult> callback) {
        player.swing(hand, true);
    }
}
