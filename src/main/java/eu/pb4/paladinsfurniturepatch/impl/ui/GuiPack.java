package eu.pb4.paladinsfurniturepatch.impl.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
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
    private static final char GLYPH_FIRST = '\uE100';

    public static final Style STYLE = Style.EMPTY
            .withColor(0xFFFFFF)
            .withFont(new FontDescription.Resource(glyphFont()));

    /**
     * One screen: the mod texture its art comes from, how many slot rows the client shows, and
     * which cell of that grid every container slot occupies.
     */
    public enum Screen {
        // The frame is copied from a player inventory slot: unlike a container slot, every one of
        // the mod's screens draws one there, so it is always a real slot frame.
        // The result slot stays at the top left, as the mod draws it; the list fills every other
        // cell of the six rows. Leaving a cell empty for the result elsewhere left a hole in the
        // third row of every page, which reads as a slot the player cannot use.
        WORKBENCH("workbench", "working_table", 6, new int[]{0}, cells(1, 54), 8, 98),
        // Inputs at 59/77/95 by 19, the 3 by 3 at 59/77/95 by 41/59/77, the three results at 117 by
        // 41/59/77 and the fuel slot at 37,59.
        OVEN("oven", "oven", 4,
                new int[]{3, 4, 5, 12, 13, 14, 21, 22, 23, 30, 31, 32, 15, 24, 33, 20},
                new int[0], 8, 113),
        MICROWAVE("microwave", "microwave", 3, new int[]{13}, new int[]{15}, 8, 84),
        // Input at 56,17, fuel at 56,53 and the result at 116,35.
        FREEZER("freezer", "freezer", 3, new int[]{3, 21, 15}, new int[0], 8, 84),
        TRASHCAN("trashcan", "microwave", 3,
                new int[]{3, 4, 5, 12, 13, 14, 21, 22, 23}, new int[]{17}, 8, 84),
        STORAGE_3("storage_3", "working_table", 3, cells(0, 27), new int[0], 8, 98),
        STORAGE_6("storage_6", "working_table", 6, cells(0, 54), new int[0], 8, 98);

        private final String id;
        private final String art;
        private final int rows;
        private final int[] cells;
        private final int[] extras;
        private final int artSlotX;
        private final int artSlotY;

        Screen(String id, String art, int rows, int[] cells, int[] extras, int artSlotX, int artSlotY) {
            this.id = id;
            this.art = art;
            this.rows = rows;
            this.cells = cells;
            this.extras = extras;
            this.artSlotX = artSlotX;
            this.artSlotY = artSlotY;
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

        public static Screen forPath(String path, boolean workbench, int containerSlots) {
            if (workbench) {
                return WORKBENCH;
            }
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

            for (var name : ICONS) {
                var png = icon(name);
                if (png == null) {
                    continue;
                }
                builder.addData("assets/" + NS + "/textures/item/" + name + ".png", png);
                builder.addStringData("assets/" + NS + "/items/" + name + ".json",
                        "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + NS + ":item/" + name + "\"}}");
                builder.addStringData("assets/" + NS + "/models/item/" + name + ".json",
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + NS + ":item/" + name + "\"}}");
            }

            builder.addStringData("assets/" + NS + "/font/gui.json", font(glyphs));
            PaladinsFurniturePatch.LOGGER.info("Built {} furniture screen backgrounds and {} buttons from the mod's own art.",
                    glyphs.size(), ICONS.length);
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

    /** The five on-screen buttons, drawn here so they match the screens instead of being dyes. */
    private static final String[] ICONS = {"ui_prev", "ui_next", "ui_clear", "ui_start", "ui_cooking"};

    /** The item model of one of the screen's buttons. */
    public static Identifier ui(String name) {
        return Identifier.fromNamespaceAndPath(NS, name);
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

    private static String font(List<Screen> glyphs) {
        var font = new JsonObject();
        var providers = new JsonArray();

        var spaces = new JsonObject();
        spaces.addProperty("type", "space");
        var advances = new JsonObject();
        // The title is drawn eight pixels into the panel, and the panel is 176 wide.
        advances.addProperty(String.valueOf(SPACE_LEFT), -GRID_X);
        advances.addProperty(String.valueOf(SPACE_RIGHT), GRID_X - 176);
        spaces.add("advances", advances);
        providers.add(spaces);

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

        // The panel's own edge, drawn last so it frames the mod's title bar as well. This is the
        // depth a vanilla container has: a dark outline, a light top and left, and a shadow along
        // the bottom and right. Without it the screen was a flat grey rectangle, and the slot
        // frames read as painted-on squares instead of places to put items.
        graphics.setColor(new Color(0xFF000000, true));
        graphics.drawRect(0, 0, width - 1, height - 1);
        graphics.setColor(new Color(0xFFFFFFFF, true));
        graphics.fillRect(1, 1, width - 2, 1);
        graphics.fillRect(1, 1, 1, height - 2);
        graphics.setColor(new Color(0xFF555555, true));
        graphics.fillRect(1, height - 2, width - 2, 1);
        graphics.fillRect(width - 2, 1, 1, height - 2);

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
     * One slot, drawn the way vanilla draws one: a flat 8B8B8B square, a dark top and left edge, and
     * a white bottom and right edge.
     *
     * <p>Those two edges are what give a slot its depth, and a screen without them looks flat however
     * well the rest is painted. A single pixel each, so the frame is still the 18 by 18 pixels the
     * client's slot grid expects.
     */
    /**
     * One button: a bevelled plate in the panel's palette with a plain symbol on it, sixteen pixels
     * square so it sits in a slot like an item does.
     *
     * <p>These replace the bed, dye and clock items the screens used to show. A dye is not a button,
     * and a page arrow that is a vanilla arrow item reads as a piece of ammunition rather than as
     * pagination.
     */
    private static byte @org.jspecify.annotations.Nullable [] icon(String name) {
        var image = new BufferedImage(SLOT - 2, SLOT - 2, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

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

        switch (name) {
            case "ui_prev" -> arrow(graphics, false);
            case "ui_next" -> arrow(graphics, true);
            case "ui_clear" -> clear(graphics);
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
        var triangle = new java.awt.Polygon();
        var shaft = new java.awt.Polygon();
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

    private static void clear(Graphics2D graphics) {
        // A white halo keeps a red cross readable on grey, at this size, in the same way the slot
        // edges do.
        graphics.setStroke(new java.awt.BasicStroke(3.5f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(0xFFFFFFFF, true));
        graphics.drawLine(5, 5, 11, 11);
        graphics.drawLine(11, 5, 5, 11);
        graphics.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(0xFFB02A2A, true));
        graphics.drawLine(5, 5, 11, 11);
        graphics.drawLine(11, 5, 5, 11);
    }

    private static void start(Graphics2D graphics) {
        var play = new java.awt.Polygon();
        play.addPoint(5, 3);
        play.addPoint(12, 8);
        play.addPoint(5, 13);

        graphics.setColor(new Color(0xFF1E4D20, true));
        graphics.fillPolygon(play);
        graphics.setColor(new Color(0xFF5FBF4A, true));
        var inner = new java.awt.Polygon();
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
        graphics.setStroke(new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        graphics.drawLine(8, 8, 8, 5);
        graphics.drawLine(8, 8, 10, 9);
    }

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

    private static int opaque(int argb) {
        return argb | 0xFF000000;
    }
}
