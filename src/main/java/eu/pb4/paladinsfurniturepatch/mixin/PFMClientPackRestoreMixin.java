package eu.pb4.paladinsfurniturepatch.mixin;

import eu.pb4.paladinsfurniturepatch.impl.PFMClientPackGuard;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Restores Fabric's real resource pack type after PFM's TAIL injection has been skipped.
 */
@Mixin(targets = "net.fabricmc.fabric.impl.resource.pack.ModResourcePackCreator", priority = 2000)
public class PFMClientPackRestoreMixin {
    @Inject(method = "loadPacks", at = @At("TAIL"))
    private void paladinsFurniturePatch$restoreFabricPackType(Consumer<Pack> consumer, CallbackInfo callback) {
        PFMClientPackGuard.restore(this);
    }
}
