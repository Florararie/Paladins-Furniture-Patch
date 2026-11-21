package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Set;

/**
 * Makes PFM's custom sounds playable by a vanilla client.
 *
 * <p>A vanilla client has no registry entry for {@code pfm:microwave_beep} or
 * {@code pfm:microwave_running}; sending the registry holder disconnects the player. The sound
 * itself is a resource-pack asset, though - PFM ships its {@code sounds.json} and the ogg files, and
 * both are already in the Polymer pack - so the holder is sent as a direct sound event with the same
 * id instead. The client resolves that id through {@code sounds.json} and plays PFM's actual audio.
 * Only the four sounds PFM ships are sent this way; anything else still falls back to a vanilla
 * sound rather than risking an id the pack does not contain.
 */
@Mixin(ServerLevel.class)
public class SoundPacketMixin {
    private static final Set<String> PACK_SOUNDS = Set.of(
            "microwave_beep", "microwave_running", "toilet_flushing", "toilet_used");
    @ModifyVariable(
            method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"),
            argsOnly = true)
    private static Holder<SoundEvent> paladinsFurniturePatch$vanillaSound(Holder<SoundEvent> sound) {
        if (sound == null) {
            return null;
        }

        var id = sound.value().location();
        if (!id.getNamespace().equals("pfm")) {
            return sound;
        }

        if (PACK_SOUNDS.contains(id.getPath())) {
            return Holder.direct(SoundEvent.createVariableRangeEvent(id));
        }

        return SoundEvents.UI_BUTTON_CLICK;
    }
}
