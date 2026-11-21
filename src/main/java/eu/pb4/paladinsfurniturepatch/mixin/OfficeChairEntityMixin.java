package eu.pb4.paladinsfurniturepatch.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import eu.pb4.paladinsfurniturepatch.impl.SafeDismount;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the same safe dismount to office chairs, and seats the rider on the cushion.
 *
 * <p>The chair entity is held at the height of its own cushion, and a vanilla client draws a rider
 * at the seat it is given - the item display that replaces the chair - so the rider needs no offset
 * at all. The mod offsets its rider by 0.15 because its own renderer draws the chair lower than the
 * entity; that offset is dropped here so the two ends agree.
 */
@Mixin(targets = "com.unlikepaladin.pfm.entity.OfficeChairEntity")
public class OfficeChairEntityMixin {
    @Inject(method = "getDismountLocationForPassenger", at = @At("HEAD"), cancellable = true)
    private void paladinsFurniturePatch$safeDismount(LivingEntity passenger,
                                                     CallbackInfoReturnable<Vec3> callback) {
        var self = (Entity) (Object) this;
        callback.setReturnValue(SafeDismount.find(self.level(), self.blockPosition(),
                self.getDirection(), passenger));
    }

    @ModifyReturnValue(method = "getPassengerRidingPosition", at = @At("RETURN"))
    private Vec3 paladinsFurniturePatch$seatRider(Vec3 original) {
        return new Vec3(original.x, 0.0, original.z);
    }
}
