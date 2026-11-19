package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps PFM's custom sound events out of clientbound sound packets.
 *
 * <p>A vanilla client has no registry entry for {@code pfm:microwave_beep} or
 * {@code pfm:microwave_running}; receiving one makes the sound packet fail to decode and disconnects
 * the player. The microwave is the only furniture that plays these sounds while it runs, but the
 * substitution is applied to every PFM sound so a future block cannot reintroduce the same crash.
 */
@Mixin(ServerLevel.class)
public class SoundPacketMixin {
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

        return switch (id.getPath()) {
            case "microwave_beep" -> SoundEvents.NOTE_BLOCK_PLING;
            case "microwave_running" -> Holder.direct(SoundEvents.FURNACE_FIRE_CRACKLE);
            default -> SoundEvents.UI_BUTTON_CLICK;
        };
    }
}
