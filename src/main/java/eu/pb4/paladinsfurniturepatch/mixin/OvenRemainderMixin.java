package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes the oven crashing when it burns the last item of fuel.
 *
 * <p>The mod consumes a fuel item and, when the stack runs out, puts back the item's crafting
 * remainder:
 *
 * <pre>{@code
 * ItemStack containerItem = item.getCraftingRemainder().create();
 * be.setItem(fuelIndex, containerItem == null ? ItemStack.EMPTY : containerItem);
 * }</pre>
 *
 * <p>In this Minecraft version {@code getCraftingRemainder()} returns {@code null} for every item
 * without a remainder - coal, wood, everything an oven normally burns - so the {@code create()}
 * call dereferences null and the block entity ticker throws, taking the server down. A bucket of
 * lava survives because a bucket does have a remainder. The mod null-checks the stack that
 * {@code create()} returns, but never the template itself.
 *
 * <p>Intercepting {@code create()} instead of the getter keeps the mod's own null check
 * meaningful: a missing template simply produces an empty stack, which is exactly what that check
 * would have written back into the fuel slot. Templates that do exist are created unchanged.
 */
@Mixin(targets = "com.unlikepaladin.pfm.blocks.blockentities.OvenBlockEntity")
public class OvenRemainderMixin {
    @Redirect(method = "serverTick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStackTemplate;create()Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack pfm$createRemainder(ItemStackTemplate template) {
        return template == null ? ItemStack.EMPTY : template.create();
    }
}
