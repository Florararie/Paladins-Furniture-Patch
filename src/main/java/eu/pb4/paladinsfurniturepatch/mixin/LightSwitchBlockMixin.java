package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a light switch feed its redstone into the wall like a lever does.
 *
 * <p>PFM reports its direct signal out of the front face, so a lamp or wire behind the switch never
 * sees it. A lever powers the block it is attached to, which is behind the face here, so the signal
 * goes that way instead.
 */
@Mixin(targets = "com.unlikepaladin.pfm.blocks.LightSwitchBlock")
public class LightSwitchBlockMixin {
    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void directSignalThroughWall(BlockState state, BlockGetter world, BlockPos pos, Direction direction,
                                         CallbackInfoReturnable<Integer> callback) {
        boolean powered = state.getValue(BlockStateProperties.POWERED);
        boolean intoWall = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite() == direction;
        callback.setReturnValue(powered && intoWall ? 15 : 0);
    }
}
