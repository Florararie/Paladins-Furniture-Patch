package eu.pb4.paladinsfurniturepatch.impl.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The look of the patch's container screens.
 *
 * <p>A vanilla client draws a chest screen from a fixed texture and only knows the slot grid. SGui
 * inherits that, so the only way to replace the chest frontend is to paint over it: the screen title
 * is sent as a font glyph that consists of a single image, the whole panel, placed on top of the
 * chest background. Negative-width space characters before and after it shift the glyph to the panel
 * origin and move the real title back to where the client normally draws it.
 *
 * <p>The panel itself is built from Paladin's Furniture's own container textures - its title bar,
 * its palette and its slot frames - so the screens look like the mod's screens rather than like a
 * chest. The player's own inventory is drawn where the chest screen puts it, because those slots are
 * the client's and cannot be moved.
 */
public final class GuiPack {
    private static final String NS = PaladinsFurniturePatch.MOD_ID;
    private static final String TARGET = PaladinsFurniturePatch.TARGET_MOD_ID;

    /** Vanilla's slot grid for a chest screen: nine cells, eighteen pixels apart. */
    public static final int SLOT = 18;
    public static final int GRID_X = 8;
    public static final int GRID_Y = 18;
    public static final int ROWS_PLAYER = 36;

    private static final char SPACE_LEFT = '\uE001';
    private static final char SPACE_RIGHT = '\uE002';
    // An anvil screen puts its title at x = 60 instead of the chest screen's x = 8, so the search
    // panel needs its own pair of offsets. Reusing the chest pair shifted the whole panel right.
    private static final char SEARCH_SPACE_LEFT = '\uE003';
    private static final char SEARCH_SPACE_RIGHT = '\uE004';
    private static final char GLYPH_FIRST = '\uE100';
    private static final char SEARCH_GLYPH = '\uE0F0';
    private static final String SEARCH_PANEL_ID = "workbench_search";
    private static final int SEARCH_PANEL_HEIGHT = 166;

    public static final Style STYLE = Style.EMPTY
            .withColor(0xFFFFFF)
            .withFont(new FontDescription.Resource(glyphFont()));

    /**
     * One screen: the mod texture its art comes from, how many slot rows the client shows, and
     * which cell of that grid every container slot occupies.
     */
    public enum Screen {
        // Inputs at 59/77/95 by 19, the 3 by 3 at 59/77/95 by 41/59/77, the three results at 117 by
        // 41/59/77 and the fuel slot at 37,59.
        //
        // Container slots 0-2 are the inputs and slot 15 is the fuel. The two spare cells left of
        // the grid carry the gauges: cooking above, fuel below, beside the fuel slot.
        //
        // The gauge cells get no slot frame. A frame made them read as two more places to put
        // things, which is exactly what they are not; the pictures sit on the panel instead.
        OVEN("oven", "oven", 4,
                new int[]{3, 4, 5, 12, 13, 14, 21, 22, 23, 30, 31, 32, 15, 24, 33, 20},
                new int[0], 8, 113, 3, 15, 19, 10, 177, 1, 14, 14),
        MICROWAVE("microwave", "microwave", 3, new int[]{13}, new int[]{15}, 8, 84, 0, -1, -1, -1),
        // Input at 56,17, fuel at 56,53 and the result at 116,35. The freezer draws its flame two
        // pixels shorter than the oven does, so its picture is cut from its own bounds. The arrow
        // sits in the cell left of the result, so it points into it rather than away from it.
        FREEZER("freezer", "freezer", 3, new int[]{3, 21, 15}, new int[0], 8, 84, 1, 1, 12, 14,
                177, 0, 12, 12),
        TRASHCAN("trashcan", "microwave", 3,
                new int[]{3, 4, 5, 12, 13, 14, 21, 22, 23}, new int[]{17}, 8, 84, 0, -1, -1, -1),
        STORAGE_3("storage_3", "working_table", 3, cells(0, 27), new int[0], 8, 98, 0, -1, -1, -1),
        STORAGE_6("storage_6", "working_table", 6, cells(0, 54), new int[0], 8, 98, 0, -1, -1, -1);

