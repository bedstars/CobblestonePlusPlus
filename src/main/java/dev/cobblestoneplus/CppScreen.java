package dev.cobblestoneplus;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Base for our screens: input arrives through {@link CppInput}. ESC closes (vanilla behaviour). */
abstract class CppScreen extends Screen {
    CppScreen(String title) {
        super(Component.literal(title));
    }

    public boolean isPauseScreen() {
        return false;
    }

    abstract void onMouse(int button, int action);

    abstract void onScroll(double dy);
}
