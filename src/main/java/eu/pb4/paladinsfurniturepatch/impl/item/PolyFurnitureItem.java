package eu.pb4.paladinsfurniturepatch.impl.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.paladinsfurniturepatch.impl.PFMBlockStates;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * Shows one Paladin's Furniture item to a client that does not have the mod.
 *
 * <p>The client gets paper whose {@code minecraft:item_model} component names the item's own
 * definition from the mod's assets, so the inventory, the hand and item frames all draw the real
 * furniture model and read the real translation key.
 *
 * <p>A lamp is one item for every wood and colour: both are components on the stack, not item ids.
 * Its model therefore has to be chosen from the stack as well, or every lamp in an inventory shows
 * the same default. The model is taken from {@link PFMBlockStates}, which is what builds the merged
 * lamp models during pack generation, so a lamp always names a model that exists - a model made up
 * here from the components could easily not.
 */
public record PolyFurnitureItem(Item item, Identifier model) implements PolymerItem {
    private static boolean resolvedVariant;
    private static DataComponentType<?> variantComponent;
    private static boolean resolvedColor;
    private static DataComponentType<?> colorComponent;

    @Override
    public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
        return Items.PAPER;
    }

    /**
     * Lets the server run the real furniture item's block interaction for clients holding paper.
     *
     * <p>A client without the mod sends its right-click as a paper use. Polymer would otherwise stop
     * there, so furniture could be seen but never placed. Handing the interaction back to the real
     * item is the same mechanism the iron chest patch uses.
     */
    @Override
    public boolean isPolymerBlockInteraction(BlockState state, ServerPlayer player, InteractionHand hand,
                                             ItemStack stack, ServerLevel world, BlockHitResult hit,
                                             InteractionResult actionResult) {
        return true;
    }

    /** Sounds made by placing a furniture item belong to the server for these clients. */
    @Override
    public boolean isIgnoringBlockInteractionPlaySoundExceptedEntity(BlockState state, ServerPlayer player,
                                                                     InteractionHand hand, ItemStack stack,
                                                                     ServerLevel world, BlockHitResult hit) {
        return true;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        if (this.lamp()) {
            var color = colorOf(stack);
            var variant = variantOf(stack);
            var model = PFMBlockStates.lampItemModel(
                    PFMBlockStates.lampColorName(color),
                    PFMBlockStates.lampWoodKey(variant));
            if (model != null) {
                logLamp(stack, color, variant, model);
                return model;
            }

            // A wood this server does not know - a datapack one, or a lamp built before the pack
            // was generated - still gets a whole lamp instead of a model that does not exist.
            var fallback = PFMBlockStates.lampItemModel("white", "oak");
            if (fallback != null) {
                logLamp(stack, color, variant, fallback);
                return fallback;
            }
        }

        if (this.chair()) {
            var chair = chairModel(stack);
            if (chair != null) {
                return chair;
            }
        }

        return this.model;
    }

    /** One line per distinct lamp variant seen, to show what the components resolve to. */
    private static final java.util.Set<String> LOGGED_LAMPS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void logLamp(ItemStack stack, DyeColor color, Identifier variant, Identifier model) {
        var key = color + "|" + variant;
        if (LOGGED_LAMPS.size() < 8 && LOGGED_LAMPS.add(key)) {
            PaladinsFurniturePatch.LOGGER.info("Lamp stack {}: colour {}, wood {}, model {}",
                    stack.getHoverName().getString(), color, variant, model);
        }
    }

    /**
     * Applies the variant model explicitly as well as through {@link #getPolymerItemModel}, so a
     * stack that also carries its own item model component cannot pin every lamp to one look.
     */
    @Override
    public ItemStack getPolymerItemStack(ItemStack stack, TooltipFlag tooltip, PacketContext context,
                                         HolderLookup.Provider lookup) {
        var out = PolymerItemUtils.createItemStack(stack, tooltip, context, lookup);
        if (this.lamp() || this.chair()) {
            out.set(DataComponents.ITEM_MODEL, getPolymerItemModel(stack, context, lookup));
        }
        stripModComponents(out);
        return out;
    }

    /**
     * Removes the mod's own data components from a stack meant for a vanilla client.
     *
     * <p>A lamp's wood and colour, and every furniture's material, are components the mod registers
     * itself. A client without the mod has no id for them, so a stack carrying one cannot be decoded
     * at all - which is what a creative tab full of furniture did: the client dropped the connection
     * on the tab contents packet. Everything those components say is already expressed by the
     * {@code item_model} the stack names, so they are stripped rather than translated.
     */
    private static void stripModComponents(ItemStack stack) {
        java.util.List<DataComponentType<?>> unknown = new java.util.ArrayList<>(0);
        for (var component : stack.getComponents()) {
            var id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
            if (id == null || !id.getNamespace().equals("minecraft")) {
                unknown.add(component.type());
            }
        }

        for (var type : unknown) {
            stack.remove(type);
        }
    }

    private boolean lamp() {
        return this.model.getPath().contains("lamp");
    }

    private boolean chair() {
        return this.model.getPath().contains("office_chair");
    }

    /** The office chair model for a stack's colour, or the white chair when it carries none. */
    private static @Nullable Identifier chairModel(ItemStack stack) {
        var model = PFMBlockStates.officeChairItemModel(PFMBlockStates.lampColorName(colorOf(stack)));
        return model != null ? model : PFMBlockStates.officeChairItemModel("white");
    }

    /**
     * The item display that stands in for an office chair entity.
     *
     * <p>The chair is an entity, not a block: a client without the mod has no renderer for it and
     * would show nothing. An item display draws a model at the entity's position, so the chair model
     * is handed to the client this way, in the colour the chair was placed as.
     */
    public static ItemStack officeChairStack(@Nullable DyeColor color) {
        var out = new ItemStack(Items.PAPER);
        var model = PFMBlockStates.officeChairItemModel(PFMBlockStates.lampColorName(color));
        if (model == null) {
            model = PFMBlockStates.officeChairItemModel("white");
        }
        if (model != null) {
            out.set(DataComponents.ITEM_MODEL, model);
        }
        return out;
    }

    /** The model every furniture item was registered with, so a stack can be built outside a packet. */
    private static final java.util.Map<Item, Identifier> MODELS = new java.util.IdentityHashMap<>();

    public static void rememberModel(Item item, Identifier model) {
        MODELS.put(item, model);
    }

    /**
     * The client-facing form of one furniture stack, made here rather than while sending a packet.
     *
     * <p>A slot SGui draws is sent as its own packet, so the item conversion that turns a furniture
     * item into paper for a client without the mod never runs for it - the client is handed an item
     * it does not know and draws the missing model. Building that stack here does the same job for
     * those places: the paper, the model written on it, and the name and lore the stack carried.
     *
     * <p>This form has no context, so it cannot carry Polymer's hidden original-stack tag. Use the
     * context overload for stacks a creative client can click; otherwise the server has no way to
     * turn the paper back into the furniture item.
     */
    public static ItemStack toClientStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
            return stack;
        }

        var out = new ItemStack(Items.PAPER);
        out.setCount(stack.getCount());
        var model = modelOf(stack);
        if (model != null) {
            out.set(DataComponents.ITEM_MODEL, model);
        }
        out.set(DataComponents.CUSTOM_NAME, stack.getHoverName());
        var lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            out.set(DataComponents.LORE, lore);
        }
        return out;
    }

    /**
     * The client-facing form used where the client can send the stack back to the server.
     *
     * <p>Polymer stores the real server stack in the paper's {@code custom_data}; its packet codec
     * unwraps that when the client takes the item or clicks a slot. The manually built form above
     * drops the tag, so a creative tab entry became anonymous paper when clicked. Going through
     * Polymer's normal item conversion keeps the tag and the model/name/lore together.
     */
    public static ItemStack toClientStack(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
            return stack;
        }

        return PolymerItemUtils.getPolymerItemStack(stack, context, lookup);
    }

    /** The model a stack shows: a lamp's own variant, or the item's registered model. */
    private static @Nullable Identifier modelOf(ItemStack stack) {
        var path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.contains("lamp")) {
            var model = PFMBlockStates.lampItemModel(
                    PFMBlockStates.lampColorName(colorOf(stack)),
                    PFMBlockStates.lampWoodKey(variantOf(stack)));
            return model != null ? model : PFMBlockStates.lampItemModel("white", "oak");
        }
        if (path.contains("office_chair")) {
            return chairModel(stack);
        }

        return MODELS.get(stack.getItem());
    }

    /**
     * Puts a stack's own variant model on it directly.
     *
     * <p>The item conversion sets that model for everything the client is shown as an item, but a
     * stack built on the server and sent as it is - the workbench's recipe icons, or an item lying on
     * the ground - never passes through it. A lamp then falls back to a model id the pack may not
     * have and renders as the missing model, so the model is written onto the stack itself.
     */
    public static void applyModel(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        var path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        Identifier model = null;
        if (path.contains("lamp")) {
            model = PFMBlockStates.lampItemModel(
                    PFMBlockStates.lampColorName(colorOf(stack)),
                    PFMBlockStates.lampWoodKey(variantOf(stack)));
            if (model == null) {
                model = PFMBlockStates.lampItemModel("white", "oak");
            }
        } else if (path.contains("office_chair")) {
            model = chairModel(stack);
        }

        if (model != null) {
            stack.set(DataComponents.ITEM_MODEL, model);
        }
    }

    private static @Nullable DyeColor colorOf(ItemStack stack) {
        if (!resolvedColor) {
            resolvedColor = true;
            try {
                var components = Class.forName("com.unlikepaladin.pfm.items.PFMComponents");
                Field field;
                try {
                    field = components.getField("COLOR_COMPONENT");
                } catch (NoSuchFieldException exception) {
                    field = components.getDeclaredField("COLOR_COMPONENT");
                    field.setAccessible(true);
                }
                colorComponent = (DataComponentType<?>) field.get(null);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                PaladinsFurniturePatch.LOGGER.warn("Could not read the lamp's colour component", exception);
            }
        }

        if (colorComponent == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        var value = stack.get((DataComponentType<DyeColor>) colorComponent);
        return value;
    }

    private static @Nullable Identifier variantOf(ItemStack stack) {
        if (!resolvedVariant) {
            resolvedVariant = true;
            try {
                var components = Class.forName("com.unlikepaladin.pfm.items.PFMComponents");
                Field field;
                try {
                    field = components.getField("VARIANT_COMPONENT");
                } catch (NoSuchFieldException exception) {
                    field = components.getDeclaredField("VARIANT_COMPONENT");
                    field.setAccessible(true);
                }
                variantComponent = (DataComponentType<?>) field.get(null);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                PaladinsFurniturePatch.LOGGER.warn("Could not read the lamp's wood component", exception);
            }
        }

        if (variantComponent == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        var value = stack.get((DataComponentType<Identifier>) variantComponent);
        return value;
    }
}