        private final String id;
        private final String art;
        private final int rows;
        private final int[] cells;
        private final int[] extras;
        private final int artSlotX;
        private final int artSlotY;
        private final int inputSlots;
        private final int fuelSlot;
        private final int fuelCell;
        private final int progressCell;
        private final int flameX;
        private final int flameY;
        private final int flameW;
        private final int flameH;

        Screen(String id, String art, int rows, int[] cells, int[] extras, int artSlotX, int artSlotY,
               int inputSlots, int fuelSlot, int fuelCell, int progressCell) {
            this(id, art, rows, cells, extras, artSlotX, artSlotY, inputSlots, fuelSlot, fuelCell,
                    progressCell, 177, 1, 14, 14);
        }

        Screen(String id, String art, int rows, int[] cells, int[] extras, int artSlotX, int artSlotY,
               int inputSlots, int fuelSlot, int fuelCell, int progressCell,
               int flameX, int flameY, int flameW, int flameH) {
            this.id = id;
            this.art = art;
            this.rows = rows;
            this.cells = cells;
            this.extras = extras;
            this.artSlotX = artSlotX;
            this.artSlotY = artSlotY;
            this.inputSlots = inputSlots;
            this.fuelSlot = fuelSlot;
            this.fuelCell = fuelCell;
            this.progressCell = progressCell;
            this.flameX = flameX;
            this.flameY = flameY;
            this.flameW = flameW;
            this.flameH = flameH;
        }

        public int rows() {
            return this.rows;
        }

        public int height() {
            return 114 + SLOT * this.rows;
        }

        /** The cell each of the mod menu's container slots is shown in, in the menu's own order. */
        public int[] cellsFor(int containerSlots) {
            if (this.cells.length == containerSlots) {
                return this.cells;
            }

            // A menu this screen was not written for still gets shown, in reading order.
            var count = Math.min(Math.min(containerSlots, this.cells.length), this.rows * 9);
            var out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = this.cells[i];
            }
            return out;
        }

        /** Whether this screen burns fuel and shows how far cooking has got. */
        public boolean hasGauges() {
            return this.fuelCell >= 0 && this.progressCell >= 0;
        }

        /** The cell the fuel gauge is drawn in, or -1 when the screen has none. */
        public int fuelCell() {
            return this.fuelCell;
        }

        /** The cell the cooking gauge is drawn in, or -1 when the screen has none. */
        public int progressCell() {
            return this.progressCell;
        }

        /** The menu slot holding this container's fuel, or -1 when the screen burns none. */
        public int fuelSlot() {
            return this.fuelSlot;
        }

        /** Whether the menu's container slot at this position is an input to be processed. */
        public boolean isInput(int containerSlot) {
            return containerSlot >= 0 && containerSlot < this.inputSlots;
        }

        public static Screen forPath(String path, int containerSlots) {
            // Standalone white/gray/iron stoves use the same 16-slot OvenScreenHandler as the
            // kitchen-counter ovens, but their menu id is stove_block_entity rather than an "oven"
            // id. Without this they fell through to a generic storage screen.
            if (path.contains("oven") || path.contains("stove")) {
                return OVEN;
            }
            if (path.contains("microwave")) {
                return MICROWAVE;
            }
            if (path.contains("freezer")) {
                return FREEZER;
            }
            if (path.contains("trash")) {
                return TRASHCAN;
            }
            return containerSlots > 27 ? STORAGE_6 : STORAGE_3;
        }

