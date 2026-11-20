package eu.pb4.paladinsfurniturepatch.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns Paladin's Furniture's generated assets into ones a vanilla client can read.
 *
 * <p>Every blockstate and item definition the mod generates names one of its own model types -
 * {@code "fabric:type": "pfm:block/chair"} in a blockstate, {@code "type": "pfm:furniture_model"} in
 * an item. Those types are registered by client code that only exists in the mod, so a client
 * without it cannot read the file at all and draws the missing model instead. The same files also
 * name a generic model ({@code pfm:block/chair}) rather than one per wood or stone variant, and the
 * client picks the textures while loading.
 *
 * <p>This class does that picking on the server and writes out what the client would otherwise have
 * built: a plain blockstate, and child models that inherit the mod's own templates with the
 * variant's textures filled in. The templates are only ever referenced, never edited, so the
 * geometry stays exactly the mod's.
 *
 * <p>Variants are found from the block registry instead of a hand written table, so wood types added
 * by other mods - Traverse, Cinderscapes, Terrestria and so on - are textured like the mod textures
 * them. Stripped furniture uses the stripped log texture, and dyed furniture uses the block's dye
 * colour. Textures from other mods are copied into the Polymer pack, since a client without those
 * mods would otherwise be shown a missing texture.
 */
public final class PFMBlockStates {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String NS = PaladinsFurniturePatch.TARGET_MOD_ID;

    private static final Map<Block, JsonObject> PLAIN = new IdentityHashMap<>();
    private static final Set<Block> UNREADABLE = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Identifier, JsonObject> CHILD = new LinkedHashMap<>();
    private static final Map<Identifier, JsonObject> TEMPLATES = new HashMap<>();
    private static final Map<String, Identifier> BY_NAME = new HashMap<>();
    private static final Map<Block, String> FAMILY = new IdentityHashMap<>();
    private static final Map<Block, Textures> TEXTURES = new IdentityHashMap<>();
    private static final Map<Block, Map<String, Identifier>> PART_IDS = new IdentityHashMap<>();
    private static final Set<Identifier> USED_TEXTURES = new LinkedHashSet<>();
    private static final Map<String, Path> TEXTURE_FILES = new HashMap<>();
    private static final Map<String, Wood> WOODS_BY_KEY = new LinkedHashMap<>();
    private static final Map<String, Identifier> LAMP_MODELS = new HashMap<>();
    private static final Map<String, Identifier> LAMP_SHADES = new HashMap<>();
    private static final Map<Identifier, byte[]> GENERATED_TEXTURES = new LinkedHashMap<>();
    private static final Set<String> MISSING_TEXTURES = new HashSet<>();
    private static boolean indexed;
    private static List<Wood> woods;
    private static List<Material> materials;
    private static Map<String, List<String>> TEXTURES_BY_NAME;

    private PFMBlockStates() {
    }

    /** The vanilla-readable form of one block's blockstate, or null when the mod ships none. */
    public static @Nullable JsonObject plain(Block block) {
        var cached = PLAIN.get(block);
        if (cached != null || UNREADABLE.contains(block)) {
            return cached;
        }

        var loaded = load(block);
        if (loaded == null) {
            UNREADABLE.add(block);
        } else {
            PLAIN.put(block, loaded);
        }
        return loaded;
    }

    /** The mod's model type this block uses, with the {@code block/} prefix removed. */
    public static @Nullable String family(Block block) {
        plain(block);
        return FAMILY.get(block);
    }

    /**
     * The model of one part of a multi-part family, with this block's textures applied.
     *
     * <p>Parts are separate models in the jar, so each one is a child model of its own template.
     * Identifiers are stable per block and part, which lets the model selection refer to them.
     */
    public static Identifier part(Block block, String key, String templatePath) {
        var parts = PART_IDS.computeIfAbsent(block, ignored -> new LinkedHashMap<>());
        var existing = parts.get(key);
        if (existing != null) {
            return existing;
        }

        index();
        var id = Identifier.fromNamespaceAndPath(NS,
                "block/patch/" + BuiltInRegistries.BLOCK.getKey(block).getPath() + "/" + key);
        var template = Identifier.fromNamespaceAndPath(NS, templatePath);

        var json = new JsonObject();
        json.addProperty("parent", template.toString());

        var declared = new LinkedHashMap<String, String>();
        collectTextures(template, declared, 0);
        var textures = textures(block);
        if (textures != null) {
            var overrides = new JsonObject();
            for (var entry : declared.entrySet()) {
                var reserved = textures.replace(entry.getKey(), entry.getValue());
                if (!reserved.equals(entry.getValue())) {
                    overrides.addProperty(entry.getKey(), reserved);
                }
            }
            if (!overrides.isEmpty()) {
                json.add("textures", overrides);
            }
        }

        CHILD.put(id, json);
        parts.put(key, id);
        return id;
    }

    /** The child model of an already prepared part, or null. */
    public static @Nullable Identifier partId(Block block, String key) {
        var parts = PART_IDS.get(block);
        return parts == null ? null : parts.get(key);
    }

    /**
     * Writes every furniture blockstate, the child models behind them, and the item definitions
     * that pointed at the mod's own item model type.
     */
    public static void writePack(ResourcePackBuilder builder) {
        var blocks = new LinkedHashMap<Identifier, Block>();
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (id.getNamespace().equals(NS)) {
                blocks.put(id, block);
            }
        }

        int states = 0;
        for (var entry : blocks.entrySet()) {
            var json = plain(entry.getValue());
            if (json == null) {
                continue;
            }

            // Multi-part families need every part they can draw to exist before the models that
            // select between them are written.
            FurnitureModels.prepare(entry.getValue());

            builder.addStringData("assets/%s/blockstates/%s.json".formatted(NS, entry.getKey().getPath()), GSON.toJson(json));
            states++;
        }

        // A lamp is one block whose wood lives on its block entity, so a model has to exist for
        // every known wood whether or not that wood is loaded while the pack is built.
        prepareLampVariants();

        // The office chair is an entity whose colour is a component, so its models have to exist for
        // every colour whether or not one is placed.
        for (var color : COLORS) {
            officeChairItemModel(color);
        }

        int items = 0;
        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (!id.getNamespace().equals(NS)) {
                continue;
            }

