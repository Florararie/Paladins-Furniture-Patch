package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Turns a classic nightstand's drawer towards the player who puts it down.
 *
 * <p>The mod gives the block a facing property and a model for every direction, but never sets the
 * property on placement: a nightstand always keeps the default, so every one of them points the same
 * way. The facing is filled in here the way every other piece of furniture does it - the side the
 * player is standing on.
 */
@Mixin(targets = "com.unlikepaladin.pfm.blocks.ClassicNightstandBlock")
public abstract class ClassicNightstandBlockMixin {
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var state = ((Block) (Object) this).defaultBlockState();
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state;
        }

        return state.setValue(BlockStateProperties.HORIZONTAL_FACING,
                context.getHorizontalDirection().getOpposite());
    }
}
