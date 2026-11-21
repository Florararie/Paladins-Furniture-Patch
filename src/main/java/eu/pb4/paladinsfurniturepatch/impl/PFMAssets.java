package eu.pb4.paladinsfurniturepatch.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Paladin's Furniture's assets, which come from three places.
 *
 * <p>The jar holds the blockstates and models for furniture that does not vary by material - beds,
 * sofas, fridges, the coloured pieces - plus every texture. Everything that varies by wood or stone
 * variant is normally generated on a client into {@code pfm/cache/pfm-assetpack} in the game
 * directory. The generator is client code, built on {@code net.minecraft.client.data.models}, so a
 * dedicated server can never run it.
 *
 * <p>Instead of shipping a copy of that generated pack, the patch reproduces the small part that is
 * needed at runtime: {@link PFMGeneratedAssets} maps each registered furniture block to the
 * blockstate and item definition the generator would have written, and the mod's own templates in
 * the jar supply the geometry and textures. Files present in a generated pack are still used first,
 * so an admin can point the server at a newer pack without updating the patch.
 *
 * <p>The generated pack cannot be handed to a vanilla client as it is. Its blockstates and item
 * definitions name model types that only the mod's client code registers, so a client without the
 * mod throws the whole file away and draws the missing model - which is why most furniture showed up
 * untextured. {@link PFMBlockStates} rewrites those files into plain ones, and those are what goes
 * into the Polymer pack; anything else in a generated pack is copied unchanged.
 */
public final class PFMAssets {
    private static final String GENERATED_PACK = "pfm/cache/pfm-assetpack";
    private static final String BLOCKSTATE_PATH = "assets/%s/blockstates/%s.json";
    private static final String ITEM_PATH = "assets/%s/items/%s.json";
    private static final String MODEL_PATH = "assets/%s/models/%s.json";
    private static final String LANGUAGE_PATH = "assets/%s/lang/%s.json";
    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Water in furniture is drawn by a display element, and item space has no biome tint, so the
     * mod's grayscale water texture would come out white. The models therefore point at a texture
     * that already carries the colour the mod tints them with.
     */
    private static final String WATER_TEXTURE_ID = PaladinsFurniturePatch.MOD_ID + ":block/water";
    private static final String WATER_TEXTURE_PATH = "assets/" + PaladinsFurniturePatch.MOD_ID + "/textures/block/water.png";
    /**
     * Used-toilet water has its own model and texture names on purpose. Reusing a dirty path from
     * an earlier build let a client keep serving the old blue texture even after the pack changed;
     * a new path makes the used model a genuinely new resource.
     */
    private static final String USED_WATER_TEXTURE_ID = PaladinsFurniturePatch.MOD_ID + ":block/used_toilet_water";
    private static final String USED_WATER_TEXTURE_PATH =
            "assets/" + PaladinsFurniturePatch.MOD_ID + "/textures/block/used_toilet_water.png";
    private static final String TOILET_BLOCKSTATE_PATH = "assets/pfm/blockstates/basic_toilet.json";
    private static final String TOILET_STILL_MODEL = "block/basic_toilet/basic_toilet_still_water";
    private static final String TOILET_FLOWING_MODEL = "block/basic_toilet/basic_toilet_flowing_water";
    private static final String TOILET_USED_STILL_MODEL = "block/basic_toilet/basic_toilet_used_water";
    private static final String TOILET_USED_FLOWING_MODEL =
            "block/basic_toilet/basic_toilet_used_flowing_water";
    private static final int WATER_FRAMES = 32;
    private static final byte[] WATER_ANIMATION =
            "{\"animation\":{\"frametime\":2}}".getBytes(StandardCharsets.UTF_8);

    private PFMAssets() {
    }

    /** The mod's generated asset pack in the game directory, or null when there is none. */
    public static @Nullable Path generatedPack() {
        var path = FabricLoader.getInstance().getGameDir().resolve(GENERATED_PACK);
        return Files.isDirectory(path.resolve("assets")) ? path : null;
    }

    /** Reads one blockstate file, or synthesizes the one PFM's client generator would have written. */
    public static byte @Nullable [] readBlockState(Identifier block) {
        var data = read(BLOCKSTATE_PATH, block);
        if (data != null) {
            return data;
        }

        var value = BuiltInRegistries.BLOCK.get(block).map(holder -> holder.value()).orElse(null);
        return value == null ? null : PFMGeneratedAssets.blockState(value);
    }

    /** Reads one item definition, or synthesizes the one PFM's client generator would have written. */
    public static byte @Nullable [] readItem(Identifier item) {
        var data = read(ITEM_PATH, item);
        return data != null ? data : PFMGeneratedAssets.item(item);
    }

