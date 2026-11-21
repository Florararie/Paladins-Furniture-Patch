package eu.pb4.paladinsfurniturepatch.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The per-variant blockstates and item definitions Paladin's Furniture normally generates on a
 * client.
 *
 * <p>The mod's generator is built on {@code net.minecraft.client.data.models}, which does not exist
 * in a dedicated server jar, so it cannot be called there. The files it produces are, however, just
 * a small table: every family of furniture maps a facing or axis to one model type, and the model
 * type is resolved to a real model by {@link PFMBlockStates}. This class reproduces that table from
 * the live block registry, which also makes furniture from newer wood-mod variants work without a
 * patch update.
 *
 * <p>The family names and rotations are the ones PFM's own generated files use. Only the files that
 * are absent from the mod's jar are synthesized; static blockstates and item definitions still come
 * from the jar first.
 */
public final class PFMGeneratedAssets {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String NS = "pfm";
    /** PFM's English names with the generated ones merged in, read once for searching. */
    private static JsonObject LANGUAGE;

    /**
     * The names PFM's client language generator produces for its variant furniture.
     *
     * <p>The keys are already the block description ids, so only families that the mod renames
     * need an entry here. The rest are made from the path: {@code oak_kitchen_counter} becomes
     * "Oak Kitchen Counter". The list is longest-suffix first so {@code table_modern_dinner} is
     * not mistaken for {@code table_dinner}.
     */
    private static final List<NameRule> NAMES = List.of(
            new NameRule("_kitchen_wall_small_drawer", "%1$s Kitchen Small Cabinet", false),
            new NameRule("_kitchen_counter_oven", "%1$s Kitchen Oven", false),
            new NameRule("_kitchen_wall_counter", "%1$s Kitchen Filler Cabinet", false),
            new NameRule("_kitchen_wall_drawer", "%1$s Kitchen Cabinet", false),
            new NameRule("_kitchen_counter", "%1$s Kitchen Counter", false),
            new NameRule("_kitchen_cabinet", "%1$s Wall Cabinet", false),
            new NameRule("_kitchen_drawer", "%1$s Kitchen Drawer", false),
            new NameRule("_kitchen_sink", "%1$s Kitchen Sink", false),
            new NameRule("_desk_cabinet_basic", "Basic %1$s Desk Cabinet", false),
            new NameRule("_desk_cabinet_classic", "Classic %1$s Desk Cabinet", false),
            new NameRule("_desk_basic", "Basic %1$s Desk", false),
            new NameRule("_desk_classic", "Classic %1$s Desk", false),
            new NameRule("_coffee_table_basic", "Basic %1$s Coffee Table", false),
            new NameRule("_coffee_table_classic", "Classic %1$s Coffee Table", false),
            new NameRule("_coffee_table_modern", "Modern %1$s Coffee Table", false),
            new NameRule("_table_modern_dinner", "%1$s Modern Dining Table", false),
            new NameRule("_raw_table_log", "Raw %1$s Log Table", false),
            new NameRule("_table_natural", "%1$s Natural Table", false),
            new NameRule("_table_dinner", "%1$s Dining Table", false),
            new NameRule("_table_basic", "Basic %1$s Table", false),
            new NameRule("_table_classic", "Classic %1$s Table", false),
            new NameRule("_table_log", "%1$s Log Table", false),
            new NameRule("_chair_classic", "Classic %1$s Chair", false),
            new NameRule("_chair_dinner", "Dining %1$s Chair", false),
            new NameRule("_chair_modern", "Modern %1$s Chair", false),
            new NameRule("_chair", "Basic %1$s Chair", false),
            new NameRule("_simple_stool", "%1$s Simple Stool", false),
            new NameRule("_classic_stool", "%1$s Classic Stool", false),
            new NameRule("_modern_stool", "%1$s Modern Stool", false),
            new NameRule("_log_stool", "%1$s Log Stool", false),
            new NameRule("_classic_nightstand", "%1$s Classic Nightstand", false),
            new NameRule("_simple_bunk_ladder", "%1$s Simple Bunk Ladder", false),
            new NameRule("_simple_bed", "%1$s %2$s Simple Bed", true),
            new NameRule("_classic_bed", "%1$s %2$s Classic Bed", true),
            new NameRule("_herringbone_planks", "%1$s Herringbone Planks", false));

    private PFMGeneratedAssets() {
    }

