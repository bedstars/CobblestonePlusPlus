package dev.cobblestoneplus;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** cobblestone++ - client-side QoL for play.cobblestone.gg. */
public final class CobblestonePlus implements ClientModInitializer {
    public static final String ID = "cobblestoneplus";
    public static final Logger LOG = LoggerFactory.getLogger("cobblestone++");

    public static KeyMapping K_MENU, K_EDIT;
    private static int pending, delay;

    /** Opens a screen a couple of ticks later (a command runs while the chat screen is still open). 1 = menu, 2 = HUD editor. */
    public static void openLater(int what) {
        pending = what;
        delay = 2;
    }

    @Override
    public void onInitializeClient() {
        Cfg.load();
        Backpack.load();
        Boosters.load();
        Pins.load();
        Treasure.load();
        Milestones.load();
        Widgets.init();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
        K_MENU = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + ID + ".menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_CONTROL, cat));
        K_EDIT = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + ID + ".edit", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));

        ClientTickEvents.END_CLIENT_TICK.register(Sync::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(Backpack::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(Boosters::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(Pins::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(Treasure::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(Milestones::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(Shiny::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(CobblestonePlus::onTick);

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                Backpack.onActionBar(message.getString());
            } else {
                String t = message.getString();
                Treasure.onChat(t);
                Boosters.onChat(t);
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "panels"), (graphics, tickCounter) -> Widgets.render(graphics, false));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Cmds.register(dispatcher));

        LOG.info("cobblestone++ loaded.");
    }

    private static void onTick(Minecraft mc) {
        if (mc.player == null) return;
        boolean free = Scr.get(mc) == null;
        while (K_MENU.consumeClick()) if (free) openLater(1);
        while (K_EDIT.consumeClick()) if (free) openLater(2);
        if (pending != 0 && --delay <= 0) {
            int p = pending;
            pending = 0;
            Scr.set(mc, p == 2 ? new EditorScreen() : new MenuScreen());
        }
    }
}
