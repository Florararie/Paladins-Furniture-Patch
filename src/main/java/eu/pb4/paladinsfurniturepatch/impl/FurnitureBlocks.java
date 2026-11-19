package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.other.PolymerMenuUtils;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Puts a Polymer overlay on everything Paladin's Furniture adds.
 *
 * <p>Blocks are found by namespace rather than by referencing the mod's classes, so furniture that
 * ships later is picked up without touching this patch.
 */
public final class FurnitureBlocks {
    private FurnitureBlocks() {
    }

    public static void setup() {
        // Collision stand-ins have to exist before the first block state is mapped to one.
        FurnitureShapes.setup();

        int blocks = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            var overlay = new FurnitureBlockOverlay();
            PolymerBlock.registerOverlay(block, overlay);
            BlockWithElementHolder.registerOverlay(block, overlay);
            blocks++;
        }

        // The mod's block entities cannot exist on a client without the mod, and the display
        // elements replace everything their renderers drew.
        int blockEntities = 0;
        for (var type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            if (!id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            PolymerBlockUtils.registerBlockEntity(type);
            blockEntities++;
        }

        // Menus are registered so the client never has to know a menu id it cannot map.
        int menus = 0;
        for (var type : BuiltInRegistries.MENU) {
            var id = BuiltInRegistries.MENU.getKey(type);
            if (!id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            PolymerMenuUtils.registerType(type);
            menus++;
        }

        // Chairs and office chairs are invisible entities that exist to be ridden. An office chair
        // also carries the chair model itself, which only an item display can show.
        int seats = 0;
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            if (id.getPath().equals("office_chair")) {
                PolymerEntityUtils.registerOverlay(type, PolyOfficeChair::new);
            } else {
                registerSeat(type);
            }
            seats++;
        }

        // A display has no box to click, so the office chair's clicks are given to an interaction
        // entity and forwarded to the chair.
        OfficeChairHitboxes.setup();

        // A dropped piece of furniture is drawn from the stack inside the item entity, which never
        // passes through the item conversion; without this a dropped lamp is an item the client
        // cannot draw and shows as the missing model.
        //
        // This registers a constructor, not an overlay: registerOverlay also marks the type as
        // server-side-only and replaces it with minecraft:marker, and a marker has eight data entries
        // where an item entity has nine. The client then received the item stack entry (id 8) for an
        // entity with only eight entries and dropped the player with a Network Protocol Error.
        PolymerEntityUtils.registerPolymerEntityConstructor(EntityType.ITEM, entity -> new PolyFurnitureDroppedItem());

        PaladinsFurniturePatch.LOGGER.info("Virtualised {} furniture blocks, {} block entity types, {} seats and {} menus.",
                blocks, blockEntities, seats, menus);
    }

    private static <T extends Entity> void registerSeat(EntityType<T> type) {
        PolymerEntityUtils.registerOverlay(type, PolyFurnitureSeat::new);
    }
}
