package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes the oven crashing when it burns the last item of fuel.
 *
 * <p>The mod treats an item's crafting remainder as always present: it consumes the fuel, and when
 * the stack runs out it calls {@code getCraftingRemainder().create()} to put the bucket or bottle
 * back. In this Minecraft version that method returns {@code null} for every item that has no
 * remainder - coal, wood, everything an oven normally burns - so the next tick throws inside the
 * block entity ticker and takes the server down. A hopper feeding the oven is enough to hit it.
 *
 * <p>An absent remainder is simply an empty stack here, which is what the mod's own null check would
 * have produced.
 */
@Mixin(targets = "com.unlikepaladin.pfm.blocks.blockentities.OvenBlockEntity")
public class OvenRemainderMixin {
    @Unique
    private static ItemStackTemplate pfm$emptyRemainder;

    @Redirect(method = "serverTick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/Item;getCraftingRemainder()Lnet/minecraft/world/item/ItemStackTemplate;"))
    private static ItemStackTemplate pfm$nullableRemainder(Item item) {
        var remainder = item.getCraftingRemainder();
        return remainder != null ? remainder : pfm$emptyRemainder();
    }

    /**
     * An empty item stack template, built once.
     *
     * <p>Reflection because the constructor of this class is not part of the mod's own API surface;
     * if it ever changes, the oven simply keeps its fuel slot empty instead of crashing.
     */
    @Unique
    private static ItemStackTemplate pfm$emptyRemainder() {
        if (pfm$emptyRemainder != null) {
            return pfm$emptyRemainder;
        }

        for (var constructor : ItemStackTemplate.class.getDeclaredConstructors()) {
            var parameters = constructor.getParameterTypes();
            if (parameters.length == 0 || !parameters[0].isInstance(Items.AIR)) {
                continue;
            }

            var arguments = new Object[parameters.length];
            arguments[0] = Items.AIR;
            for (int i = 1; i < parameters.length; i++) {
                arguments[i] = parameters[i].isPrimitive() ? 0 : null;
            }

            try {
                constructor.setAccessible(true);
                pfm$emptyRemainder = (ItemStackTemplate) constructor.newInstance(arguments);
                return pfm$emptyRemainder;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next constructor.
            }
        }

        throw new IllegalStateException("No way to build an empty ItemStackTemplate");
    }
}
