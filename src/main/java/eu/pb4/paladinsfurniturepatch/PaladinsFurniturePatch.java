package eu.pb4.paladinsfurniturepatch;

import eu.pb4.paladinsfurniturepatch.impl.FurnitureBlocks;
import eu.pb4.paladinsfurniturepatch.impl.FurnitureItems;
import eu.pb4.paladinsfurniturepatch.impl.FurnitureModels;
import eu.pb4.paladinsfurniturepatch.impl.FurnitureShapes;
import eu.pb4.paladinsfurniturepatch.impl.PFMAssets;
import eu.pb4.paladinsfurniturepatch.impl.ui.GuiPack;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.extras.api.ResourcePackExtras;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes Paladin's Furniture work for clients that do not have it.
 *
 * <p>The mod is a large set of blocks whose shapes and textures are picked per wood and stone
 * variant. The real blocks stay on the server, the client is shown an invisible vanilla state of
 * matching size, and a display element attached to the block draws the mod's own model for the
 * current state - the same arrangement the Skniro's Furniture patch uses.
 *
 * <p>What makes this mod harder is where its assets live. Only the furniture that does not vary by
 * material ships blockstates in the jar: the per-variant blockstates and models are generated at
 * runtime by {@code PFMBlockstateModelProvider}, which is client code (it is built on
 * {@code net.minecraft.client.data.models}), so a dedicated server can never run it. The patch
 * therefore reproduces the generated blockstate/item table from the live block registry and feeds
 * the result straight into the Polymer pack. A generated asset pack in the game directory is used
 * instead when one is present, and the mod's static assets still come from its jar. No generated
 * JSON files are shipped in this patch; see {@link PFMAssets} for what is reported at startup.
 *
 * <p>Its furniture is also not in the block registry when mod initialisers run: the mod builds its
 * blocks from the wood and stone variants from its server entrypoint, so the patch does its registry
 * work when the server starts.
 */
public final class PaladinsFurniturePatch implements ModInitializer {
    public static final String MOD_ID = "paladins_furniture_patch";
    public static final String TARGET_MOD_ID = "pfm";
    public static final Logger LOGGER = LoggerFactory.getLogger("paladins-furniture-patch");

    private static boolean setUp;

    @Override
    public void onInitialize() {
        PolymerResourcePackUtils.addModAssets(MOD_ID);
        PolymerResourcePackUtils.addModAssets(TARGET_MOD_ID);

        // Item display elements render block models as items, which needs item-space copies of the
        // mod's block models. This asks Polymer to generate one for every model in that folder.
        ResourcePackExtras.forDefault().addBridgedModelsFolder(Identifier.fromNamespaceAndPath(TARGET_MOD_ID, "block"));

        PFMAssets.registerPackCopy();
        FurnitureShapes.registerPackEvents();
        GuiPack.setup();

        // Paladin's Furniture adds its furniture from the platform's server entrypoint - that is
        // where it reads its wood and stone variants and builds the blocks from them - and a
        // dedicated server runs every mod's main entrypoint first, so the block registry is still
        // empty here. The mixin on that entrypoint does the work; this is the safety net for a
        // server that somehow starts without it.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> setupNow());
    }

    /**
     * Registers the overlays once the mod's blocks exist.
     *
     * <p>Called from the hook on Paladin's Furniture's own registration and, as a fallback, when a
     * server starts. Guarded because both happen on the same server start, and because leaving and
     * re-entering a single player world starts a server again.
     */
    public static void setupNow() {
        if (setUp) {
            return;
        }
        setUp = true;

        FurnitureModels.setup();
        FurnitureBlocks.setup();
        FurnitureItems.setup();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
