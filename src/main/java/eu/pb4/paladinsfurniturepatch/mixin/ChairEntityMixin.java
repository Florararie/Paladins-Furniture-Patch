package eu.pb4.paladinsfurniturepatch.mixin;

import eu.pb4.paladinsfurniturepatch.impl.SafeDismount;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes PFM's chair and stool seats dismount with the safe logic PolyDecorations uses. */
@Mixin(targets = "com.unlikepaladin.pfm.entity.ChairEntity")
public class ChairEntityMixin {
    @Inject(method = "getDismountLocationForPassenger", at = @At("HEAD"), cancellable = true)
    private void paladinsFurniturePatch$safeDismount(LivingEntity passenger,
                                                     CallbackInfoReturnable<Vec3> callback) {
        var self = (Entity) (Object) this;
        var level = self.level();
        var pos = self.blockPosition();
        var state = level.getBlockState(pos);

        var direction = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()
                : passenger.getDirection();

        callback.setReturnValue(SafeDismount.find(level, pos, direction, passenger));
    }
}