            var json = itemState(item, blocks);
            if (json != null) {
                builder.addStringData("assets/%s/items/%s.json".formatted(NS, id.getPath()), GSON.toJson(json));
                items++;
            }
        }

        // Item models can name a part that blockstates never select, so the children are written
        // after the item pass.
        for (var entry : CHILD.entrySet()) {
            builder.addStringData("assets/%s/models/%s.json".formatted(NS, entry.getKey().getPath()), GSON.toJson(entry.getValue()));
        }

        // A model put on a stack - by the item model component - is looked up as a definition under
        // items/, not as a model file: a stack naming pfm:block/patch/basic_lamp/white_oak/item is
        // drawn from assets/pfm/items/block/patch/basic_lamp/white_oak/item.json. Without one of
        // those per model the client quietly fell back to the item's own definition, which is why
        // every lamp in creative looked like the same white oak lamp however the stack was built.
        int definitions = 0;
        for (var model : CHILD.keySet()) {
            builder.addStringData("assets/%s/items/%s.json".formatted(model.getNamespace(), model.getPath()),
                    GSON.toJson(definition(model)));
            definitions++;
        }

        // The office chair is one item for every colour, and the mod's own item definition tints it
        // with a tint type a vanilla client cannot read. A plain definition shows the white chair;
        // the colour is written onto the stack itself. This goes in a pre-finish task because the
        // mod's own jar is added after the pack is built and would otherwise replace the file.
        if (officeChairItemModel("white") != null) {
            builder.addPreFinishTask(finished -> {
                var officeChair = officeChairItemModel("white");
                if (officeChair != null) {
                    finished.addStringData("assets/%s/items/office_chair.json".formatted(NS),
                            GSON.toJson(definition(officeChair)));
                }
            });
        }
        for (var entry : GENERATED_TEXTURES.entrySet()) {
            builder.addData("assets/" + entry.getKey().getNamespace() + "/textures/" + entry.getKey().getPath() + ".png",
                    entry.getValue());
        }
        addModTextures(builder);

        PaladinsFurniturePatch.LOGGER.info(
                "Wrote {} plain furniture blockstates, {} variant models, {} item models and {} model definitions for clients without the mod.",
                states, CHILD.size(), items, definitions);

        if (!UNREADABLE.isEmpty()) {
            PaladinsFurniturePatch.LOGGER.warn("{} furniture blocks have no blockstate and will render as nothing", UNREADABLE.size());
        }
    }

    private static @Nullable JsonObject itemState(Item item, Map<Identifier, Block> blocks) {
        var id = BuiltInRegistries.ITEM.getKey(item);
        var data = PFMAssets.readItem(id);
        if (data == null) {
            return null;
        }

        try {
            var json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            var model = json.getAsJsonObject("model");
            if (model == null || !model.has("type")) {
                return null;
            }

            // Only the mod's own item model type has to be replaced; definitions it ships as plain
            // vanilla assets are already correct and are left to the mod's assets.
            if (!model.get("type").getAsString().equals(NS + ":furniture_model")) {
                return null;
            }

            var blockId = model.has("block") ? Identifier.tryParse(model.get("block").getAsString()) : null;
            var block = blockId == null ? null : blocks.get(blockId);
            var blockModel = block == null ? null : itemModel(block);
            if (blockModel == null) {
                return null;
            }

            var out = new JsonObject();
            var inner = new JsonObject();
            inner.addProperty("type", "minecraft:model");
            inner.addProperty("model", blockModel.toString());
            out.add("model", inner);
            return out;
        } catch (RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not read the item definition for {}", id, exception);
            return null;
        }
    }

    private static JsonObject definition(Identifier model) {
        var definition = new JsonObject();
        var inner = new JsonObject();
        inner.addProperty("type", "minecraft:model");
        inner.addProperty("model", model.toString());
        definition.add("model", inner);
        return definition;
    }

    /** The model a furniture item should use, taken from the first state of its block. */
    public static @Nullable Identifier itemModel(Block block) {
        var json = plain(block);
        if (json == null) {
            return null;
        }

        // A bed's blockstate only draws its frame; the mattresses are separate parts that the mod
        // bakes on the client. An item shows both halves as one model.
        if ("simple_bed".equals(family(block))) {
            var bed = bedItemModel(block);
            if (bed != null) {
                return bed;
            }
        }

        // A lamp's colour is its shade, but its wood is a component on the item. Merging the shade,
        // a pole and the bulb into one model is what gives the inventory a complete lamp.
        if ("basic_lamp".equals(family(block))) {
            var lamp = lampItemModel("white", "oak");
            if (lamp != null) {
                return lamp;
            }
        }

        // A desk is a top plus one corner leg per direction, each a model of its own that the
        // blockstate places. Naming only the top is why a desk item was a board floating in the air.
        if ("desk_basic".equals(family(block))) {
            var desk = deskItemModel(block);
            if (desk != null) {
                return desk;
            }
        }

        // Tables, desk cabinets and wall cabinets are assembled from several parts, and their item
        // has to show the whole furniture rather than the one part a blockstate variant names.
        var merged = mergedItemModel(block);
        if (merged != null) {
            return merged;
        }

        // A multi-part family's blockstate names a type, not a model, so the part the mod's own
        // item uses is the only right answer there.
        var key = PFMParts.itemKey(block);
        if (key != null) {
            var template = PFMParts.template(block, key);
            if (template != null) {
                return part(block, key, template);
            }
        }

        if (json.has("variants")) {
            var variants = json.getAsJsonObject("variants");
            for (var entry : variants.entrySet()) {
                var model = modelOf(entry.getValue());
                if (model != null) {
                    return model;
                }
            }
        }

        if (json.has("multipart")) {
            for (var part : json.getAsJsonArray("multipart")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                    var model = modelOf(part.getAsJsonObject().get("apply"));
                    if (model != null) {
                        return model;
                    }
                }
            }
        }

        return null;
    }

    private static @Nullable Identifier modelOf(JsonElement element) {
        if (element.isJsonArray()) {
            for (var entry : element.getAsJsonArray()) {
                var model = modelOf(entry);
                if (model != null) {
                    return model;
                }
            }
            return null;
        }

        if (!element.isJsonObject()) {
            return null;
        }

        var model = element.getAsJsonObject().get("model");
        return model == null || !model.isJsonPrimitive() ? null : Identifier.tryParse(model.getAsString());
    }

    private static @Nullable JsonObject load(Block block) {
        var id = BuiltInRegistries.BLOCK.getKey(block);
        var data = PFMAssets.readBlockState(id);
        if (data == null) {
            return null;
        }

        try {
            var raw = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            var family = findFamily(raw);
            if (family != null) {
                FAMILY.put(block, family);
            }

            var out = new JsonObject();
            var children = new HashMap<Identifier, Identifier>();

            if (raw.has("variants")) {
                var variants = new JsonObject();
                for (var entry : raw.getAsJsonObject("variants").entrySet()) {
                    variants.add(entry.getKey(), rewrite(block, entry.getValue(), children));
                }
                out.add("variants", variants);
            } else if (raw.has("multipart")) {
                var parts = new JsonArray();
                for (var part : raw.getAsJsonArray("multipart")) {
                    if (!part.isJsonObject()) {
                        parts.add(part);
                        continue;
                    }

                    var object = part.getAsJsonObject();
                    var copy = object.deepCopy();
                    if (object.has("apply")) {
                        copy.add("apply", rewrite(block, object.get("apply"), children));
                    }
                    parts.add(copy);
                }
                out.add("multipart", parts);
            } else {
                return raw;
            }

            // A sink's water is part of its state, not of the model the mod's model type produces:
            // the generated blockstate only names the basin, so a filled sink would look empty.
            out = withSinkWater(block, out);

            return out;
        } catch (RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not rewrite the blockstate for {}", id, exception);
            return null;
        }
    }

    /**
     * Adds the water a sink shows at each fill level.
     *
     * <p>A sink's fill level is a blockstate property, but the generated blockstate names only the
     * basin model - the mod's own model type adds the water on the client. Here the water is a second
     * multipart part per facing and level, so a sink filled from a bucket shows water without the mod.
     */
    private static JsonObject withSinkWater(Block block, JsonObject json) {
        var path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (!path.contains("sink") || !json.has("variants")
                || block.getStateDefinition().getProperties().stream().noneMatch(p -> p.getName().equals("level"))) {
            return json;
        }

        var parts = new JsonArray();
        for (var entry : json.getAsJsonObject("variants").entrySet()) {
            if (!entry.getKey().contains("=")) {
                return json;
            }

            var when = new JsonObject();
            var parts2 = entry.getKey().split(",");
            for (var part : parts2) {
                var split = part.split("=", 2);
                if (split.length == 2) {
                    when.addProperty(split[0], split[1]);
                }
            }

            var base = new JsonObject();
            base.add("when", when);
            base.add("apply", entry.getValue());
            parts.add(base);

            if (!entry.getValue().isJsonObject()) {
                continue;
            }

            var rotation = entry.getValue().getAsJsonObject().get("y");
            for (int level = 1; level <= 3; level++) {
                var water = new JsonObject();
                var condition = new JsonObject();
                var conditions = new JsonArray();
                conditions.add(when.deepCopy());
                var levelCondition = new JsonObject();
                levelCondition.addProperty("level", String.valueOf(level));
                conditions.add(levelCondition);
                condition.add("AND", conditions);

                var apply = new JsonObject();
                apply.addProperty("model", "pfm:block/basic_sink/"
                        + (level == 3 ? "basic_sink_full" : "basic_sink_level" + level));
                if (rotation != null && rotation.isJsonPrimitive()) {
                    apply.add("y", rotation.deepCopy());
                }

                water.add("when", condition);
                water.add("apply", apply);
                parts.add(water);
            }
        }

        var out = new JsonObject();
        out.add("multipart", parts);
        return out;
    }

    /** The mod's model type named by a blockstate, as a plain family name. */
    private static @Nullable String findFamily(JsonObject raw) {
        if (raw.has("variants")) {
            for (var entry : raw.getAsJsonObject("variants").entrySet()) {
                var family = familyOf(entry.getValue());
                if (family != null) {
                    return family;
                }
            }
        } else if (raw.has("multipart")) {
            for (var part : raw.getAsJsonArray("multipart")) {
                if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                    var family = familyOf(part.getAsJsonObject().get("apply"));
                    if (family != null) {
                        return family;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String familyOf(JsonElement element) {
        if (element.isJsonArray()) {
            for (var entry : element.getAsJsonArray()) {
                var family = familyOf(entry);
                if (family != null) {
                    return family;
                }
            }
            return null;
        }

        if (!element.isJsonObject()) {
            return null;
        }

        var type = element.getAsJsonObject().get("fabric:type");
        if (type == null || !type.isJsonPrimitive()) {
            return null;
        }

        var id = Identifier.tryParse(type.getAsString());
        if (id == null) {
            return null;
        }

        var path = id.getPath();
        return path.startsWith("block/") ? path.substring("block/".length()) : path;
    }

    private static JsonElement rewrite(Block block, JsonElement element, Map<Identifier, Identifier> children) {
        if (element.isJsonArray()) {
            var array = new JsonArray();
            for (var entry : element.getAsJsonArray()) {
                array.add(rewrite(block, entry, children));
            }
            return array;
        }

        if (!element.isJsonObject()) {
            return element;
        }

        var variant = element.getAsJsonObject();
        if (!variant.has("fabric:type") || !variant.has("model")) {
            return element;
        }

        var copy = variant.deepCopy();
        copy.remove("fabric:type");

        // A basic desk's blockstate names only the desk model type; the mod's own client code draws
        // the top and all four legs from it. A placed desk has to draw the same five pieces, which is
        // the merged model it already shows as an item.
        if ("desk_basic".equals(FAMILY.get(block))) {
            var desk = deskItemModel(block);
            if (desk != null) {
                copy.addProperty("model", desk.toString());
                return copy;
            }
        }

        var source = Identifier.tryParse(variant.get("model").getAsString());
        var template = source == null ? null : resolve(block, source);
        if (template == null) {
            if (source != null && source.getPath().contains("herringbone")) {
                copy.addProperty("model", herringboneChild(block, children).toString());
            }
            return copy;
        }

        var model = children.get(template);
        if (model == null) {
            model = childFor(block, template, children.size());
            children.put(template, model);
        }

        copy.addProperty("model", model.toString());
        return copy;
    }

    private static Identifier childFor(Block block, Identifier template, int index) {
        var blockPath = BuiltInRegistries.BLOCK.getKey(block).getPath();
        var id = Identifier.fromNamespaceAndPath(NS, "block/patch/" + blockPath + "/" + index);

        var textures = textures(block);
        var overrides = new LinkedHashMap<String, String>();
        if (textures != null) {
            var effective = new LinkedHashMap<String, String>();
            collectTextures(template, effective, 0);
            for (var entry : effective.entrySet()) {
                var reserved = textures.replace(entry.getKey(), entry.getValue());
                if (!reserved.equals(entry.getValue())) {
                    overrides.put(entry.getKey(), reserved);
                }
            }
        }

        var json = new JsonObject();
        json.addProperty("parent", template.toString());
        if (!overrides.isEmpty()) {
            var object = new JsonObject();
            overrides.forEach(object::addProperty);
            json.add("textures", object);
        }

        CHILD.put(id, json);
        return id;
    }

    private static void collectTextures(Identifier model, Map<String, String> out, int depth) {
        if (depth > 8) {
            return;
        }

        var json = template(model);
        if (json == null) {
            return;
        }

        if (json.has("textures") && json.get("textures").isJsonObject()) {
            for (var entry : json.getAsJsonObject("textures").entrySet()) {
                if (entry.getValue().isJsonPrimitive() && !out.containsKey(entry.getKey())) {
                    out.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }

        if (json.has("parent")) {
            var parent = Identifier.tryParse(json.get("parent").getAsString());
            if (parent != null) {
                collectTextures(parent, out, depth + 1);
            }
        }
    }

    /** A model from the mod's jar, or the model a generated family name stands for. */
    private static @Nullable Identifier resolve(Block block, Identifier family) {
        index();

        // A mirror's blockstate names one model type for both colours; only the frame texture
        // differs. The gray block therefore has to be pointed at the gray model, which overrides
        // that texture, or it is drawn with the white frame like an unpainted mirror.
        if (BuiltInRegistries.BLOCK.getKey(block).getPath().equals("gray_mirror")
                && family.getPath().contains("mirror")) {
            var gray = Identifier.fromNamespaceAndPath(NS, "block/gray_mirror/gray_mirror");
            if (TEMPLATES.containsKey(gray)) {
                return gray;
            }
        }

        if (TEMPLATES.containsKey(family)) {
            return family;
        }

        var path = family.getPath();
        var name = path.substring(path.lastIndexOf('/') + 1);
        var byName = BY_NAME.get(name);
        return byName != null ? byName : alias(block, name);
    }

    /**
     * Families whose template is shipped under a different name than the family itself.
     *
     * <p>The generated blockstates name a family - {@code pfm:block/desk_classic} - while the model
     * that family draws is in the jar as {@code pfm:block/classic_desk/...}. A few also change shape
     * with the block rather than the family, which is why the block is needed: a desk cabinet and a
     * desk share one family name.
     */
    private static @Nullable Identifier alias(Block block, String family) {
        var blockPath = BuiltInRegistries.BLOCK.getKey(block).getPath();
        var path = switch (family) {
            case "desk_classic" -> blockPath.contains("cabinet")
                    ? "block/classic_desk/classic_desk_cabinet_all"
                    : "block/classic_desk/classic_desk_all";
            case "desk_basic" -> "block/basic_desk/basic_desk_base";
            case "desk_cabinet_basic" -> "block/basic_desk_cabinet/basic_desk_cabinet_closed";
            case "modern_dinner_table" -> "block/table_modern_dinner/table_modern_dinner";
            case "kitchen_wall_counter" -> "block/kitchen_counter/kitchen_counter_middle";
            case "kitchen_wall_drawer" -> "block/kitchen_drawer/kitchen_drawer_middle";
            case "kitchen_wall_small_drawer" -> "block/kitchen_wall_drawer_small/kitchen_wall_drawer_small";
            case "simple_bunk_ladder" -> "block/simple_bunk_ladder/simple_ladder";
            case "basic_lamp" -> "block/basic_lamp/basic_lamp_single";
            default -> null;
        };

        if (path != null) {
            return Identifier.fromNamespaceAndPath(NS, path);
        }

        if (family.equals("white_fridge") || family.equals("gray_fridge")) {
            return Identifier.fromNamespaceAndPath(NS, "block/" + family + "/fridge");
        }
        if (family.equals("white_freezer") || family.equals("gray_freezer")) {
            return Identifier.fromNamespaceAndPath(NS, "block/" + family.replace("freezer", "fridge") + "/freezer");
        }
        if (family.equals("white_mirror")) {
            return Identifier.fromNamespaceAndPath(NS, "block/mirror/mirror");
        }
        if (family.equals("gray_mirror")) {
            return Identifier.fromNamespaceAndPath(NS, "block/gray_mirror/gray_mirror");
        }

        return null;
    }

    /** Herringbone planks have no model in the jar; the mod builds them from one texture. */
    private static Identifier herringboneChild(Block block, Map<Identifier, Identifier> children) {
        var key = Identifier.fromNamespaceAndPath(NS, "herringbone");
        var existing = children.get(key);
        if (existing != null) {
            return existing;
        }

        var id = Identifier.fromNamespaceAndPath(NS,
                "block/patch/" + BuiltInRegistries.BLOCK.getKey(block).getPath() + "/herringbone");
        var json = new JsonObject();
        json.addProperty("parent", "minecraft:block/cube_all");
        var textures = new JsonObject();
        textures.addProperty("all", "pfm:block/template_herringbone_planks");
        json.add("textures", textures);

        CHILD.put(id, json);
        children.put(key, id);
        return id;
    }

    private static @Nullable JsonObject template(Identifier model) {
        index();
        return TEMPLATES.get(model);
    }

    private static void index() {
        if (indexed) {
            return;
        }
        indexed = true;

        var container = FabricLoader.getInstance().getModContainer(NS).orElse(null);
        if (container == null) {
            return;
        }

        var found = new TreeMap<String, Identifier>();
        for (var root : container.getRootPaths()) {
            var models = root.resolve("assets").resolve(NS).resolve("models");
            if (!Files.isDirectory(models)) {
                continue;
            }

            try (var files = Files.walk(models)) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    var relative = models.relativize(file).toString().replace('\\', '/');
                    if (!relative.endsWith(".json")) {
                        continue;
                    }

                    var id = Identifier.fromNamespaceAndPath(NS, "models/" + relative.substring(0, relative.length() - ".json".length()));
                    try {
                        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                        TEMPLATES.put(id, json);
                    } catch (IOException | RuntimeException exception) {
                        continue;
                    }

                    var path = id.getPath();
                    var name = path.substring(path.lastIndexOf('/') + 1);
                    if (path.startsWith("models/block/") && !found.containsKey(name)) {
                        found.put(name, id);
                    }
                }
            } catch (IOException exception) {
                PaladinsFurniturePatch.LOGGER.warn("Could not list Paladin's Furniture's models", exception);
            }
        }

        // "models/" is part of the scan path only; valid model identifiers drop it.
        for (var entry : TEMPLATES.entrySet().stream().toList()) {
            var id = entry.getKey();
            if (id.getPath().startsWith("models/")) {
                TEMPLATES.put(Identifier.fromNamespaceAndPath(NS, id.getPath().substring("models/".length())), entry.getValue());
            }
        }
        TEMPLATES.keySet().removeIf(id -> id.getPath().startsWith("models/"));

        for (var entry : found.entrySet()) {
            var id = entry.getValue();
            BY_NAME.put(entry.getKey(), id.getPath().startsWith("models/")
                    ? Identifier.fromNamespaceAndPath(NS, id.getPath().substring("models/".length()))
                    : id);
        }
    }

    /**
     * The textures a wood, stone or dyed variant uses.
     *
     * <p>The mod does this on the client by reading sprites and recolouring them while rendering;
     * a static pack can only name textures, so the same intent is expressed as explicit texture
     * identifiers taken from the variant's own blocks.
     */
    private static @Nullable Textures textures(Block block) {
        var cached = TEXTURES.get(block);
        if (cached != null) {
            return cached;
        }

        var path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        var stripped = path.contains("stripped");
        var wood = wood(path);
        var stone = stone(path);
        var color = color(path);

        // PFM keeps the authoritative variant table, including combinations such as
        // andesite_oak where the base is one block and the trim is another. Reading it is more
        // reliable than guessing from the id, which cannot tell "crimson blackstone" from
        // "crimson" alone.
        var material = material(path);
        if (material != null) {
            var primary = stripped && material.stripped != null ? material.stripped : material.base;
            // PFM gives stripped cabinets, drawers, counters and tables planks for their trim, not
            // the stripped log: the body is the stripped texture and the legs are the plain wood.
            var secondary = material.secondary == null ? primary
                    : (stripped && usesPlankTrim(path) ? material.base : material.secondary);
            var bed = color == null ? null : bedTexture(color);
            var textures = new Textures(primary, secondary, topTexture(secondary), null,
                    bed == null ? null : bed.toString());
            TEXTURES.put(block, textures);
            return textures;
        }

        Textures textures = null;
        if (wood != null) {
            var log = stripped && wood.strippedLog != null ? wood.strippedLog : wood.log;
            var planks = stripped && wood.strippedLog != null ? wood.strippedLog : wood.planks;
            var primary = blockTexture(planks, wood.token, "planks");
            var plankTrim = stripped && usesPlankTrim(path);
            var trim = plankTrim ? wood.planks : log;
            var secondary = trim == null ? primary
                    : blockTexture(trim, wood.token, plankTrim ? "planks" : stripped ? "stripped_log" : "log");
            var top = log == null ? secondary : topTexture(secondary);
            textures = new Textures(primary, secondary, top, null, null);
        } else if (stone != null) {
            textures = switch (stone) {
                case "stone" -> new Textures("minecraft:block/stone", "minecraft:block/cobblestone",
                        "minecraft:block/cobblestone", null, null);
                // There is no minecraft:block/quartz_block texture; the block's faces are side and top.
                case "quartz" -> new Textures("minecraft:block/quartz_block_side", "minecraft:block/quartz_block_side",
                        "minecraft:block/quartz_block_top", null, null);
                case "calcite" -> new Textures("minecraft:block/calcite", "minecraft:block/calcite",
                        "minecraft:block/calcite", null, null);
                default -> new Textures("minecraft:block/polished_" + stone, "minecraft:block/" + stone,
                        "minecraft:block/" + stone, null, null);
            };
        }

        if (color != null) {
            // The bed entity sheet is not in the block atlas, so the pack carries vanilla's sheet
            // under a block texture path where the mattress models can sample it.
            var bed = bedTexture(color);
            if (bed != null) {
                var id = bed.toString();
                textures = textures == null
                        ? new Textures(null, null, null, null, id)
                        : new Textures(textures.primary, textures.secondary, textures.top, null, id);
            }
        }

        if (textures != null) {
            TEXTURES.put(block, textures);
        }
        return textures;
    }

    private record Textures(@Nullable String primary, @Nullable String secondary, @Nullable String top,
                            @Nullable String wool, @Nullable String bed) {
        String replace(String key, String texture) {
            var path = texture;
            var colon = path.indexOf(':');
            if (colon >= 0) {
                path = path.substring(colon + 1);
            }
            if (path.startsWith("block/")) {
                path = path.substring("block/".length());
            }

            if (bed != null && path.contains("entity/bed/")) {
                USED_TEXTURES.add(Identifier.parse(bed));
                return bed;
            }

            if (wool != null && path.endsWith("_wool")) {
                USED_TEXTURES.add(Identifier.parse(wool));
                return wool;
            }

            var topLog = path.endsWith("_log_top") || path.endsWith("_wood_top")
                    || path.endsWith("_stem_top") || path.endsWith("_block_top") || path.endsWith("_hyphae_top");
            // Template slots name the surface they cover, which is more precise than their
            // current texture: a combined variant's base and trim are both named "base" and
            // "legs" even when the template says oak planks and oak log.
            if (primary != null && "base".equals(key)) {
                USED_TEXTURES.add(Identifier.parse(primary));
                return primary;
            }
            if (secondary != null && ("legs".equals(key) || "support".equals(key))) {
                USED_TEXTURES.add(Identifier.parse(secondary));
                return secondary;
            }

            if (top != null && topLog) {
                USED_TEXTURES.add(Identifier.parse(top));
                return top;
            }

            var log = path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem")
                    || path.endsWith("_hyphae") || path.endsWith("_block");
            if (secondary != null && log && !topLog) {
                USED_TEXTURES.add(Identifier.parse(secondary));
                return secondary;
            }

            if (primary != null && path.endsWith("_planks")) {
                USED_TEXTURES.add(Identifier.parse(primary));
                return primary;
            }

            return texture;
        }
    }

    private record Material(String name, String base, @Nullable String secondary, @Nullable String stripped) {
    }

    /** PFM's variants, including the combinations whose two halves are different blocks. */
    private static @Nullable Material material(String path) {
        Material best = null;
        for (var material : materials()) {
            if (!containsToken(path, material.name)) {
                continue;
            }
            if (best == null || material.name.length() > best.name.length()) {
                best = material;
            }
        }
        return best;
    }

    private static List<Material> materials() {
        if (materials != null) {
            return materials;
        }

        var found = new ArrayList<Material>();
        var seen = new HashSet<String>();

        readRegistry("com.unlikepaladin.pfm.data.materials.WoodVariantRegistry", "getVariants", found, seen);
        readRegistry("com.unlikepaladin.pfm.data.materials.StoneVariantRegistry", "getVariants", found, seen);
        readRegistry("com.unlikepaladin.pfm.data.materials.ExtraCounterVariant", "values", found, seen);

        // Some PFM builds expose basalt as a datapack material rather than a registry entry.
        if (seen.add("basalt")) {
            found.add(new Material("basalt", "minecraft:block/polished_basalt_side",
                    "minecraft:block/basalt_side", null));
        }
        if (seen.add("netherite")) {
            found.add(new Material("netherite", "minecraft:block/netherite_block",
                    "minecraft:block/ancient_debris_side", null));
        }

        found.sort((a, b) -> {
            int byLength = Integer.compare(b.name.length(), a.name.length());
            return byLength != 0 ? byLength : a.name.compareTo(b.name);
        });
        materials = List.copyOf(found);
        return materials;
    }

    private static void readRegistry(String className, String methodName, List<Material> out, Set<String> seen) {
        try {
            var type = Class.forName(className);
            var method = type.getMethod(methodName);
            var values = method.invoke(null);
            if (values == null) {
                return;
            }

            if (values instanceof Iterable<?> iterable) {
                for (var value : iterable) {
                    addVariant(out, seen, value);
                }
            } else if (values.getClass().isArray()) {
                for (var value : (Object[]) values) {
                    addVariant(out, seen, value);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.debug("Could not read PFM material registry {}", className, exception);
        }
    }

    private static void addVariant(List<Material> out, Set<String> seen, Object variant) {
        if (variant == null) {
            return;
        }

        var name = stringMethod(variant, "getSerializedName");
        var base = blockMethod(variant, "getBaseBlock");
        var secondary = blockMethod(variant, "getSecondaryBlock");
        if (name == null || name.isBlank() || base == null || !seen.add(name)) {
            return;
        }

        var baseTexture = blockTexture(base);
        if (baseTexture == null) {
            return;
        }
        var secondTexture = secondary == null ? null : blockTexture(secondary);

        Block stripped = null;
        try {
            var child = variant.getClass().getMethod("getChild", String.class).invoke(variant, "stripped_log");
            if (child instanceof Block block) {
                stripped = block;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Not every material has children.
        }

        out.add(new Material(name, baseTexture, secondTexture, stripped == null ? null : blockTexture(stripped)));
    }

    private static @Nullable String stringMethod(Object value, String methodName) {
        try {
            var result = value.getClass().getMethod(methodName).invoke(value);
            return result instanceof String string ? string : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static @Nullable Block blockMethod(Object value, String methodName) {
        try {
            var result = value.getClass().getMethod(methodName).invoke(value);
            return result instanceof Block block ? block : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static @Nullable String blockTexture(@Nullable Block block) {
        if (block == null) {
            return null;
        }

        var id = BuiltInRegistries.BLOCK.getKey(block);
        return switch (id.getPath()) {
            case "air" -> null;
            // The vanilla block is a cube whose side and top are different sprites.
            case "quartz_block" -> "minecraft:block/quartz_block_side";
            case "ancient_debris" -> "minecraft:block/ancient_debris_side";
            case "basalt" -> "minecraft:block/basalt_side";
            case "polished_basalt" -> "minecraft:block/polished_basalt_side";
            default -> {
                if (id.getNamespace().equals("minecraft")) {
                    yield "minecraft:block/" + id.getPath();
                }

                var direct = id.getNamespace() + ":block/" + id.getPath();
                if (textureExists(direct)) {
                    yield direct;
                }

                // Not every mod puts a block's sprite where its id suggests. tsa-stone registers
                // tsa:limestone but ships the image as minecraft:block/custom/stone/limestone, so
                // the sprite is looked up by name rather than by the path the id would imply.
                yield textureNamed(id.getNamespace(), id.getPath(), direct);
            }
        };
    }

    /**
     * The sprite a mod actually ships for a block whose id does not name a texture path.
     *
     * <p>Names are collected once from every loaded mod's {@code textures/block} folder. A sprite in
     * the block's own namespace wins, then one in {@code minecraft} - which is where mods that add
     * vanilla styled blocks keep theirs - and only then another namespace's, shortest path first.
     * The id the block's name implies is returned when nothing matches, so a missing sprite is still
     * reported instead of silently drawing another mod's block.
     */
    private static String textureNamed(String namespace, String path, String fallback) {
        var candidates = texturesByName().get(path);
        if (candidates == null) {
            return fallback;
        }

        String best = null;
        int bestRank = Integer.MAX_VALUE;
        int bestDepth = Integer.MAX_VALUE;
        for (var candidate : candidates) {
            var candidateNamespace = candidate.substring(0, candidate.indexOf(':'));
            int rank = candidateNamespace.equals(namespace) ? 0
                    : candidateNamespace.equals("minecraft") ? 1 : 2;
            int depth = candidate.length() - candidate.replace("/", "").length();
            if (rank < bestRank
                    || (rank == bestRank && depth < bestDepth)
                    || (rank == bestRank && depth == bestDepth && (best == null || candidate.compareTo(best) < 0))) {
                best = candidate;
                bestRank = rank;
                bestDepth = depth;
            }
        }

        return best == null ? fallback : best;
    }

    /** Every block sprite a loaded mod ships, by file name. */
    private static Map<String, List<String>> texturesByName() {
        if (TEXTURES_BY_NAME != null) {
            return TEXTURES_BY_NAME;
        }

        var found = new HashMap<String, List<String>>();
        for (var container : FabricLoader.getInstance().getAllMods()) {
            for (var root : container.getRootPaths()) {
                var assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) {
                    continue;
                }

                try (var namespaces = Files.list(assets)) {
                    for (var namespace : namespaces.filter(Files::isDirectory).toList()) {
                        var textures = namespace.resolve("textures").resolve("block");
                        if (!Files.isDirectory(textures)) {
                            continue;
                        }

                        var name = namespace.getFileName().toString();
                        try (var files = Files.walk(textures)) {
                            for (var file : files.filter(Files::isRegularFile).toList()) {
                                var fileName = file.getFileName().toString();
                                if (!fileName.endsWith(".png")) {
                                    continue;
                                }

                                var relative = textures.relativize(file).toString().replace('\\', '/');
                                relative = relative.substring(0, relative.length() - ".png".length());
                                found.computeIfAbsent(relative.substring(relative.lastIndexOf('/') + 1),
                                                ignored -> new ArrayList<>())
                                        .add(name + ":block/" + relative);
                            }
                        }
                    }
                } catch (IOException exception) {
                    PaladinsFurniturePatch.LOGGER.debug("Could not list the textures of {}",
                            container.getMetadata().getId(), exception);
                }
            }
        }

        TEXTURES_BY_NAME = found;
        return found;
    }

    private record Wood(String token, Identifier planks, @Nullable Identifier log, @Nullable Identifier strippedLog) {
    }

    private static @Nullable Wood wood(String path) {
        Wood fallback = null;
        for (var wood : woods()) {
            if (!containsToken(path, wood.token)) {
                continue;
            }

            // The mod names furniture for a modded wood as <wood>_<namespace>_<furniture>. Two mods
            // can share a wood name - cypress exists in more than one - so the namespace in the
            // block's own name decides which planks it was made from.
            var namespace = wood.planks.getNamespace();
            if (!namespace.equals("minecraft")
                    && (path.contains("_" + namespace + "_") || path.endsWith("_" + namespace))) {
                return wood;
            }

            if (fallback == null) {
                fallback = wood;
            }
        }
        return fallback;
    }

    private static boolean containsToken(String path, String token) {
        return path.equals(token) || path.startsWith(token + "_") || path.endsWith("_" + token)
                || path.contains("_" + token + "_");
    }

    /** Every wood type in the registry, longest name first so dark_oak wins over oak. */
    private static List<Wood> woods() {
        if (woods != null) {
            return woods;
        }

        var found = new ArrayList<Wood>();
        var seen = new HashSet<String>();
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            var path = id.getPath();
            if (!path.endsWith("_planks")) {
                continue;
            }

            var token = path.substring(0, path.length() - "_planks".length());
            if (token.isEmpty() || !seen.add(token)) {
                continue;
            }

            var log = block(id.getNamespace(), token + "_log", token + "_stem", token + "_wood", token + "_block");
            var stripped = block(id.getNamespace(), "stripped_" + token + "_log", "stripped_" + token + "_stem",
                    "stripped_" + token + "_wood", "stripped_" + token + "_block", token + "_stripped_log");
            found.add(new Wood(token, id, log, stripped));
        }

        found.sort((a, b) -> {
            int byLength = Integer.compare(b.token.length(), a.token.length());
            if (byLength != 0) {
                return byLength;
            }
            boolean vanillaA = a.planks.getNamespace().equals("minecraft");
            boolean vanillaB = b.planks.getNamespace().equals("minecraft");
            if (vanillaA != vanillaB) {
                return vanillaA ? -1 : 1;
            }
            return a.token.compareTo(b.token);
        });

        woods = List.copyOf(found);
        WOODS_BY_KEY.clear();
        for (var wood : woods) {
            WOODS_BY_KEY.put(serializedName(wood), wood);
        }
        return woods;
    }

    private static @Nullable Identifier block(String namespace, String... paths) {
        for (var path : paths) {
            var id = Identifier.fromNamespaceAndPath(namespace, path);
            if (BuiltInRegistries.BLOCK.containsKey(id)) {
                return id;
            }
        }
        return null;
    }

    private static @Nullable String stone(String path) {
        for (var stone : STONES) {
            if (containsToken(path, stone)) {
                return stone;
            }
        }
        return null;
    }

    private static @Nullable String color(String path) {
        for (var color : COLORS) {
            if (containsToken(path, color)) {
                return color;
            }
        }
        return null;
    }

    /**
     * A block's texture, or the best matching texture a mod actually ships.
     *
     * <p>Vanilla names are trusted; other mods are free to put their planks somewhere else, so for
     * those the candidates are checked against the mod's own resources and the first one that
     * exists wins. Nothing is guessed silently: a missing texture is reported instead of silently
     * drawing oak.
     */
    private static @Nullable String blockTexture(@Nullable Identifier block, String token, String kind) {
        if (block != null) {
            var namespace = block.getNamespace();
            var path = block.getPath();
            if (namespace.equals("minecraft")) {
                return "minecraft:block/" + path;
            }

            var direct = namespace + ":block/" + path;
            if (textureExists(direct)) {
                return direct;
            }
        }

        if (block == null) {
            // There is no block to take a texture from. Guessing a vanilla name would produce an id
            // that looks valid but is not, which is exactly how a lamp lost its pole texture.
            return null;
        }

        var namespace = block.getNamespace();
        var candidates = kind.equals("planks")
                ? List.of("block/" + token + "_planks", "block/" + token + "/planks", "block/planks_" + token,
                "block/wood/" + token + "_planks", "block/wood/planks_" + token)
                : List.of("block/" + token + "_log", "block/" + token + "/log", "block/wood/" + token + "_log",
                "block/wood/log_" + token);

        for (var candidate : candidates) {
            var id = namespace + ":" + candidate;
            if (textureExists(id)) {
                return id;
            }
        }

        return "minecraft:block/oak_planks";
    }

    private static @Nullable String topTexture(@Nullable String base) {
        if (base == null) {
            return null;
        }

        var colon = base.indexOf(':');
        var namespace = colon < 0 ? "minecraft" : base.substring(0, colon);
        var path = colon < 0 ? base : base.substring(colon + 1);
        var top = namespace + ":" + path + "_top";
        if (namespace.equals("minecraft") || textureExists(top)) {
            return top;
        }
        return base;
    }

    private static boolean textureExists(String texture) {
        var id = Identifier.tryParse(texture);
        if (id == null) {
            return false;
        }
        if (id.getNamespace().equals("minecraft")) {
            return true;
        }
        return textureFile(id) != null;
    }

    /** Finds a texture in any loaded mod, which is how modded woods are checked and copied. */
    private static @Nullable Path textureFile(Identifier texture) {
        var key = texture.toString();
        if (TEXTURE_FILES.containsKey(key)) {
            return TEXTURE_FILES.get(key);
        }
        if (MISSING_TEXTURES.contains(key)) {
            return null;
        }

        var relative = "assets/" + texture.getNamespace() + "/textures/" + texture.getPath() + ".png";
        for (var container : FabricLoader.getInstance().getAllMods()) {
            for (var root : container.getRootPaths()) {
                var file = root.resolve(relative);
                if (Files.isRegularFile(file)) {
                    TEXTURE_FILES.put(key, file);
                    return file;
                }
            }
        }

        MISSING_TEXTURES.add(key);
        return null;
    }

    /** The name PFM uses for a wood in block ids: {@code oak}, {@code fir_traverse}, ... */
    static String serializedName(Wood wood) {
        return wood.planks.getNamespace().equals("minecraft")
                ? wood.token
                : wood.token + "_" + wood.planks.getNamespace();
    }

    /** Builds every lamp pole and lamp item model for every wood the server knows about. */
    static void prepareLampVariants() {
        if (lampBlock() == null) {
            return;
        }

        for (var color : COLORS) {
            lampShadeModel(color);
            for (var wood : woods()) {
                var key = serializedName(wood);
                for (var spec : PFMParts.lampSpecs()) {
                    if (isLampPole(spec.key())) {
                        lampPart(key, spec.key());
                    }
                }
                lampItemModel(color, key);
            }
        }
    }

    static boolean isLampPole(String key) {
        return key.length() == 2 && key.charAt(0) == 'l'
                && key.charAt(1) >= '0' && key.charAt(1) <= '3';
    }

    private static Block lampBlock() {
        return BuiltInRegistries.BLOCK.get(Identifier.fromNamespaceAndPath(NS, "basic_lamp"))
                .map(holder -> holder.value())
                .orElse(null);
    }

    /**
     * The shaded part of one lamp colour. PFM tints the white shade with the block entity's
     * colour at render time; a static model has to point at a pre-tinted copy instead.
     */
    public static @Nullable Identifier lampShadeModel(String color) {
        var existing = LAMP_SHADES.get(color);
        if (existing != null) {
            return existing;
        }

        var texture = lampShadeTexture(color);
        if (texture == null) {
            return null;
        }

        var id = Identifier.fromNamespaceAndPath(NS, "block/patch/basic_lamp/" + color + "/shade");
        var json = new JsonObject();
        json.addProperty("parent", NS + ":block/basic_lamp/basic_lamp_shade");
        var textures = new JsonObject();
        textures.addProperty("shade", texture.toString());
        textures.addProperty("particle", texture.toString());
        json.add("textures", textures);
        CHILD.put(id, json);
        LAMP_SHADES.put(color, id);
        return id;
    }

    /**
     * One bed sheet, copied from vanilla's {@code entity/bed/<colour>} texture.
     *
     * <p>PFM's mattresses name vanilla's entity-atlas texture, which cannot be sampled by a block
     * or item model. The 16 sheets are shipped under the patch's block namespace with the same
     * pixels; this only names the matching copy.
     */
    private static Identifier bedTexture(String color) {
        return Identifier.fromNamespaceAndPath(PaladinsFurniturePatch.MOD_ID, "block/patch/bed/" + color);
    }

    private static @Nullable Identifier lampShadeTexture(String color) {
        var id = Identifier.fromNamespaceAndPath(NS, "block/patch/lamp_shade/" + color);
        if (GENERATED_TEXTURES.containsKey(id)) {
            return id;
        }

        var source = textureFile(Identifier.fromNamespaceAndPath(NS, "block/lamp_shade"));
        if (source == null) {
            return null;
        }

        try {
            var tint = dyeColor(color);
            GENERATED_TEXTURES.put(id, tintPng(Files.readAllBytes(source), tint));
            return id;
        } catch (IOException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not tint the lamp shade {}", color, exception);
            return null;
        }
    }

    private static int dyeColor(String name) {
        for (var color : DyeColor.values()) {
            if (color.getSerializedName().equals(name)) {
                return color.getMapColor().col;
            }
        }
        return 0xFFFFFF;
    }

    private static byte[] tintPng(byte[] source, int rgb) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) {
            return source;
        }

        float red = ((rgb >> 16) & 0xFF) / 255.0f;
        float green = ((rgb >> 8) & 0xFF) / 255.0f;
        float blue = (rgb & 0xFF) / 255.0f;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * red));
                int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * green));
                int b = Math.min(255, Math.round((argb & 0xFF) * blue));
                image.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }

        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    /**
     * The lamp's pole model for one wood, or null when PFM does not know that wood.
     *
     * <p>Every lamp block shares one blockstate; the wood is read from the block entity when the
     * block is drawn, which is why these models are keyed by wood rather than by block.
     */
    public static @Nullable Identifier lampPart(String woodKey, String key) {
        var block = lampBlock();
        var wood = WOODS_BY_KEY.get(woodKey);
        var templatePath = block == null || wood == null ? null : PFMParts.template(block, key);
        if (templatePath == null) {
            return null;
        }

        var cacheKey = woodKey + "|" + key;
        var existing = LAMP_MODELS.get(cacheKey);
        if (existing != null) {
            return existing;
        }

        var id = Identifier.fromNamespaceAndPath(NS,
                "block/patch/basic_lamp/" + woodKey + "/" + key);
        var template = Identifier.fromNamespaceAndPath(NS, templatePath);
        var json = new JsonObject();
        json.addProperty("parent", template.toString());

        var declared = new LinkedHashMap<String, String>();
        collectTextures(template, declared, 0);
        var stripped = strippedTexture(wood);
        var overrides = new JsonObject();
        for (var entry : declared.entrySet()) {
            if (entry.getValue().contains("stripped_oak_log")) {
                overrides.addProperty(entry.getKey(), stripped);
            }
        }
        if (!overrides.isEmpty()) {
            json.add("textures", overrides);
        }

        CHILD.put(id, json);
        LAMP_MODELS.put(cacheKey, id);
        return id;
    }

    /**
     * The lamp item model: the shade, a pole of this wood and the unlit bulb, merged into one
     * model. Unlike a blockstate, an item model cannot be assembled from several parts.
     */
    public static @Nullable Identifier lampItemModel(String color, String woodKey) {
        var wood = WOODS_BY_KEY.get(woodKey);
        var shade = lampShadeTexture(color);
        if (wood == null || shade == null) {
            return null;
        }

        var id = Identifier.fromNamespaceAndPath(NS,
                "block/patch/basic_lamp/" + color + "_" + woodKey + "/item");
        if (CHILD.containsKey(id)) {
            return id;
        }

        var woodTexture = strippedTexture(wood);
        var json = merge(List.of(
                        new Piece("block/basic_lamp/basic_lamp_shade", 0, false),
                        new Piece("block/basic_lamp/basic_lamp_single", 0, false),
                        new Piece("block/basic_lamp/basic_lamp_light_bulb_off", 0, false)),
                value -> {
                    if (value.contains("stripped_oak_log")) {
                        return woodTexture;
                    }
                    if (value.contains("lamp_shade")) {
                        return shade.toString();
                    }
                    return value;
                });
        if (json == null) {
            return null;
        }

        CHILD.put(id, json);
        return id;
    }

    /**
     * Centres a two block long model and gives it the display transforms a block item has.
     *
     * <p>Item space draws a block model centred on the origin, so a model twice as long as a block
     * hangs half a block off to one side; moving its geometry back by half a block centres it. The
     * ground transform is the vanilla one, which also lifts the model off the floor - without it the
     * bottom faces of the frame sit exactly on the ground and flicker against it.
     */
    private static void centre(JsonObject json) {
        if (json.has("elements")) {
            for (var element : json.getAsJsonArray("elements")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                shift(element.getAsJsonObject(), "from", -8);
                shift(element.getAsJsonObject(), "to", -8);
            }
        }

        var display = new JsonObject();
        display.add("gui", displayTransform(new int[]{30, 225, 0}, new float[]{0, 0, 0}, 0.625f));
        display.add("ground", displayTransform(new int[]{0, 0, 0}, new float[]{0, 3, 0}, 0.25f));
        display.add("fixed", displayTransform(new int[]{0, 180, 0}, new float[]{0, 0, 0}, 0.5f));
        display.add("thirdperson_righthand", displayTransform(new int[]{75, 45, 0}, new float[]{0, 2.5f, 0}, 0.375f));
        display.add("thirdperson_lefthand", displayTransform(new int[]{75, 45, 0}, new float[]{0, 2.5f, 0}, 0.375f));
        display.add("firstperson_righthand", displayTransform(new int[]{0, 45, 0}, new float[]{0, 0, 0}, 0.4f));
        display.add("firstperson_lefthand", displayTransform(new int[]{0, 45, 0}, new float[]{0, 0, 0}, 0.4f));
        json.add("display", display);
    }

    private static JsonObject displayTransform(int[] rotation, float[] translation, float scale) {
        var out = new JsonObject();
        var rot = new JsonArray();
        for (var value : rotation) {
            rot.add(value);
        }
        var move = new JsonArray();
        for (var value : translation) {
            move.add(value);
        }
        var size = new JsonArray();
        size.add(scale);
        size.add(scale);
        size.add(scale);
        out.add("rotation", rot);
        out.add("translation", move);
        out.add("scale", size);
        return out;
    }

    /**
     * The bed item: the whole frame plus both mattresses, which the mod draws as separate parts.
     *
     * <p>The full frame model runs from the foot to the head, so the foot mattress already sits at
     * its origin and the head mattress is moved one block along.
     */
    private static @Nullable Identifier bedItemModel(Block block) {
        var textures = textures(block);
        if (textures == null || textures.bed() == null) {
            return null;
        }

        var classic = BuiltInRegistries.BLOCK.getKey(block).getPath().contains("classic");
        var base = classic ? "block/classic_bed" : "block/simple_bed";
        var frame = base + "/template/full/" + (classic ? "classic_bed" : "simple_bed");

        var id = Identifier.fromNamespaceAndPath(NS,
                "block/patch/" + BuiltInRegistries.BLOCK.getKey(block).getPath() + "/item");
        if (CHILD.containsKey(id)) {
            return id;
        }

        // The mattresses are modelled across the bed - their own width runs along the bed's length -
        // while the frame runs along it, so both have to be turned a quarter turn to sit inside it.
        // The head mattress is then moved to the far half, where its pillow ends up against the
        // headboard; without the turn it lies across the bed and overhangs the frame.
        var json = merge(List.of(
                        new Piece(frame, 0, false),
                        new Piece(base + "/mattresses/red_foot_mattress", 0, true),
                        new Piece(base + "/mattresses/red_head_mattress", 16, true)),
                value -> {
                    if (value.contains("entity/bed/")) {
                        return textures.bed();
                    }
                    if (textures.primary() != null && value.endsWith("_planks")) {
                        USED_TEXTURES.add(Identifier.parse(textures.primary()));
                        return textures.primary();
                    }
                    return value;
                });
        if (json == null) {
            return null;
        }

        centre(json);
        CHILD.put(id, json);
        return id;
    }

    /**
     * A basic desk's item: the top and all four legs, which the mod draws as five separate models.
     *
     * <p>{@code basic_desk_base} is the top surface alone. The legs are {@code basic_desk_north_east}
     * and its three siblings, each a single post at one corner, so the item model concatenates the
     * five and nothing has to be moved or turned.
     */
    private static @Nullable Identifier deskItemModel(Block block) {
        var id = Identifier.fromNamespaceAndPath(NS,
                "block/patch/" + BuiltInRegistries.BLOCK.getKey(block).getPath() + "/item");
        if (CHILD.containsKey(id)) {
            return id;
        }

        var json = merge(List.of(
                        new Piece("block/basic_desk/basic_desk_base", 0, false),
                        new Piece("block/basic_desk/basic_desk_north_east", 0, false),
                        new Piece("block/basic_desk/basic_desk_north_west", 0, false),
                        new Piece("block/basic_desk/basic_desk_south_east", 0, false),
                        new Piece("block/basic_desk/basic_desk_south_west", 0, false)),
                variantTextures(block));
        if (json == null) {
            return null;
        }

        CHILD.put(id, json);
        return id;
    }

    /**
     * Fills a merged model's textures with the block's own wood or stone.
     *
     * <p>A desk's five parts carry oak textures of their own - {@code block/oak_planks} for the top
     * and {@code block/oak_log} for the legs - so concatenating them without this made every basic
     * desk, whatever wood it was made of, an oak one.
     */

    /**
     * The whole furniture, merged from its parts, for the families whose blockstates only name one.
     *
     * <p>An item model cannot be assembled from several models, so the parts the mod draws in the
     * world are concatenated here instead. A table item is its top plus all four legs; a desk
     * cabinet item is its body plus its four legs.
     */
    private static @Nullable Identifier mergedItemModel(Block block) {
        var family = family(block);
        if (family == null) {
            return null;
        }

        String prefix;
        int[] indices;
        switch (family) {
            case "table_basic" -> {
                prefix = "bt";
                indices = new int[]{0, 1, 2, 3, 4, 7, 8};
            }
            case "coffee_table_basic" -> {
                prefix = "cb";
                indices = new int[]{0, 1, 2, 3, 4, 7, 8};
            }
            case "table_classic" -> {
                prefix = "ct";
                indices = new int[]{0, 1, 2, 3, 4};
            }
            case "coffee_table_classic" -> {
                prefix = "cc";
                indices = new int[]{0, 1, 2, 3, 4};
            }
            case "log_table" -> {
                prefix = "lt";
                indices = new int[]{0, 3};
            }
            case "dinner_table" -> {
                prefix = "dt";
                indices = new int[]{0, 3};
            }
            case "modern_dinner_table" -> {
                prefix = "md";
                indices = new int[]{0, 3};
            }
            case "coffee_table_modern" -> {
                prefix = "cm";
                indices = new int[]{0, 3};
            }
            case "desk_cabinet_basic" -> {
                prefix = "dc";
                indices = new int[]{0, 16, 17, 18, 19};
            }
            case "kitchen_wall_drawer" -> {
                prefix = "wd";
                indices = new int[]{0};
            }
            case "kitchen_wall_small_drawer" -> {
                prefix = "ws";
                indices = new int[]{0};
            }
            case "kitchen_wall_counter" -> {
                prefix = "wc";
                indices = new int[]{0};
            }
            default -> {
                return null;
            }
        }

        var id = Identifier.fromNamespaceAndPath(NS,
                "block/patch/" + BuiltInRegistries.BLOCK.getKey(block).getPath() + "/item");
        if (CHILD.containsKey(id)) {
            return id;
        }

        var pieces = new ArrayList<Piece>();
        for (var index : indices) {
            var template = PFMParts.template(block, prefix + index);
            if (template != null) {
                pieces.add(new Piece(template, 0, false));
            }
        }

        var json = merge(pieces, variantTextures(block));
        if (json == null) {
            return null;
        }

        CHILD.put(id, json);
        return id;
    }

    /**
     * Whether a stripped variant's secondary surfaces are planks.
     *
     * <p>PFM builds cabinetry and tables from a "plank and log" pair: the body uses the variant's
     * main block and the trim uses the secondary one. For stripped wood the main block is the
     * stripped log, but the trim stays the plain planks - which is why a stripped oak cabinet has
     * oak planks for its legs, not another oak log.
     */
    private static boolean usesPlankTrim(String path) {
        return path.contains("kitchen_") || path.contains("desk_") || path.contains("_table_")
                || path.contains("counter") || path.contains("drawer") || path.contains("cabinet")
                || path.contains("nightstand");
    }

    private static java.util.function.UnaryOperator<String> variantTextures(Block block) {
        var textures = textures(block);
        if (textures == null) {
            return value -> value;
        }

        return value -> {
            if (value.startsWith("#")) {
                return value;
            }

            var colon = value.indexOf(':');
            var path = colon < 0 ? value : value.substring(colon + 1);
            if (path.endsWith("_planks")) {
                return textures.replace("base", value);
            }
            if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem")
                    || path.endsWith("_hyphae")) {
                return textures.replace("legs", value);
            }
            return value;
        };
    }

    /**
     * One model file, how far its geometry has to move along the bed to line up with the frame, and
     * whether it has to be turned a quarter turn first.
     */
    private record Piece(String path, int shiftX, boolean turn) {
    }

    /**
     * Concatenates several model templates into the one model an item needs.
     *
     * <p>Item models cannot be assembled from parts the way blockstates can, and each face's
     * {@code tintindex} is dropped: the mod tints a lamp on the client from the stack, which a
     * static pack cannot do, so the textures are already tinted and the tint would only wash them
     * out.
     */
    private static @Nullable JsonObject merge(List<Piece> pieces, java.util.function.UnaryOperator<String> textures) {
        var elements = new JsonArray();
        var merged = new JsonObject();
        JsonObject display = null;

        for (var piece : pieces) {
            var json = template(Identifier.fromNamespaceAndPath(NS, piece.path()));
            if (json == null) {
                continue;
            }

            if (json.has("elements") && json.get("elements").isJsonArray()) {
                for (var element : json.getAsJsonArray("elements")) {
                    var copy = element.deepCopy();
                    if (piece.turn() && copy.isJsonObject()) {
                        // Three quarter turns, not one. A mattress is modelled across the bed, so it
                        // has to be turned to lie along it - but turning it the near way puts the
                        // head mattress' pillow at the wrong end and leaves both mattresses hanging
                        // over the footboard and the headboard, with a gap in the middle of the bed.
                        for (int turn = 0; turn < 3; turn++) {
                            turnQuarter(copy.getAsJsonObject());
                        }
                    }
                    if (piece.shiftX() != 0 && copy.isJsonObject()) {
                        shift(copy.getAsJsonObject(), "from", piece.shiftX());
                        shift(copy.getAsJsonObject(), "to", piece.shiftX());
                    }
                    stripTint(copy);
                    elements.add(copy);
                }
            }

            if (json.has("textures") && json.get("textures").isJsonObject()) {
                for (var entry : json.getAsJsonObject("textures").entrySet()) {
                    if (!entry.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    merged.addProperty(entry.getKey(), textures.apply(entry.getValue().getAsString()));
                }
            }

            if (display == null && json.has("display") && json.get("display").isJsonObject()) {
                display = json.getAsJsonObject("display").deepCopy();
            }
        }

        if (elements.isEmpty()) {
            return null;
        }

        var out = new JsonObject();
        out.add("elements", elements);
        out.add("textures", merged);
        if (display != null) {
            out.add("display", display);
        }
        return out;
    }

    /**
     * Turns one element a quarter turn about the block's centre.
     *
     * <p>The bed's mattresses are modelled across the bed, so composing them into the bed's own
     * length means turning them 90 degrees. The turn maps {@code x} to {@code z} and {@code z} to
     * {@code 16 - x}; the faces follow, north to west, west to south, south to east and east to
     * north. Texture coordinates stay as the mattress models authored them.
     */
    private static void turnQuarter(JsonObject element) {
        var from = element.getAsJsonArray("from");
        var to = element.getAsJsonArray("to");
        if (from != null && to != null && from.size() >= 3 && to.size() >= 3) {
            var minX = from.get(0).getAsFloat();
            var minY = from.get(1).getAsFloat();
            var minZ = from.get(2).getAsFloat();
            var maxX = to.get(0).getAsFloat();
            var maxY = to.get(1).getAsFloat();
            var maxZ = to.get(2).getAsFloat();

            setCoordinate(from, 0, minZ);
            setCoordinate(from, 1, minY);
            setCoordinate(from, 2, 16.0f - maxX);
            setCoordinate(to, 0, maxZ);
            setCoordinate(to, 1, maxY);
            setCoordinate(to, 2, 16.0f - minX);
        }

        if (element.has("faces") && element.get("faces").isJsonObject()) {
            var faces = new JsonObject();
            for (var face : element.getAsJsonObject("faces").entrySet()) {
                var name = switch (face.getKey()) {
                    case "north" -> "west";
                    case "west" -> "south";
                    case "south" -> "east";
                    case "east" -> "north";
                    default -> face.getKey();
                };
                faces.add(name, face.getValue());
            }
            element.add("faces", faces);
        }
    }

    private static void setCoordinate(JsonArray coordinates, int index, float value) {
        coordinates.set(index, new com.google.gson.JsonPrimitive(value));
    }

    private static void shift(JsonObject element, String key, int shift) {
        if (!element.has(key) || !element.get(key).isJsonArray()) {
            return;
        }
        var coordinates = element.getAsJsonArray(key);
        if (coordinates.isEmpty()) {
            return;
        }
        var x = coordinates.get(0);
        if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isNumber()) {
            coordinates.set(0, new com.google.gson.JsonPrimitive(x.getAsFloat() + shift));
        }
    }

    private static void stripTint(JsonElement element) {
        if (!element.isJsonObject()) {
            return;
        }
        var faces = element.getAsJsonObject().get("faces");
        if (faces == null || !faces.isJsonObject()) {
            return;
        }
        for (var face : faces.getAsJsonObject().entrySet()) {
            if (face.getValue().isJsonObject()) {
                face.getValue().getAsJsonObject().remove("tintindex");
            }
        }
    }

    /** The wood key a lamp's variant component names, if this server knows that wood. */
    public static @Nullable String lampWoodKey(@Nullable Identifier variant) {
        if (variant == null) {
            return null;
        }

        var key = variant.getPath();
        if (!variant.getNamespace().equals("minecraft")) {
            key = key + "_" + variant.getNamespace();
        }
        woods();
        return WOODS_BY_KEY.containsKey(key) ? key : null;
    }

    /** The shade colour a lamp's colour component names, as PFM writes it. */
    public static String lampColorName(@Nullable DyeColor color) {
        if (color == null) {
            return "white";
        }
        var name = color.getSerializedName();
        return COLORS.contains(name) ? name : "white";
    }

    /**
     * The office chair model for one colour.
     *
     * <p>The chair is an entity, so there is no blockstate to pick a model from: the colour lives on
     * the entity and on the item's component. PFM tints the seat and the back while rendering with a
     * tint type a vanilla client cannot read, so the two sprites are tinted here and the model only
     * substitutes them.
     */
    public static @Nullable Identifier officeChairItemModel(String color) {
        var id = Identifier.fromNamespaceAndPath(NS, "block/patch/office_chair/" + color + "/item");
        if (CHILD.containsKey(id)) {
            return id;
        }

        var seat = officeChairTexture("seat", color);
        var back = officeChairTexture("back", color);
        if (seat == null || back == null) {
            return null;
        }

        var json = new JsonObject();
        json.addProperty("parent", NS + ":block/office_chair/office_chair");
        var textures = new JsonObject();
        // The chair model names its sprites "2" (legs), "3" (seat) and "4" (back).
        textures.addProperty("3", seat.toString());
        textures.addProperty("4", back.toString());
        json.add("textures", textures);

        CHILD.put(id, json);
        return id;
    }

    private static @Nullable Identifier officeChairTexture(String name, String color) {
        var id = Identifier.fromNamespaceAndPath(NS, "block/patch/office_chair/" + color + "/" + name);
        if (GENERATED_TEXTURES.containsKey(id)) {
            return id;
        }

        var source = textureFile(Identifier.fromNamespaceAndPath(NS, "block/office_chair/office_chair_" + name));
        if (source == null) {
            return null;
        }

        try {
            GENERATED_TEXTURES.put(id, tintPng(Files.readAllBytes(source), fireworkColor(color)));
            return id;
        } catch (IOException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not tint the office chair {}", name, exception);
            return null;
        }
    }

    /** The dye colour PFM multiplies a chair's sprites with. */
    private static int fireworkColor(String name) {
        for (var color : DyeColor.values()) {
            if (color.getSerializedName().equals(name)) {
                return color.getFireworkColor();
            }
        }
        return 0xFFFFFF;
    }

    private static String strippedTexture(Wood wood) {
        var block = wood.strippedLog != null ? wood.strippedLog : wood.log;
        var texture = blockTexture(block, wood.token, "stripped_log");
        if (texture == null) {
            // PFM's herringbone planks are furniture planks, not a wood type, so there is no log
            // for them. A lamp built from them has a pole of the wood they were cut from.
            var base = baseWood(wood.token);
            if (base != null) {
                return strippedTexture(base);
            }
            return "minecraft:block/stripped_oak_log";
        }
        USED_TEXTURES.add(Identifier.parse(texture));
        return texture;
    }

    /** The wood a herringbone plank was cut from, or null for a real wood. */
    private static @Nullable Wood baseWood(String token) {
        var index = token.indexOf("_herringbone");
        if (index <= 0) {
            return null;
        }

        var base = token.substring(0, index);
        for (var wood : woods()) {
            if (wood.token.equals(base)) {
                return wood;
            }
        }
        return null;
    }

    private static void addModTextures(ResourcePackBuilder builder) {
        int copied = 0;
        for (var texture : USED_TEXTURES) {
            if (texture.getNamespace().equals("minecraft") || GENERATED_TEXTURES.containsKey(texture)) {
                continue;
            }

            var file = textureFile(texture);
            if (file == null) {
                PaladinsFurniturePatch.LOGGER.warn("Texture {} is used by a furniture variant but was not found", texture);
                continue;
            }

            try {
                builder.addData("assets/" + texture.getNamespace() + "/textures/" + texture.getPath() + ".png",
                        Files.readAllBytes(file));
                copied++;
            } catch (IOException exception) {
                PaladinsFurniturePatch.LOGGER.warn("Could not add texture {}", texture, exception);
            }
        }

        if (copied > 0) {
            PaladinsFurniturePatch.LOGGER.info("Copied {} variant textures from other mods into the pack", copied);
        }
    }

    private static final List<String> COLORS = List.of(
            "light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black");

    private static final List<String> STONES = List.of(
            "deepslate", "blackstone", "granite", "diorite", "andesite", "tuff", "calcite", "quartz", "stone");
}
