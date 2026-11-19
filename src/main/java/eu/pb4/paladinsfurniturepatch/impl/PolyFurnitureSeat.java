package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Stand-in for the invisible entities the mod spawns so players can sit on chairs, sofas and cushions.
 *
 * <p>They exist only to be ridden, so the client is given an empty block display: it renders
 * nothing, but the seat still exists on the client and the player is drawn sitting on it. Without an
 * overlay an unmodded client would not know the entity type at all, and the rider would be left
 * standing.
 *
 * <p>The entity is deliberately left where the mod put it. A block display has no height, and a
 * rider is drawn at the vehicle's own position, so the player ends up exactly where the mod's own
 * seat entity would have put them. Moving it - even only to look better - is not safe: the mod's
 * chair entity checks the block underneath itself every tick and ejects its rider and removes itself
 * when that block is not furniture, so lifting it half a block lands it in the air above the chair
 * and ends the ride on the next tick.
 */
public final class PolyFurnitureSeat implements PolymerEntity {
    private final Entity entity;

    public PolyFurnitureSeat(Entity entity) {
        this.entity = entity;
    }

    @Override
    public EntityType<?> getPolymerEntityType(PacketContext context) {
        return EntityType.BLOCK_DISPLAY;
    }
}
