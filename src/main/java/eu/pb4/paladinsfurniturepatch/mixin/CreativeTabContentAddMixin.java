package eu.pb4.paladinsfurniturepatch.mixin;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.paladinsfurniturepatch.impl.PolymerTabs;
import eu.pb4.paladinsfurniturepatch.impl.item.PolyFurnitureItem;
import eu.pb4.polymer.core.impl.networking.payloads.s2c.PolymerCreativeTabContentAddS2CPayload;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns furniture stacks into client-readable ones before a creative tab's contents are sent.
 *
 * <p>Only PFM stacks need converting; every other mod's stack is returned unchanged by
 * {@link PolyFurnitureItem#toClientStack(ItemStack)}. Do not skip or empty another mod's tab just
 * because its items use their own models or components.
 */
@Mixin(targets = "eu.pb4.polymer.core.impl.networking.payloads.s2c.PolymerCreativeTabContentAddS2CPayload")
public class CreativeTabContentAddMixin {
    @Inject(method = "of", at = @At("RETURN"), cancellable = true)
    private static void convertFurnitureStacks(int version, CreativeModeTab group,
                                               ServerGamePacketListenerImpl handler,
                                               CallbackInfoReturnable<PolymerCreativeTabContentAddS2CPayload> callback) {
        var payload = callback.getReturnValue();
        var context = handler.getPacketContext();
        var lookup = handler.player.level().registryAccess();
        callback.setReturnValue(new PolymerCreativeTabContentAddS2CPayload(payload.groupId(),
                convert(payload.stacksMain(), context, lookup), convert(payload.stacksSearch(), context, lookup)));
    }

    private static List<PolymerCreativeTabContentAddS2CPayload.Entry> convert(
            List<PolymerCreativeTabContentAddS2CPayload.Entry> entries, PacketContext context,
            HolderLookup.Provider lookup) {
        var converted = new ArrayList<PolymerCreativeTabContentAddS2CPayload.Entry>(entries.size());
        for (var entry : entries) {
            var stacks = new ArrayList<ItemStack>(entry.stacks().size());
            for (var stack : entry.stacks()) {
                if (!PolymerTabs.isGuidebook(stack)) {
                    stacks.add(PolyFurnitureItem.toClientStack(stack, context, lookup));
                }
            }
            var relative = PolymerTabs.isGuidebook(entry.relative())
                    ? ItemStack.EMPTY
                    : PolyFurnitureItem.toClientStack(entry.relative(), context, lookup);
            converted.add(new PolymerCreativeTabContentAddS2CPayload.Entry(entry.mode(), relative, stacks));
        }
        return converted;
    }
}
