package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PFM's guidebook opens a Patchouli book, and Patchouli is not installed on these clients. The book
 * is therefore not granted on first join and is filtered out of the creative tab contents.
 */
@Mixin(targets = "com.unlikepaladin.pfm.advancements.GiveGuideBookCriterion")
public class GuidebookMixin {
    @Inject(method = "trigger", at = @At("HEAD"), cancellable = true)
    private void paladinsFurniturePatch$skipGuidebook(ServerPlayer player, CallbackInfo callback) {
        callback.cancel();
    }
}
