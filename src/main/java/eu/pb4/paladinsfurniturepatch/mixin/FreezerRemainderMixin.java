package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes the freezer crashing when it burns the last item of fuel.
 *
 * <p>It is the same bug the oven had, written slightly differently. The mod consumes a fuel item
 * and, when the stack runs out, asks for the item's crafting remainder:
 *
 * <pre>{@code
 * Item item2 = item.getCraftingRemainder().item().value();
 * inventory.set(1, item2 == null ? ItemStack.EMPTY : new ItemStack(item2));
 * }</pre>
 *
 * <p>Here {@code getCraftingRemainder()} returns {@code null} for every item without a remainder,
 * and the {@code .item()} call on that null is what takes the server down. The branch only runs when
 * the fuel stack runs out, which is why a full stack burns fine and a single item kills the server.
 *
 * <p>The mod's {@code item2 == null} check is on the item, not on the template, so a missing
 * template is answered with an air holder instead: that keeps the call non-null and lands the mod on
 * its own empty-stack branch. Air is then written back as {@link ItemStack#EMPTY} at the one place
 * the result is stored, since a stack of air is not a stack a fuel slot should ever hold.
 */
@Mixin(targets = "com.unlikepaladin.pfm.blocks.blockentities.FreezerBlockEntity")
public class FreezerRemainderMixin {
    @Redirect(method = "serverTick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStackTemplate;item()Lnet/minecraft/core/Holder;"))
    private static Holder<Item> pfm$remainderItem(ItemStackTemplate template) {
        return template == null ? Holder.direct(Items.AIR) : template.item();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "serverTick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/NonNullList;set(ILjava/lang/Object;)Ljava/lang/Object;"))
    private static Object pfm$emptySpentFuel(NonNullList inventory, int slot, Object stack) {
        if (stack instanceof ItemStack item && item.is(Items.AIR)) {
            return inventory.set(slot, ItemStack.EMPTY);
        }

        return inventory.set(slot, stack);
    }
}