    /**
     * Adds the translations for every furniture block and item whose key the mod's jar does not
     * define. PFM generates these names on a client; a dedicated server cannot run that generator,
     * and without them a vanilla client shows the raw {@code block.pfm.*} key in every inventory.
     */
    static int addLanguageNames(JsonObject language) {
        var added = 0;
        var woods = new TreeSet<String>();
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals(NS)) {
                continue;
            }

            var path = id.getPath();
            if (!path.startsWith("stripped_") && path.endsWith("_herringbone_planks")) {
                woods.add(path.substring(0, path.length() - "_herringbone_planks".length()));
            }
            added += addLanguageName(language, block.getDescriptionId(), path);
        }

        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (id.getNamespace().equals(NS)) {
                added += addLanguageName(language, item.getDescriptionId(), id.getPath());
            }
        }

        // Lamps and office chairs are one item each, with the colour and wood carried as components.
        // Their generated keys are what the item-name component names, so the item registry alone
        // does not produce them.
        for (var color : DyeColor.values()) {
            var colorName = color.getSerializedName();
            added += addExactName(language, "block.pfm." + colorName + "_office_chair",
                    title(colorName) + " Office Chair");
            for (var wood : woods) {
                added += addExactName(language,
                        "block.pfm.basic_" + colorName + "_" + wood + "_lamp",
                        "Basic " + title(colorName) + " " + variantName(wood, language) + " Lamp");
            }
        }
        return added;
    }

    /**
     * The name PFM's own client shows for an item, for searching.
     *
     * <p>A dedicated server has no translation of its own: the block's name component is still its
     * raw {@code block.pfm.*} key, and the variants are built on a client by a language generator
     * that cannot run here. Searching therefore has to use the same rules the generated language
     * file uses, or a filler cabinet is only findable by its registry path.
     */
    public static @Nullable String searchName(Item item) {
        var id = BuiltInRegistries.ITEM.getKey(item);
        if (!id.getNamespace().equals(NS)) {
            return null;
        }

        return englishName(id.getPath(), language());
    }

    /** PFM's English language file with the generated names merged in, read once. */
    private static synchronized JsonObject language() {
        if (LANGUAGE == null) {
            var language = new JsonObject();
            var container = FabricLoader.getInstance().getModContainer(NS).orElse(null);
            if (container != null) {
                for (var root : container.getRootPaths()) {
                    var file = root.resolve("assets/" + NS + "/lang/en_us.json");
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }

                    try {
                        language = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    } catch (IOException | RuntimeException exception) {
                        PaladinsFurniturePatch.LOGGER.warn("Could not read PFM's English names", exception);
                    }
                    break;
                }
            }

            addLanguageNames(language);
            LANGUAGE = language;
        }

        return LANGUAGE;
    }

    private static int addExactName(JsonObject language, String key, String name) {
        if (language.has(key)) {
            return 0;
        }

        language.addProperty(key, name);
        return 1;
    }

    private static int addLanguageName(JsonObject language, String key, String path) {
        if (language.has(key)) {
            return 0;
        }

        language.addProperty(key, englishName(path, language));
        return 1;
    }

    /** A name in the same shape PFM's generated language file would give the block. */
    private static String englishName(String path, JsonObject language) {
        if (path.startsWith("basic_") && path.endsWith("_lamp")) {
            var start = "basic_".length();
            var end = path.length() - "_lamp".length();
            if (end > start) {
                var name = lampName(path.substring(start, end), language);
                if (name != null) {
                    return name;
                }
            }
        }

        if (path.endsWith("_office_chair") && path.length() > "_office_chair".length()) {
            return title(path.substring(0, path.length() - "_office_chair".length())) + " Office Chair";
        }

        for (var rule : NAMES) {
            if (!path.endsWith(rule.suffix()) || path.length() == rule.suffix().length()) {
                continue;
            }

            var prefix = path.substring(0, path.length() - rule.suffix().length());
            var color = "";
            if (rule.bed()) {
                var found = colorOf(prefix);
                if (found != null) {
                    prefix = prefix.substring(0, prefix.length() - found.length() - 1);
                    color = title(found);
                }
            }

            return normalize(String.format(Locale.ROOT, rule.format(), variantName(prefix, language), color));
        }

        return title(path);
    }

    /** The generated name for a lamp: basic &lt;colour&gt; &lt;wood&gt; lamp. */
    private static @Nullable String lampName(String middle, JsonObject language) {
        for (var color : DyeColor.values()) {
            var name = color.getSerializedName();
            if (middle.startsWith(name + "_")) {
                var wood = middle.substring(name.length() + 1);
                return "Basic " + title(name) + " " + variantName(wood, language) + " Lamp";
            }
        }
        return null;
    }

    /** The colour at the end of a bed's block path, or null when it has none. */
    private static @Nullable String colorOf(String path) {
        String best = null;
        for (var color : DyeColor.values()) {
            var name = color.getSerializedName();
            if (path.endsWith("_" + name) && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        return best;
    }

    /**
     * The wood, stone or combination name PFM uses for a variant.
     *
     * <p>The mod's jar already translates the combinations it ships (for example
     * {@code andesite_oak} is "Andesite Stripped Oak" and {@code pink_wood} is "Pastelle").
     * Those are preferred, and a stripped prefix is added only when the variant itself has no
     * translation.
     */
    private static String variantName(String prefix, JsonObject language) {
        var translated = language.get("block.pfm.variant." + prefix);
        if (translated != null && translated.isJsonPrimitive()) {
            return translated.getAsString();
        }

        if (prefix.startsWith("stripped_")) {
            return "Stripped " + title(prefix.substring("stripped_".length()));
        }
        return title(prefix);
    }

    private static String title(String path) {
        var out = new StringBuilder();
        for (var word : path.split("[_ ]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String normalize(String name) {
        return name.replaceAll("\\s+", " ").trim();
    }

    private record NameRule(String suffix, String format, boolean bed) {
    }

    /** The generated blockstate for a furniture block, or null for a block this table does not know. */
    static byte @Nullable [] blockState(Block block) {
        var id = BuiltInRegistries.BLOCK.getKey(block);
        if (!id.getNamespace().equals(NS)) {
            return null;
        }

        var family = family(id.getPath());
        if (family == null) {
            return null;
        }

        var variants = new JsonObject();
        if (!addVariants(variants, family)) {
            return null;
        }

        var json = new JsonObject();
        json.add("variants", variants);
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    /** The generated item definition for a furniture block item, or null for anything else. */
    static byte @Nullable [] item(Identifier itemId) {
        var item = BuiltInRegistries.ITEM.get(itemId).map(holder -> holder.value()).orElse(null);
        if (!(item instanceof BlockItem blockItem)) {
            return null;
        }

        var blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
        if (!blockId.getNamespace().equals(NS) || family(blockId.getPath()) == null) {
            return null;
        }

        var model = new JsonObject();
        model.addProperty("type", NS + ":furniture_model");
        model.addProperty("block", blockId.toString());

        var json = new JsonObject();
        json.add("model", model);
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean addVariants(JsonObject variants, String family) {
        switch (family) {
            case "simple_bed", "desk_classic", "desk_cabinet_basic", "kitchen_counter", "kitchen_cabinet",
                    "kitchen_drawer", "kitchen_sink", "kitchen_wall_counter", "kitchen_wall_drawer",
                    "kitchen_wall_small_drawer", "simple_bunk_ladder", "log_table", "mirror" ->
                    addFacing(variants, family, 0, 90, 180, 270,
                            switch (family) {
                                case "desk_classic", "desk_cabinet_basic", "kitchen_counter", "kitchen_cabinet",
                                        "kitchen_drawer", "kitchen_sink", "kitchen_wall_counter",
                                        "kitchen_wall_drawer", "kitchen_wall_small_drawer" -> true;
                                default -> false;
                            });
            case "kitchen_counter_oven" -> addFacing(variants, family, 180, 270, 0, 90, true);
            case "fridge", "freezer", "iron_fridge" -> addFacing(variants, family, 180, 270, 0, 90, false);
            case "chair", "chair_classic", "chair_dinner", "chair_modern", "simple_stool", "classic_stool",
                    "modern_stool", "log_stool", "dinner_table", "classic_nightstand" ->
                    addFacing(variants, family, 90, 180, 270, 0, false);
            case "table_basic", "coffee_table_basic" -> addAxis(variants, family, 0, 90);
            case "coffee_table_modern", "modern_dinner_table" -> addAxis(variants, family, 90, 0);
            case "table_classic", "coffee_table_classic", "desk_basic", "basic_lamp", "herringbone_planks" ->
                    addVariant(variants, family, "", 0, false);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void addFacing(JsonObject variants, String family, int north, int east, int south, int west,
                                  boolean uvlock) {
        addVariant(variants, family, "facing=north", north, uvlock);
        addVariant(variants, family, "facing=east", east, uvlock);
        addVariant(variants, family, "facing=south", south, uvlock);
        addVariant(variants, family, "facing=west", west, uvlock);
    }

    private static void addAxis(JsonObject variants, String family, int z, int x) {
        addVariant(variants, family, "axis=z", z, false);
        addVariant(variants, family, "axis=x", x, false);
    }

    private static void addVariant(JsonObject variants, String family, String key, int y, boolean uvlock) {
        var variant = new JsonObject();
        variant.addProperty("fabric:type", NS + ":block/" + family);
        variant.addProperty("model", NS + ":block/" + family);
        if (y != 0) {
            variant.addProperty("y", y);
        }
        if (uvlock) {
            variant.addProperty("uvlock", true);
        }
        variants.add(key, variant);
    }

    /**
     * The family name PFM uses for a block, derived from its registry path.
     *
     * <p>The longer suffixes have to be checked before the shorter ones. The names are the model
     * family, not the block name: a log table is {@code log_table}, and a classic bed is
     * {@code simple_bed}, exactly as in PFM's own generated blockstates.
     */
    private static @Nullable String family(String path) {
        if (path.equals("basic_lamp")) {
            return "basic_lamp";
        }
        if (path.endsWith("_herringbone_planks")) {
            return "herringbone_planks";
        }
        if (path.endsWith("_simple_bed") || path.endsWith("_classic_bed")) {
            return "simple_bed";
        }
        if (path.endsWith("_chair_classic")) {
            return "chair_classic";
        }
        if (path.endsWith("_chair_dinner")) {
            return "chair_dinner";
        }
        if (path.endsWith("_chair_modern")) {
            return "chair_modern";
        }
        if (path.endsWith("_chair")) {
            return "chair";
        }
        if (path.endsWith("_classic_stool")) {
            return "classic_stool";
        }
        if (path.endsWith("_modern_stool")) {
            return "modern_stool";
        }
        if (path.endsWith("_simple_stool")) {
            return "simple_stool";
        }
        if (path.endsWith("_log_stool") || path.endsWith("_stem_stool")) {
            return "log_stool";
        }
        if (path.endsWith("_classic_nightstand")) {
            return "classic_nightstand";
        }
        if (path.endsWith("_coffee_table_basic")) {
            return "coffee_table_basic";
        }
        if (path.endsWith("_coffee_table_classic")) {
            return "coffee_table_classic";
        }
        if (path.endsWith("_coffee_table_modern")) {
            return "coffee_table_modern";
        }
        if (path.endsWith("_table_modern_dinner")) {
            return "modern_dinner_table";
        }
        if (path.endsWith("_table_dinner")) {
            return "dinner_table";
        }
        if (path.endsWith("_table_basic")) {
            return "table_basic";
        }
        if (path.endsWith("_table_classic")) {
            return "table_classic";
        }
        if (path.endsWith("_table_log") || path.endsWith("_raw_table_log") || path.endsWith("_table_stem")
                || path.endsWith("_raw_table_stem") || path.endsWith("_table_natural")) {
            return "log_table";
        }
        if (path.endsWith("_desk_cabinet_basic")) {
            return "desk_cabinet_basic";
        }
        if (path.endsWith("_desk_cabinet_classic")) {
            return "desk_classic";
        }
        if (path.endsWith("_desk_basic")) {
            return "desk_basic";
        }
        if (path.endsWith("_desk_classic")) {
            return "desk_classic";
        }
        if (path.endsWith("_kitchen_counter_oven")) {
            return "kitchen_counter_oven";
        }
        if (path.endsWith("_kitchen_counter")) {
            return "kitchen_counter";
        }
        if (path.endsWith("_kitchen_cabinet")) {
            return "kitchen_cabinet";
        }
        if (path.endsWith("_kitchen_drawer")) {
            return "kitchen_drawer";
        }
        if (path.endsWith("_kitchen_sink")) {
            return "kitchen_sink";
        }
        if (path.endsWith("_kitchen_wall_counter")) {
            return "kitchen_wall_counter";
        }
        if (path.endsWith("_kitchen_wall_drawer")) {
            return "kitchen_wall_drawer";
        }
        if (path.endsWith("_kitchen_wall_small_drawer")) {
            return "kitchen_wall_small_drawer";
        }
        if (path.endsWith("_simple_bunk_ladder")) {
            return "simple_bunk_ladder";
        }
        if (path.equals("iron_fridge")) {
            return "iron_fridge";
        }
        if (path.endsWith("_fridge")) {
            return "fridge";
        }
        if (path.endsWith("_freezer")) {
            return "freezer";
        }
        if (path.endsWith("_mirror")) {
            return "mirror";
        }
        return null;
    }
}