    /** Reads one of the mod's model files, for a model the patch has to split or re-centre. */
    public static byte @Nullable [] readModel(Identifier model) {
        return read(MODEL_PATH, model);
    }

    private static byte @Nullable [] read(String template, Identifier id) {
        var relative = template.formatted(id.getNamespace(), id.getPath());

        var generated = generatedPack();
        if (generated != null) {
            var file = generated.resolve(relative);
            if (Files.isRegularFile(file)) {
                try {
                    return Files.readAllBytes(file);
                } catch (IOException exception) {
                    PaladinsFurniturePatch.LOGGER.warn("Could not read {}", file, exception);
                }
            }
        }

        var mod = FabricLoader.getInstance().getModContainer(PaladinsFurniturePatch.TARGET_MOD_ID).orElse(null);
        if (mod == null) {
            return null;
        }

        var path = mod.findPath(relative);
        if (path.isEmpty()) {
            return null;
        }

        try {
            return Files.readAllBytes(path.get());
        } catch (IOException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not read {} from the mod jar", id, exception);
            return null;
        }
    }

    /**
     * Adds the mod's assets to the Polymer pack, and rewrites the generated ones on the way.
     *
     * <p>The models the server draws are item-space copies of the mod's models, which only resolve on
     * the client if the blockstates and item definitions behind them are readable. The mod's own
     * namespace is added by the mod's assets, so this only has to bring in what the mod generates.
     */
    public static void registerPackCopy() {
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(PFMAssets::addAssets);
    }

    private static void addAssets(ResourcePackBuilder builder) {
        var generated = generatedPack();
        if (generated != null) {
            PaladinsFurniturePatch.LOGGER.info("Using PFM's generated asset pack from {}", generated);
            var copied = copy(builder, generated);
            PaladinsFurniturePatch.LOGGER.info("Copied {} files from the PFM asset pack", copied);
        } else {
            PaladinsFurniturePatch.LOGGER.info(
                    "No generated PFM asset pack in {}, generating furniture assets from the registry",
                    GENERATED_PACK);
        }

        PFMBlockStates.writePack(builder);
        writeLanguage(builder);

        // Water is last on purpose: the models that use it come from the mod's own jar, which is
        // added after this builder runs, so only a task that runs once every asset is in place can
        // replace them and the texture they use.
        builder.addPreFinishTask(PFMAssets::colourWater);
    }

    /**
     * Merges the variant names PFM generates on a client into its own language file.
     *
     * <p>The jar only ships the static keys. Names such as {@code block.pfm.oak_kitchen_counter}
     * are written by {@code PFMLangProvider}, which cannot run on a dedicated server. Without them a
     * client shows the raw key in the inventory even though the item's model is correct. Polymer
     * merges language files as they are added, so this has to happen during the build rather than in
     * a pre-finish task; the mod's own file is merged into the same entry afterward.
     */
    private static void writeLanguage(ResourcePackBuilder builder) {
        var id = Identifier.fromNamespaceAndPath(PaladinsFurniturePatch.TARGET_MOD_ID, "en_us");
        var data = read(LANGUAGE_PATH, id);
        var language = new JsonObject();
        if (data != null) {
            try {
                language = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException exception) {
                PaladinsFurniturePatch.LOGGER.warn("Could not read PFM's English language file", exception);
            }
        }

        var added = PFMGeneratedAssets.addLanguageNames(language);
        builder.addStringData(LANGUAGE_PATH.formatted(PaladinsFurniturePatch.TARGET_MOD_ID, "en_us"), GSON.toJson(language));
        PaladinsFurniturePatch.LOGGER.info("Added {} generated furniture names to PFM's English language file", added);
    }

