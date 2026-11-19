package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.paladinsfurniturepatch.impl.item.PolyFurnitureItem;
import eu.pb4.polymer.core.impl.InternalServerRegistry;
import eu.pb4.polymer.core.mixin.CreativeModeTabAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes Paladin's Furniture's own creative tabs reach clients that do not have the mod.
 *
 * <p>Polymer only sends the tab definition - the packet that makes the client create the tab - for
 * tabs it knows about in {@code PolymerCreativeModeTabUtils.REGISTRY}. PFM registers its tabs in the
 * vanilla registry during mod initialisation, so they are not in Polymer's registry and a client
 * without the mod has nowhere to put their contents. Adding the real tabs to the registry keeps
 * their own ids, so a client that does have PFM keeps using PFM's tabs instead of growing duplicates.
 *
 * <p>The tab icons and contents are raw PFM stacks. The contents are converted by the payload mixin;
 * the icon has to be replaced on the tab itself, because Polymer writes it into the definition with
 * the plain item stack codec. A paper stack carrying the furniture item model is decodable by a
 * vanilla client and still shows the real icon.
 */
public final class PolymerTabs {
    private static final Logger LOGGER = LoggerFactory.getLogger("paladins-furniture-patch/tabs");
    private static final List<CreativeModeTab> TABS = new ArrayList<>();

    private PolymerTabs() {
    }

    public static void trackAll() {
        for (var tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            var key = BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElse(null);
            if (key == null || !key.identifier().getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }
            if (TABS.contains(tab)) {
                continue;
            }

            TABS.add(tab);
            InternalServerRegistry.ITEM_GROUPS.set(key.identifier(), tab);
            LOGGER.info("Serving creative tab {} to Polymer clients", key.identifier());
        }
    }

    /**
     * Item components are only available after the registries are bound, so icons are swapped when
     * the server has finished starting, as the iron chest patch does.
     */
    public static void applyIconsOnServerStart() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> applyClientIcons());
    }

    private static void applyClientIcons() {
        for (var tab : TABS) {
            var current = tab.getIconItem();
            if (current.isEmpty()) {
                continue;
            }

            var id = BuiltInRegistries.ITEM.getKey(current.getItem());
            if (!id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            // The guidebook is intentionally not offered, so do not leave it as a tab icon either.
            var replacement = isGuidebook(current)
                    ? new ItemStack(Items.CRAFTING_TABLE)
                    : PolyFurnitureItem.toClientStack(current);
            if (replacement == current || replacement.isEmpty()) {
                continue;
            }

            ((CreativeModeTabAccessor) tab).setIconItemStack(replacement);
            LOGGER.info("Creative tab icon {} -> {}", id, BuiltInRegistries.ITEM.getKey(replacement.getItem()));
        }
    }

    public static boolean isGuidebook(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)
                && id.getPath().equals("furniture_book");
    }
}
