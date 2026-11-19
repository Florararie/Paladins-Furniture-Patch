package eu.pb4.paladinsfurniturepatch.mixin;

import eu.pb4.paladinsfurniturepatch.impl.PFMClientPackGuard;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Runs before PFM's own TAIL injection on Fabric's resource pack creator and hides the
 * client-resource pack type from it on a dedicated server.
 */
@Mixin(targets = "net.fabricmc.fabric.impl.resource.pack.ModResourcePackCreator", priority = 500)
public class PFMClientPackGuardMixin {
    @Inject(method = "loadPacks", at = @At("TAIL"))
    private void paladinsFurniturePatch$hidePFMClientPack(Consumer<Pack> consumer, CallbackInfo callback) {
        PFMClientPackGuard.hideFromPFM(this);
    }
}