    /**
     * Copies everything except the files that get rewritten.
     *
     * <p>Blockstates and item definitions are skipped because the versions in the pack name the
     * mod's client-only model types; {@link PFMBlockStates} writes the readable replacements. The
     * pack metadata is skipped too, since Polymer owns the finished pack's {@code pack.mcmeta}.
     */
    private static int copy(ResourcePackBuilder builder, Path root) {
        var copied = 0;
        var water = false;
        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var path = root.relativize(file).toString().replace('\\', '/');
                if (path.equals("pack.mcmeta")
                        || path.startsWith("assets/" + PaladinsFurniturePatch.TARGET_MOD_ID + "/blockstates/")
                        || path.startsWith("assets/" + PaladinsFurniturePatch.TARGET_MOD_ID + "/items/")) {
                    continue;
                }

                var data = Files.readAllBytes(file);
                if (path.startsWith("assets/" + PaladinsFurniturePatch.TARGET_MOD_ID + "/models/")
                        && path.endsWith(".json")) {
                    var rewrite = rewriteWater(data);
                    if (rewrite != data) {
                        data = rewrite;
                        water = true;
                    }
                }

                builder.addData(path, data);
                copied++;
            }
        } catch (IOException exception) {
            PaladinsFurniturePatch.LOGGER.error("Could not add the PFM asset pack", exception);
        }

        return copied;
    }

    /**
     * Replaces the water the mod draws with a blue copy of it.
     *
     * <p>Furniture water is drawn by display elements, which render in item space where no biome
     * tint applies, so the mod's grayscale water texture comes out white - the toilet and the
     * bathtub looked like they were full of milk. Both the texture and every model that names it are
     * replaced, because the models come from the mod's jar and only a finished pack can override
     * them; the replacement texture has no tint index left for a client to tint it again.
     */
    private static void colourWater(ResourcePackBuilder builder) {
        var water = waterTexture(false);
        var dirtyWater = waterTexture(true);
        if (water.length == 0 || dirtyWater.length == 0) {
            return;
        }

        var rewrites = new ArrayList<String>();
        builder.forEachResource((path, resource) -> {
            if (!path.startsWith("assets/" + PaladinsFurniturePatch.TARGET_MOD_ID + "/models/")
                    || !path.endsWith(".json")) {
                return;
            }

            var data = resource.readAllBytes();
            if (data == null) {
                return;
            }
            var rewritten = rewriteWater(data);
            if (rewritten != data) {
                builder.addData(path, rewritten);
                rewrites.add(path);
            }
        });

        // Only our own copy of the texture: it is what the rewritten models name. Writing the
        // tinted texture into minecraft's namespace would recolour every ocean in the world.
        // The texture is a vertical animation strip; the mcmeta is what makes the client cycle
        // through the frames instead of showing the first one as a still image.
        addAnimated(builder, WATER_TEXTURE_PATH, water);
        // Used water is the same animation in brown, so it keeps the clean surface's translucency
        // and motion instead of turning into a flat brown lid.
        addAnimated(builder, USED_WATER_TEXTURE_PATH, dirtyWater);
        // The mod's own still and flow textures, which its jar models name before the rewrite.
        addAnimated(builder, "assets/" + PaladinsFurniturePatch.TARGET_MOD_ID + "/textures/block/water_still.png", water);
        addAnimated(builder, "assets/" + PaladinsFurniturePatch.TARGET_MOD_ID + "/textures/block/water_flow.png", water);

        // A dirty toilet uses the same water geometry as a clean one, but needs a different
        // texture; the blockstate has separate dirty and flushing models, so those model names are
        // pointed at brown copies while the clean states keep the blue surface.
        writeDirtyToilet(builder);

        PaladinsFurniturePatch.LOGGER.info("Coloured the furniture water surfaces blue and animated them ({} models)",
                rewrites.size());
    }

    /** A vertical animation strip plus the {@code .mcmeta} that makes the client cycle its frames. */
    private static void addAnimated(ResourcePackBuilder builder, String path, byte[] data) {
        builder.addData(path, data);
        builder.addData(path + ".mcmeta", WATER_ANIMATION);
    }

    private static void writeDirtyToilet(ResourcePackBuilder builder) {
        var still = readModel(Identifier.fromNamespaceAndPath(PaladinsFurniturePatch.TARGET_MOD_ID,
                TOILET_STILL_MODEL));
        var flowing = readModel(Identifier.fromNamespaceAndPath(PaladinsFurniturePatch.TARGET_MOD_ID,
                TOILET_FLOWING_MODEL));
        if (still == null || flowing == null) {
            PaladinsFurniturePatch.LOGGER.warn("Could not find the toilet water models; keeping blue toilet water");
            return;
        }

        builder.addData("assets/" + PaladinsFurniturePatch.TARGET_MOD_ID + "/models/"
                + TOILET_USED_STILL_MODEL + ".json", dirtyModel(still));
        builder.addData("assets/" + PaladinsFurniturePatch.TARGET_MOD_ID + "/models/"
                + TOILET_USED_FLOWING_MODEL + ".json", dirtyModel(flowing));

        var data = builder.getDataOrSource(TOILET_BLOCKSTATE_PATH);
        if (data == null) {
            data = readBlockState(Identifier.fromNamespaceAndPath(PaladinsFurniturePatch.TARGET_MOD_ID, "basic_toilet"));
        }
        if (data == null) {
            PaladinsFurniturePatch.LOGGER.warn("Could not find the toilet blockstate; dirty water will stay blue");
            return;
        }

        try {
            var root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            useDirtyToiletModels(root);
            builder.addData(TOILET_BLOCKSTATE_PATH, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not rewrite the toilet blockstate", exception);
        }
    }

    /**
     * Points a toilet's dirty and flushing states at the brown water models.
     *
     * <p>Two readers need this. The client gets it in the finished pack, and the patch itself reads
     * the same blockstate to decide which model each state draws on the server, because furniture is
     * drawn by display elements instead of the client's block renderer. Only rewriting the pack left
     * the server still naming the blue model, so a used toilet kept its blue surface.
     */
    static void useDirtyToiletModels(JsonObject root) {
        var multipart = root.getAsJsonArray("multipart");
        if (multipart == null) {
            return;
        }

        for (var element : multipart) {
            if (!element.isJsonObject()) {
                continue;
            }

            var part = element.getAsJsonObject();
            var state = toiletState(part.get("when"));
            if (!"dirty".equals(state) && !"flushing".equals(state)) {
                continue;
            }

            var apply = part.getAsJsonObject("apply");
            if (apply == null || !apply.has("model")) {
                continue;
            }

            var model = apply.get("model").getAsString();
            if (model.equals("pfm:" + TOILET_STILL_MODEL)) {
                apply.addProperty("model", "pfm:" + TOILET_USED_STILL_MODEL);
            } else if (model.equals("pfm:" + TOILET_FLOWING_MODEL)) {
                apply.addProperty("model", "pfm:" + TOILET_USED_FLOWING_MODEL);
            }
        }
    }

    private static String toiletState(JsonElement when) {
        if (when == null) {
            return "";
        }
        if (when.isJsonArray()) {
            for (var child : when.getAsJsonArray()) {
                var state = toiletState(child);
                if (!state.isEmpty()) {
                    return state;
                }
            }
            return "";
        }
        if (!when.isJsonObject()) {
            return "";
        }
        var object = when.getAsJsonObject();
        if (object.has("toilet") && object.get("toilet").isJsonPrimitive()) {
            return object.get("toilet").getAsString();
        }
        for (var entry : object.entrySet()) {
            var state = toiletState(entry.getValue());
            if (!state.isEmpty()) {
                return state;
            }
        }
        return "";
    }

    private static byte[] dirtyModel(byte[] original) {
        return new String(original, StandardCharsets.UTF_8)
                .replace("\"block/water_still\"", "\"" + USED_WATER_TEXTURE_ID + "\"")
                .replace("\"block/water_flow\"", "\"" + USED_WATER_TEXTURE_ID + "\"")
                .replaceAll(",\\s*\"tintindex\"\\s*:\\s*-?\\d+", "")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Points a model's water faces at the pre-tinted texture, or returns it unchanged. */
    private static byte[] rewriteWater(byte[] data) {
        var json = new String(data, StandardCharsets.UTF_8);
        if (!json.contains("block/water_still") && !json.contains("block/water_flow")) {
            return data;
        }

        return json.replace("\"block/water_still\"", "\"" + WATER_TEXTURE_ID + "\"")
                .replace("\"block/water_flow\"", "\"" + WATER_TEXTURE_ID + "\"")
                .replaceAll(",\\s*\"tintindex\"\\s*:\\s*-?\\d+", "")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A 32-frame animated water texture in vanilla's water colour.
     *
     * <p>It is translucent on purpose. The mod draws its water with an opaque grayscale texture and
     * lets the biome tint colour it; a pre-tinted opaque copy looks like a slab of blue plastic, which
     * is what the sink, the bathtub and the toilet showed. Vanilla water is roughly half transparent,
     * so the basin underneath shows through and the surface reads as water. The frames move a subtle
     * sine wave across the tile, so the surface in the basin is no longer a frozen blue square.
     *
     * <p>The dirty variant is the same strip in brown rather than a separate flat tile, so used
     * toilet water keeps the transparency and the animation of the clean surface.
     */
    private static byte[] waterTexture(boolean dirty) {
        var image = new BufferedImage(16, 16 * WATER_FRAMES, BufferedImage.TYPE_INT_ARGB);
        for (int frame = 0; frame < WATER_FRAMES; frame++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    double wave = Math.sin((x + frame * 0.55) * 0.62)
                            + Math.cos((y - frame * 0.42) * 0.51);
                    int shade = (int) Math.round(wave * 7.0);
                    int glint = ((x + y * 3 + frame * 2) % 17 == 0) ? 12 : 0;
                    int red = clamp((dirty ? 112 : 42) + shade / 2 + glint / 4);
                    int green = clamp((dirty ? 72 : 104) + shade + glint / 2);
                    int blue = clamp((dirty ? 32 : 210) + shade / 2 + glint / 2);
                    int alpha = clamp(130 + shade * 2);
                    image.setRGB(x, frame * 16 + y, alpha << 24 | red << 16 | green << 8 | blue);
                }
            }
        }

        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not build the clean water texture", exception);
            return new byte[0];
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
