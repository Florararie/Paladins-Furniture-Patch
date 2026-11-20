package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.paladinsfurniturepatch.impl.item.PolyFurnitureItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Shows an office chair to a client that does not have the mod.
 *
 * <p>An office chair is not a block: the mod spawns an entity and draws the chair model in its own
 * renderer, so a vanilla client knows neither the entity type nor the model and shows nothing -
 * the chair could only be removed with a command. The entity is therefore replaced with a vanilla
 * item display, which draws a model at exactly the same position, and the model is the chair's own
 * one, tinted with the colour the chair was placed as.
 *
 * <p>Only the item display's own tracked entries are sent. The chair's mob data - health, colour,
 * and so on - would be read by the client as a display's fields, so the list is replaced rather
 * than extended.
 */
public final class PolyOfficeChair implements PolymerEntity {
    // The chair entity sits at seat height, so the model only needs lowering by the 0.05 by
    // which its own seat is above the rider's feet.
    private static final Vector3f CHAIR_OFFSET = new Vector3f(0f, -0.05f, 0f);
    private static final int POS_ROT_INTERPOLATION_TICKS = 3;

    private static boolean resolved;
    private static @Nullable EntityDataAccessor<ItemStack> stackAccessor;
    private static @Nullable EntityDataAccessor<Byte> contextAccessor;
    private static @Nullable EntityDataAccessor<Vector3fc> translationAccessor;
    private static @Nullable EntityDataAccessor<Integer> posRotInterpolationAccessor;

    private final Entity entity;

    public PolyOfficeChair(Entity entity) {
        this.entity = entity;
    }

    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return EntityType.ITEM_DISPLAY;
    }

    @Override
    public void modifyRawTrackedData(List<SynchedEntityData.DataValue<?>> data, ServerPlayer player,
                                     boolean initial) {
        resolve();
        if (stackAccessor == null) {
            return;
        }

        data.clear();
        data.add(SynchedEntityData.DataValue.create(stackAccessor, PolyFurnitureItem.officeChairStack(color())));

        // NONE keeps the chair model at its own size and around the entity. The item's other display
        // contexts shrink and offset it the way an item held in a hand or lying on the ground is.
        if (contextAccessor != null) {
            data.add(SynchedEntityData.DataValue.create(contextAccessor, ItemDisplayContext.NONE.getId()));
        }

        // An item display draws the model centred on the entity, so a chair model - which stands on
        // its own floor - had its lower half under the ground. Half a block is exactly the offset
        // between the model's centre and the chair's feet.
        if (translationAccessor != null) {
            data.add(SynchedEntityData.DataValue.create(translationAccessor, (Vector3fc) CHAIR_OFFSET));
        }

        // A display snaps to every position and rotation update unless it is given an interpolation
        // duration. The chair is moved and turned every tick while ridden, so that snapping is felt
        // as jitter. Three ticks is the span a normal entity is interpolated over.
        if (posRotInterpolationAccessor != null) {
            data.add(SynchedEntityData.DataValue.create(posRotInterpolationAccessor, POS_ROT_INTERPOLATION_TICKS));
        }
    }

    @SuppressWarnings("unchecked")
    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;

        try {
            var type = Display.ItemDisplay.class;
            var stack = type.getDeclaredField("DATA_ITEM_STACK_ID");
            stack.setAccessible(true);
            stackAccessor = (EntityDataAccessor<ItemStack>) stack.get(null);

            var context = type.getDeclaredField("DATA_ITEM_DISPLAY_ID");
            context.setAccessible(true);
            contextAccessor = (EntityDataAccessor<Byte>) context.get(null);

            var translation = Display.class.getDeclaredField("DATA_TRANSLATION_ID");
            translation.setAccessible(true);
            translationAccessor = (EntityDataAccessor<Vector3fc>) translation.get(null);

            var posRot = Display.class.getDeclaredField("DATA_POS_ROT_INTERPOLATION_DURATION_ID");
            posRot.setAccessible(true);
            posRotInterpolationAccessor = (EntityDataAccessor<Integer>) posRot.get(null);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not read the item display's tracked data", exception);
        }
    }

    private DyeColor color() {
        try {
            var value = this.entity.getClass().getMethod("getPFMColor").invoke(this.entity);
            if (value instanceof DyeColor color) {
                return color;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.debug("Could not read an office chair's colour", exception);
        }
        return DyeColor.WHITE;
    }
}
