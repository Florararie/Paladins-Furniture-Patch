package eu.pb4.paladinsfurniturepatch.mixin;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Saves a light switch's linked lights under the key it reads them back from.
 *
 * <p>PFM writes the list as {@code Items} but loads it as {@code lights}, so every switch comes back
 * from a reload with no links and has to be set up again. The list is written out again under the
 * key the loader expects.
 */
@Mixin(targets = "com.unlikepaladin.pfm.blocks.blockentities.LightSwitchBlockEntity")
public class LightSwitchBlockEntityMixin {
    @Shadow
    @Final
    private List<BlockPos> lights;

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void saveLinkedLights(ValueOutput view, CallbackInfo callback) {
        var list = view.list("lights", Codec.LONG);
        for (var pos : this.lights) {
            list.add(pos.asLong());
        }
    }
}
