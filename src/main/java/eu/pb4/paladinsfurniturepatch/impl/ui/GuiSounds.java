package eu.pb4.paladinsfurniturepatch.impl.ui;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The click a vanilla button makes, sent to the one player who pressed it.
 *
 * <p>The screens are built on the server, so they cannot borrow Polydex's client-side
 * {@code PositionedSoundInstance}; the packet is the equivalent. It goes to the clicking player only
 * instead of through the level's sound broadcast, which is what keeps a workbench click from being
 * audible to everyone standing nearby.
 */
public final class GuiSounds {
    private GuiSounds() {
    }

    public static void click(ServerPlayer player) {
        player.connection.send(new ClientboundSoundPacket(SoundEvents.UI_BUTTON_CLICK,
                SoundSource.MASTER, player.getX(), player.getY(), player.getZ(),
                1.0f, 1.0f, player.getRandom().nextLong()));
    }
}