        private List<Integer> frames() {
            var frames = new ArrayList<Integer>(this.cells.length + this.extras.length);
            Arrays.stream(this.cells).forEach(frames::add);
            Arrays.stream(this.extras).forEach(frames::add);
            return frames;
        }
    }

    private GuiPack() {
    }

    public static void setup() {
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> {
            var glyphs = new ArrayList<Screen>();
            for (var screen : Screen.values()) {
                var png = panel(screen);
                if (png == null) {
                    PaladinsFurniturePatch.LOGGER.warn("Could not build the {} screen background", screen.id);
                    continue;
                }

                builder.addData("assets/" + NS + "/textures/gui/" + screen.id + ".png", png);
                glyphs.add(screen);
            }

            var searchPanel = searchPanel();
            if (searchPanel != null) {
                builder.addData("assets/" + NS + "/textures/gui/" + SEARCH_PANEL_ID + ".png", searchPanel);
            } else {
                PaladinsFurniturePatch.LOGGER.warn("Could not build the workbench search background");
            }

            for (var name : ICONS) {
                addItem(builder, name, icon(name));
            }

            int gauges = 0;
            for (var screen : glyphs) {
                if (!screen.hasGauges()) {
                    continue;
                }
                // Each screen's flame is cut from its own art - the oven's warm one and the
                // freezer's cold one - so they need one set of pictures each.
                for (int level = 0; level <= FUEL_STEPS; level++) {
                    addItem(builder, fuelName(screen, level), fuelIcon(screen, level));
                    gauges++;
                }
            }
            // The cooking bar is drawn by hand and is the same picture on every screen.
            for (int step = 0; step <= PROGRESS_STEPS; step++) {
                addItem(builder, progressName(step), progressIcon(step));
                gauges++;
            }

            builder.addStringData("assets/" + NS + "/font/gui.json", font(glyphs, searchPanel != null));
            PaladinsFurniturePatch.LOGGER.info("Built {} furniture screen backgrounds, a workbench search panel, {} buttons and {} gauges.",
                    glyphs.size(), ICONS.length, gauges);
        });
    }

    /**
     * The title that also draws the panel. The glyph is prepended with a negative space that cancels
     * the title's own eight pixel left inset and appended with one that puts the real title back
     * where the client expects it, so the name still reads on top of the panel.
     */
    public static Component title(Screen screen, Component title) {
        var panel = Component.literal("" + SPACE_LEFT + glyph(screen) + SPACE_RIGHT).setStyle(STYLE);
        return Component.empty().append(panel).append(title);
    }

    public static char glyph(Screen screen) {
        return (char) (GLYPH_FIRST + screen.ordinal());
    }

    /** The one-screen workbench uses an anvil menu, so its panel is 176 by 166. */
    public static Component searchTitle(Component title) {
        var panel = Component.literal("" + SEARCH_SPACE_LEFT + SEARCH_GLYPH + SEARCH_SPACE_RIGHT)
                .setStyle(STYLE);
        return Component.empty().append(panel).append(title);
    }

    /** The on-screen buttons and scrollbar, drawn here so they match the screens instead of being dyes. */
    private static final String[] ICONS = {"ui_prev", "ui_next", "ui_clear", "ui_close",
            "ui_start", "ui_cooking", "ui_scroll_up", "ui_scroll_down", "ui_scroll_track",
            "ui_scroll_thumb", "ui_search", "ui_blank"};

    /** The item model of one of the screen's buttons. */
    public static Identifier ui(String name) {
        return Identifier.fromNamespaceAndPath(NS, name);
    }

    /** How many pictures each gauge has: thirteen lit steps, twelve cooking steps. */
    private static final int FUEL_STEPS = 13;
    private static final int PROGRESS_STEPS = 12;

    /** The item model of one screen's fuel gauge at one lit step. */
    public static Identifier fuel(Screen screen, int level) {
        return ui(fuelName(screen, Math.max(0, Math.min(FUEL_STEPS, level))));
    }

    /** The item model of the cooking gauge at one step. */
    public static Identifier progress(int step) {
        return ui(progressName(Math.max(0, Math.min(PROGRESS_STEPS, step))));
    }

    private static String fuelName(Screen screen, int level) {
        return "ui_fuel_" + screen.id + "_" + level;
    }

    private static String progressName(int step) {
        return "ui_progress_" + step;
    }

    /** Writes one button or gauge: its texture and the item definition that draws it in a slot. */
    private static void addItem(ResourcePackBuilder builder, String name, byte @Nullable [] png) {
        if (png == null) {
            return;
        }
        builder.addData("assets/" + NS + "/textures/item/" + name + ".png", png);
        builder.addStringData("assets/" + NS + "/items/" + name + ".json",
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + NS + ":item/" + name + "\"}}");
        builder.addStringData("assets/" + NS + "/models/item/" + name + ".json",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + NS + ":item/" + name + "\"}}");
    }

    /**
     * One fuel gauge: the mod's own flame, cut out of the screen art it is drawn from.
     *
     * <p>A furnace shows an unlit flame in grey and paints the lit one over it a row at a time, and
     * this does the same with the mod's flame: the whole sprite in panel grey underneath, then the
     * bottom rows of the real one. Nothing is drawn behind it, so an empty gauge is a grey flame on
     * the panel rather than a bar or an empty box.
     */
    private static byte @Nullable [] fuelIcon(Screen screen, int level) {
        var image = new BufferedImage(SLOT - 2, SLOT - 2, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        var art = texture(screen.art);
        if (art == null) {
            graphics.setColor(new Color(0xFF8B8B8B, true));
            graphics.fillOval(4, 1, 8, 14);
            if (level > 0) {
                graphics.setColor(new Color(0xFFFFA33A, true));
                graphics.fillRect(5, SLOT - 2 - level, 6, level);
            }
            graphics.dispose();
            return encode(image, "fuel gauge");
        }

        // The sprite is smaller than a slot - fourteen pixels tall in the oven's art, twelve in the
        // freezer's - so it is scaled up once and both the grey and the lit copy come from it.
        var flame = new BufferedImage(FLAME_SIZE, FLAME_SIZE, BufferedImage.TYPE_INT_ARGB);
        var scaled = flame.createGraphics();
        scaled.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        scaled.drawImage(art, 0, 0, FLAME_SIZE, FLAME_SIZE, screen.flameX, screen.flameY,
                screen.flameX + screen.flameW, screen.flameY + screen.flameH, null);
        scaled.dispose();

        graphics.drawImage(unlit(flame), 1, 1, null);
        if (level > 0) {
            int lit = Math.max(1, Math.min(FLAME_SIZE, Math.round(level * (float) FLAME_SIZE / FUEL_STEPS)));
            graphics.drawImage(flame, 1, 1 + FLAME_SIZE - lit, 1 + FLAME_SIZE, 1 + FLAME_SIZE,
                    0, FLAME_SIZE - lit, FLAME_SIZE, FLAME_SIZE, null);
        }

        graphics.dispose();
        return encode(image, "fuel gauge");
    }

    /** The flame's height once it has been scaled up to a slot. */
    private static final int FLAME_SIZE = 14;

    /** The same flame in panel grey, so an unlit gauge still shows the shape it is going to take. */
    private static BufferedImage unlit(BufferedImage flame) {
        var copy = new BufferedImage(flame.getWidth(), flame.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < flame.getHeight(); y++) {
            for (int x = 0; x < flame.getWidth(); x++) {
                int pixel = flame.getRGB(x, y);
                int alpha = (pixel >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                // The mod paints its flame onto a plate of the panel's own colour, which both its
                // screen and this one fill with C6C6C6. Greying that plate is what turned the gauge
                // into a grey block with a flame on it, so it is dropped instead and the flame is
                // left standing on the panel.
                if ((pixel & 0xFFFFFF) == PANEL) {
                    continue;
                }
                int luminance = (((pixel >> 16) & 0xFF) * 30 + ((pixel >> 8) & 0xFF) * 59 + (pixel & 0xFF) * 11) / 100;
                int grey = 0x44 + luminance * 0x50 / 255;
                copy.setRGB(x, y, (alpha << 24) | (grey << 16) | (grey << 8) | grey);
            }
        }
        return copy;
    }

    /** The colour a panel is filled with, and the colour the mod draws its flame plate on. */
    private static final int PANEL = 0xC6C6C6;

    /**
     * One cooking gauge: the arrow a furnace uses, filled from its tail to its head.
     *
     * <p>The mod paints progress over each of its nine cooking cells, which a slot grid of flat
     * cells has nowhere to put, so the screen carries a single arrow where a furnace carries one.
     * The arrow is drawn in grey and the part cooked so far in white, as vanilla draws it.
     */
    private static byte @Nullable [] progressIcon(int step) {
        var image = new BufferedImage(SLOT - 2, SLOT - 2, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();

        graphics.setColor(new Color(0xFF6E6E6E, true));
        graphics.fillPolygon(arrowShape(0));

        int filled = Math.min(ARROW_LENGTH, Math.round(step * (float) ARROW_LENGTH / PROGRESS_STEPS));
        if (filled > 0) {
            var clip = graphics.getClip();
            graphics.clipRect(0, 0, 1 + filled, SLOT);
            graphics.setColor(new Color(0xFFFFFFFF, true));
            graphics.fillPolygon(arrowShape(1));
            graphics.setClip(clip);
        }

        graphics.dispose();
        return encode(image, "cooking gauge");
    }

    /** How far the arrow's outline reaches across the icon. */
    private static final int ARROW_LENGTH = 14;

    /** The arrow's outline, and the same arrow one pixel inside it for the filled part. */
    private static Polygon arrowShape(int inset) {
        var arrow = new Polygon();
        if (inset == 0) {
            arrow.addPoint(1, 6);
            arrow.addPoint(9, 6);
            arrow.addPoint(9, 2);
            arrow.addPoint(15, 7);
            arrow.addPoint(9, 12);
            arrow.addPoint(9, 8);
            arrow.addPoint(1, 8);
        } else {
            // Inside the outline, so the filled arrow reads as the shaft filling up rather than as
            // a second arrow drawn beside the first one.
            arrow.addPoint(2, 7);
            arrow.addPoint(10, 7);
            arrow.addPoint(10, 4);
            arrow.addPoint(14, 8);
            arrow.addPoint(10, 11);
            arrow.addPoint(10, 8);
            arrow.addPoint(2, 8);
        }
        return arrow;
    }

    private static byte @Nullable [] encode(BufferedImage image, String what) {
        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not encode a {} icon", what, exception);
            return null;
        }
    }

    private static Identifier glyphFont() {
        return Identifier.fromNamespaceAndPath(NS, "gui");
    }

    private static int[] cells(int from, int to) {
        var array = new int[Math.max(0, to - from)];
        for (int i = 0; i < array.length; i++) {
            array[i] = from + i;
        }
        return array;
    }

    private static String font(List<Screen> glyphs, boolean searchPanel) {
        var font = new JsonObject();
        var providers = new JsonArray();

        var spaces = new JsonObject();
        spaces.addProperty("type", "space");
        var advances = new JsonObject();
        // The title is drawn eight pixels into the panel, and the panel is 176 wide.
        advances.addProperty(String.valueOf(SPACE_LEFT), -GRID_X);
        advances.addProperty(String.valueOf(SPACE_RIGHT), GRID_X - 176);
        // The search panel is drawn on an anvil screen, whose title starts at x = 60.
        advances.addProperty(String.valueOf(SEARCH_SPACE_LEFT), -60);
        advances.addProperty(String.valueOf(SEARCH_SPACE_RIGHT), 60 - 176);
        spaces.add("advances", advances);
        providers.add(spaces);

        if (searchPanel) {
            var bitmap = new JsonObject();
            bitmap.addProperty("type", "bitmap");
            bitmap.addProperty("file", NS + ":gui/" + SEARCH_PANEL_ID + ".png");
            bitmap.addProperty("ascent", 13);
            bitmap.addProperty("height", SEARCH_PANEL_HEIGHT);
            var chars = new JsonArray();
            chars.add(String.valueOf(SEARCH_GLYPH));
            bitmap.add("chars", chars);
            providers.add(bitmap);
        }

        for (var screen : glyphs) {
            var bitmap = new JsonObject();
            bitmap.addProperty("type", "bitmap");
            bitmap.addProperty("file", NS + ":gui/" + screen.id + ".png");
            // Ascent 13 puts the top of the image on the panel's top edge when the title is drawn
            // at y = 6, which is where a chest screen draws it.
            bitmap.addProperty("ascent", 13);
            bitmap.addProperty("height", screen.height());
            var chars = new JsonArray();
            chars.add(String.valueOf(glyph(screen)));
            bitmap.add("chars", chars);
            providers.add(bitmap);
        }

        font.add("providers", providers);
        return font.toString();
    }

    /**
     * The workbench browser's panel.
     *
     * <p>It is intentionally shaped like Polydex's search page rather than Paladin's workbench
     * art: a title and search field at the top, three rows of furniture, and a control strip at the
     * bottom. The middle is left transparent so the anvil screen's own inventory slots provide the
     * familiar nine-by-three grid. The search field is a transparent hole over the vanilla anvil
     * edit box, which is also what keeps typed text visible without SGui resetting the menu.
     */
    private static byte @Nullable [] searchPanel() {
        int width = 176;
        int height = SEARCH_PANEL_HEIGHT;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();

        var fill = new Color(0xFFC6C6C6, true);
        graphics.setColor(fill);
        graphics.fillRect(0, 0, width, 84);
        graphics.fillRect(0, 136, width, height - 136);
        graphics.fillRect(0, 84, 3, 52);
        graphics.fillRect(width - 3, 84, 3, 52);

        // The header gets a faint divider and a magnifier, so the field reads as a search box.
        graphics.setColor(new Color(0xFF8B8B8B, true));
        graphics.fillRect(3, 16, width - 6, 1);
        magnifier(graphics, 31, 18);

        // Leave the vanilla anvil edit box visible. The edit box is at x = 62, y = 24 and is 103 by
        // 12, and the widget draws its own sprite three pixels wider on each side and four pixels
        // up: 59..168 by 20..35. The cutout is that sprite exactly, so the field's own frame is what
        // borders it; an extra drawn frame only ever sat a pixel off from the sprite's edges.
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(59, 20, 110, 16);
        graphics.setComposite(AlphaComposite.SrcOver);

        // A dark separator makes the control strip feel like a footer instead of a fourth row.
        graphics.setColor(new Color(0xFF373737, true));
        graphics.fillRect(3, 136, width - 6, 1);
        graphics.setColor(new Color(0xFFFFFFFF, true));
        graphics.fillRect(3, 137, width - 6, 1);

        // The middle stays transparent, so the anvil's own inventory slots show through.
        bevel(graphics, width, height);
        graphics.dispose();

        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not encode the workbench search background", exception);
            return null;
        }
    }

    private static void magnifier(Graphics2D graphics, int x, int y) {
        graphics.setColor(new Color(0xFF373737, true));
        graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        graphics.drawOval(x + 1, y + 1, 10, 10);
        graphics.drawLine(x + 10, y + 10, x + 14, y + 14);
        graphics.setColor(new Color(0xFFE7E7E7, true));
        graphics.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        graphics.drawOval(x + 3, y + 3, 6, 6);
    }

    /**
     * Paints one panel: the mod's title bar and palette, a slot frame wherever a slot is shown, and
     * the player's inventory where the chest screen puts it.
     */
    private static byte[] panel(Screen screen) {
        var art = texture(screen.art);
        if (art == null) {
            return null;
        }

        int width = 176;
        int height = screen.height();
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();

        int fill = 0xFFC6C6C6;

        graphics.setColor(new Color(fill, true));
        graphics.fillRect(0, 0, width, height);

        // The mod's own title bar, complete with its border.
        int header = Math.min(17, art.getHeight());
        graphics.drawImage(art, 0, 0, 176, header, 0, 0, 176, header, null);

        var frame = frame();
        for (int cell : screen.frames()) {
            graphics.drawImage(frame, GRID_X + SLOT * (cell % 9), GRID_Y + SLOT * (cell / 9), null);
        }

        int playerY = SLOT * screen.rows + 31;
        for (int i = 0; i < ROWS_PLAYER; i++) {
            int column = i % 9;
            int row = i / 9;
            int y = playerY + (row < 3 ? row * SLOT : 58);
            graphics.drawImage(frame, GRID_X + SLOT * column, y, null);
        }

        // Drawn last, so it frames the mod's title bar as well.
        bevel(graphics, width, height);
        graphics.dispose();

        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not encode the {} screen background", screen.id, exception);
            return null;
        }
    }

    /**
     * One button: a bevelled plate in the panel's palette with a plain symbol on it, sixteen pixels
     * square so it sits in a slot like an item does.
     *
     * <p>These replace the bed, dye and clock items the screens used to show. A dye is not a button,
     * and a page arrow that is a vanilla arrow item reads as a piece of ammunition rather than as
     * pagination.
     */
    private static byte @Nullable [] icon(String name) {
        var image = new BufferedImage(SLOT - 2, SLOT - 2, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        if (!"ui_blank".equals(name)) {
            graphics.setColor(new Color(0xFF373737, true));
            graphics.fillRect(0, 0, 16, 16);
            graphics.setColor(new Color(0xFFC6C6C6, true));
            graphics.fillRect(1, 1, 14, 14);
            graphics.setColor(new Color(0xFFFFFFFF, true));
            graphics.fillRect(1, 1, 14, 1);
            graphics.fillRect(1, 1, 1, 14);
            graphics.setColor(new Color(0xFF8B8B8B, true));
            graphics.fillRect(1, 14, 14, 1);
            graphics.fillRect(14, 1, 1, 14);
        }

        switch (name) {
            case "ui_prev" -> arrow(graphics, false);
            case "ui_next" -> arrow(graphics, true);
            case "ui_scroll_up" -> scrollArrow(graphics, false);
            case "ui_scroll_down" -> scrollArrow(graphics, true);
            case "ui_scroll_track" -> scrollTrack(graphics);
            case "ui_scroll_thumb" -> scrollThumb(graphics);
            case "ui_clear" -> clear(graphics);
            case "ui_close" -> close(graphics);
            case "ui_search" -> search(graphics);
            // The anvil needs a non-empty input slot to keep its edit box enabled, but the
            // workbench panel hides that slot. This fully transparent model makes the marker
            // occupy the slot without drawing a paper icon over the panel.
            case "ui_blank" -> {
            }
            case "ui_start" -> start(graphics);
            case "ui_cooking" -> cooking(graphics);
            default -> {
                graphics.dispose();
                return null;
            }
        }

        graphics.dispose();

        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not encode the {} button", name, exception);
            return null;
        }
    }

    private static void arrow(Graphics2D graphics, boolean right) {
        var triangle = new Polygon();
        var shaft = new Polygon();
        if (right) {
            triangle.addPoint(6, 4);
            triangle.addPoint(11, 8);
            triangle.addPoint(6, 12);
            shaft.addPoint(4, 7);
            shaft.addPoint(8, 7);
            shaft.addPoint(8, 9);
            shaft.addPoint(4, 9);
        } else {
            triangle.addPoint(10, 4);
            triangle.addPoint(5, 8);
            triangle.addPoint(10, 12);
            shaft.addPoint(12, 7);
            shaft.addPoint(8, 7);
            shaft.addPoint(8, 9);
            shaft.addPoint(12, 9);
        }

        graphics.setColor(new Color(0xFF373737, true));
        graphics.fillPolygon(triangle);
        graphics.fillPolygon(shaft);
    }

    private static void scrollArrow(Graphics2D graphics, boolean down) {
        var triangle = new Polygon();
        if (down) {
            triangle.addPoint(4, 6);
            triangle.addPoint(12, 6);
            triangle.addPoint(8, 11);
        } else {
            triangle.addPoint(8, 5);
            triangle.addPoint(12, 10);
            triangle.addPoint(4, 10);
        }

        graphics.setColor(new Color(0xFF373737, true));
        graphics.fillPolygon(triangle);
    }

    private static void scrollTrack(Graphics2D graphics) {
        graphics.setColor(new Color(0xFF555555, true));
        graphics.fillRect(7, 4, 2, 8);
        graphics.setColor(new Color(0xFFAFAFAF, true));
        graphics.fillRect(7, 4, 1, 8);
    }

    private static void scrollThumb(Graphics2D graphics) {
        graphics.setColor(new Color(0xFF373737, true));
        graphics.fillRect(4, 5, 8, 6);
        graphics.setColor(new Color(0xFFE0E0E0, true));
        graphics.fillRect(5, 6, 6, 4);
        graphics.setColor(new Color(0xFFFFFFFF, true));
        graphics.fillRect(5, 6, 6, 1);
    }

    private static void close(Graphics2D graphics) {
        graphics.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(0xFFFFFFFF, true));
        graphics.drawLine(5, 5, 11, 11);
        graphics.drawLine(11, 5, 5, 11);
        graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(0xFF373737, true));
        graphics.drawLine(5, 5, 11, 11);
        graphics.drawLine(11, 5, 5, 11);
    }

    private static void clear(Graphics2D graphics) {
        // A white halo keeps a red cross readable on grey, at this size, in the same way the slot
        // edges do.
        graphics.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(0xFFFFFFFF, true));
        graphics.drawLine(5, 5, 11, 11);
        graphics.drawLine(11, 5, 5, 11);
        graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(0xFFB02A2A, true));
        graphics.drawLine(5, 5, 11, 11);
        graphics.drawLine(11, 5, 5, 11);
    }

    private static void search(Graphics2D graphics) {
        graphics.setColor(new Color(0xFF373737, true));
        graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        graphics.drawOval(3, 3, 8, 8);
        graphics.drawLine(10, 10, 14, 14);
        graphics.setColor(new Color(0xFFFFFFFF, true));
        graphics.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        graphics.drawOval(4, 4, 6, 6);
        graphics.drawLine(10, 10, 13, 13);
    }

    private static void start(Graphics2D graphics) {
        var play = new Polygon();
        play.addPoint(5, 3);
        play.addPoint(12, 8);
        play.addPoint(5, 13);

        graphics.setColor(new Color(0xFF1E4D20, true));
        graphics.fillPolygon(play);
        graphics.setColor(new Color(0xFF5FBF4A, true));
        var inner = new Polygon();
        inner.addPoint(6, 5);
        inner.addPoint(10, 8);
        inner.addPoint(6, 11);
        graphics.fillPolygon(inner);
    }

    private static void cooking(Graphics2D graphics) {
        graphics.setColor(new Color(0xFF3A3A3A, true));
        graphics.fillOval(3, 3, 10, 10);
        graphics.setColor(new Color(0xFFFFE08A, true));
        graphics.fillOval(4, 4, 8, 8);
        graphics.setColor(new Color(0xFF3A3A3A, true));
        graphics.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        graphics.drawLine(8, 8, 8, 5);
        graphics.drawLine(8, 8, 10, 9);
    }

    /**
     * The edge of a panel, with the depth a vanilla container has: a dark outline, a light top and
     * left, and a shadow along the bottom and right.
     */
    private static void bevel(Graphics2D graphics, int width, int height) {
        graphics.setColor(new Color(0xFF000000, true));
        graphics.drawRect(0, 0, width - 1, height - 1);
        graphics.setColor(new Color(0xFFFFFFFF, true));
        graphics.fillRect(1, 1, width - 2, 1);
        graphics.fillRect(1, 1, 1, height - 2);
        graphics.setColor(new Color(0xFF555555, true));
        graphics.fillRect(1, height - 2, width - 2, 1);
        graphics.fillRect(width - 2, 1, 1, height - 2);
    }

    /**
     * One slot, drawn the way vanilla draws one: a flat 8B8B8B square, a dark top and left edge, and
     * a white bottom and right edge. Those edges are what give a slot its depth, and a single pixel
     * each keeps the frame the 18 by 18 pixels the client's slot grid expects.
     */
    private static BufferedImage frame() {
        var copy = new BufferedImage(SLOT, SLOT, BufferedImage.TYPE_INT_ARGB);
        var graphics = copy.createGraphics();
        graphics.setColor(new Color(0xFF8B8B8B, true));
        graphics.fillRect(0, 0, SLOT, SLOT);
        graphics.setColor(new Color(0xFF373737, true));
        graphics.fillRect(0, 0, SLOT, 1);
        graphics.fillRect(0, 0, 1, SLOT);
        graphics.setColor(new Color(0xFFFFFFFF, true));
        graphics.fillRect(0, SLOT - 1, SLOT, 1);
        graphics.fillRect(SLOT - 1, 0, 1, SLOT);
        graphics.dispose();
        return copy;
    }

    private static BufferedImage texture(String name) {
        var container = FabricLoader.getInstance().getModContainer(TARGET).orElse(null);
        if (container == null) {
            return null;
        }

        var relative = "assets/" + TARGET + "/textures/gui/container/" + name + ".png";
        for (var root : container.getRootPaths()) {
            var file = root.resolve(relative);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                return ImageIO.read(Files.newInputStream(file));
            } catch (IOException exception) {
                PaladinsFurniturePatch.LOGGER.warn("Could not read the mod's {} screen art", name, exception);
                return null;
            }
        }
        return null;
    }
}
