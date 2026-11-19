package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackType;

import java.lang.reflect.Field;

/**
 * Keeps Paladin's Furniture's client-only runtime asset pack out of dedicated-server resource
 * loading.
 *
 * <p>PFM injects into Fabric's {@code ModResourcePackCreator.loadPacks} and, when the creator's
 * pack type is {@code CLIENT_RESOURCES}, builds a generated asset pack. That code touches
 * {@code AbstractBakedModel}, which implements the client-only {@code BlockStateModel}; on a
 * dedicated server the class cannot load. The patch supplies those assets through Polymer instead,
 * so the client branch is skipped while the server-data branch is left alone.
 *
 * <p>The pack type is kept in a private final field on Fabric's creator. Its value is hidden only
 * for the duration of the other TAIL injections and restored immediately afterwards, so Fabric's
 * own resource loading still sees the real type.
 */
public final class PFMClientPackGuard {
    private static final ThreadLocal<PackType> HIDDEN_TYPE = new ThreadLocal<>();
    private static Field typeField;

    private PFMClientPackGuard() {
    }

    public static void hideFromPFM(Object creator) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            return;
        }

        try {
            var field = typeField(creator);
            if (field.get(creator) != PackType.CLIENT_RESOURCES) {
                return;
            }

            HIDDEN_TYPE.set(PackType.CLIENT_RESOURCES);
            field.set(creator, null);
        } catch (ReflectiveOperationException exception) {
            PaladinsFurniturePatch.LOGGER.warn(
                    "Could not hide PFM's client resource pack from the dedicated server", exception);
        }
    }

    public static void restore(Object creator) {
        var type = HIDDEN_TYPE.get();
        if (type == null) {
            return;
        }

        try {
            typeField(creator).set(creator, type);
        } catch (ReflectiveOperationException exception) {
            PaladinsFurniturePatch.LOGGER.warn(
                    "Could not restore Fabric's resource pack type after PFM's client pack", exception);
        } finally {
            HIDDEN_TYPE.remove();
        }
    }

    private static Field typeField(Object creator) throws NoSuchFieldException {
        if (typeField == null) {
            typeField = creator.getClass().getDeclaredField("type");
            typeField.setAccessible(true);
        }

        return typeField;
    }
}
