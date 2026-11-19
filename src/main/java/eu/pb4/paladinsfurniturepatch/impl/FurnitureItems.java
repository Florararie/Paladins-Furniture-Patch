package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.paladinsfurniturepatch.impl.item.PolyFurnitureItem;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Makes the mod's items usable without the mod.
 *
 * <p>PFM's own creative tabs are served to Polymer clients with their real ids. Their contents and
 * icons are converted to client-safe stacks by {@link PolymerTabs} and the tab payload mixin.
 */
public final class FurnitureItems {
    private FurnitureItems() {
    }

    public static void setup() {
        int items = 0;
        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (!id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            PolyFurnitureItem.rememberModel(item, id);
            PolymerItem.registerOverlay(item, new PolyFurnitureItem(item, id));
            items++;
        }

        PolymerTabs.trackAll();
        PolymerTabs.applyIconsOnServerStart();

        PaladinsFurniturePatch.LOGGER.info("Virtualised {} items; serving PFM creative tabs.", items);
    }
}
