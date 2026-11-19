package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.paladinsfurniturepatch.impl.item.PolyFurnitureItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Shows a dropped piece of furniture to a client without the mod.
 *
 * <p>An item entity is drawn from the stack in its own tracked data, and that stack is sent as it is
 * - the item conversion that turns furniture into paper never runs for it. A dropped lamp was
 * therefore an item the client did not know and rendered as the missing model, while the same lamp
 * looked right in the inventory and in the world.
 *
 * <p>Only the item entry of the tracked data is replaced, and only for the mod's own items, so
 * everything else about the entity stays what the server says it is.
 */
public final class PolyFurnitureDroppedItem implements PolymerEntity {
    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return EntityType.ITEM;
    }

    @Override
    public void modifyRawTrackedData(List<SynchedEntityData.DataValue<?>> data, ServerPlayer player,
                                     boolean initial) {
        for (int i = 0; i < data.size(); i++) {
            var entry = data.get(i);
            // An item entity's only item stack entry is the one the client draws; its accessor is
            // private in the entity class, so it is recognised by its serializer instead.
            if (entry.serializer() != EntityDataSerializers.ITEM_STACK
                    || !(entry.value() instanceof ItemStack stack)) {
                continue;
            }

            var converted = PolyFurnitureItem.toClientStack(stack);
            if (converted != stack) {
                data.set(i, replace(entry, converted));
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SynchedEntityData.DataValue<?> replace(SynchedEntityData.DataValue<?> entry, ItemStack stack) {
        return new SynchedEntityData.DataValue(entry.id(), entry.serializer(), stack);
    }
}
