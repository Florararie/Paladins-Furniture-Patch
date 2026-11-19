package eu.pb4.paladinsfurniturepatch.mixin;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the patch once Paladin's Furniture has built its blocks.
 *
 * <p>The mod builds its furniture from its wood and stone variants from the platform's server
 * entrypoint, which a dedicated server calls after every mod's main entrypoint and, in this version,
 * even after the server has been constructed - so neither the mod initialiser nor the server
 * starting event can see the blocks. This is the one place that is guaranteed to run after they are
 * registered and before a world is loaded, which is what the overlays and the collision stand-ins
 * need.
 */
@Mixin(targets = "com.unlikepaladin.pfm.fabric.PaladinFurnitureModFabric")
public class PaladinFurnitureModFabricMixin {
    @Inject(method = "registerLateEntries", at = @At("TAIL"))
    private static void paladinsFurniturePatch$afterRegistration(CallbackInfo callback) {
        PaladinsFurniturePatch.setupNow();
    }
}
