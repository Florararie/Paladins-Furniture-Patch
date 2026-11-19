package eu.pb4.paladinsfurniturepatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the mod's microwave update packet from killing the client.
 *
 * <p>The mod sends {@code MicrowaveUpdatePayload} to every player whenever a microwave starts or
 * stops. Its client handler casts the block entity at the position to {@code MicrowaveBlockEntity}
 * before checking whether the microwave's own screen is open. That cast is safe with the mod alone
 * because the client was told to create that block entity type - but this patch replaces every
 * furniture block entity with a vanilla donor type, so the client holds a campfire there and the
 * handler dies with a {@code ClassCastException} in a render-thread task.
 *
 * <p>The packet only drives the microwave's own screen (the spinning item and its timer), and that
 * screen is never opened here: functional containers open as SGui panels instead, and the patch
 * mirrors the spinning item with a display element. Dropping the packet is therefore what the mod
 * would do anyway after its screen check.
 */
@Mixin(targets = "com.unlikepaladin.pfm.networking.MicrowaveUpdatePayload")
public class MicrowaveUpdateMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void pfm$ignoreWithoutMicrowaveScreen(CallbackInfo callback) {
        callback.cancel();
    }
}
