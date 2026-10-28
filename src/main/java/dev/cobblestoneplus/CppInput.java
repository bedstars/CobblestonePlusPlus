package dev.cobblestoneplus;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWScrollCallbackI;

import java.util.HashSet;
import java.util.Set;

/**
 * Wraps GLFW's mouse button and scroll callbacks so our screens get clicks and the wheel without
 * depending on Screen method signatures (which change between Minecraft versions). Everything else
 * passes straight through to Minecraft.
 */
final class CppInput {
    private CppInput() {}

    static long window;
    static boolean installed;
    private static GLFWMouseButtonCallback myMouse;
    private static GLFWScrollCallback myScroll;
    private static GLFWMouseButtonCallbackI prevMouse;
    private static GLFWScrollCallbackI prevScroll;
    private static final Set<Integer> consumed = new HashSet<>();

    static void ensure() {
        if (installed) return;
        long w = GLFW.glfwGetCurrentContext();
        if (w == 0L) return;
        window = w;
        myMouse = GLFWMouseButtonCallback.create(CppInput::onMouse);
        GLFWMouseButtonCallback pm = GLFW.glfwSetMouseButtonCallback(w, myMouse);
        prevMouse = pm != null ? pm : (a, b, c, d) -> {};
        myScroll = GLFWScrollCallback.create(CppInput::onScroll);
        GLFWScrollCallback ps = GLFW.glfwSetScrollCallback(w, myScroll);
        prevScroll = ps != null ? ps : (a, b, c) -> {};
        installed = true;
    }

    /** Cursor in GUI-scaled coordinates. */
    static double[] gui() {
        if (!installed) return new double[]{-1, -1};
        Minecraft mc = Minecraft.getInstance();
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(window, x, y);
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetWindowSize(window, w, h);
        return new double[]{
                x[0] * mc.getWindow().getGuiScaledWidth() / Math.max(1, w[0]),
                y[0] * mc.getWindow().getGuiScaledHeight() / Math.max(1, h[0])};
    }

    private static void onMouse(long w, int button, int action, int mods) {
        try {
            if (action == GLFW.GLFW_RELEASE && consumed.remove(button)) {
                if (Scr.get(Minecraft.getInstance()) instanceof CppScreen s) s.onMouse(button, action);
                return;
            }
            if (Scr.get(Minecraft.getInstance()) instanceof CppScreen s) {
                if (action == GLFW.GLFW_PRESS) consumed.add(button);
                s.onMouse(button, action);
                return;
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("mouse hook", t);
        }
        prevMouse.invoke(w, button, action, mods);
    }

    private static void onScroll(long w, double dx, double dy) {
        try {
            if (Scr.get(Minecraft.getInstance()) instanceof CppScreen s) {
                s.onScroll(dy);
                return;
            }
        } catch (Throwable t) {
            CobblestonePlus.LOG.warn("scroll hook", t);
        }
        prevScroll.invoke(w, dx, dy);
    }
}
