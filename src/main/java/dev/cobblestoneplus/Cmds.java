package dev.cobblestoneplus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** /cpp ... client commands (never sent to the server). Most things now live in /cpp menu and /cpp edit. */
final class Cmds {
    private Cmds() {}

    private static int say(FabricClientCommandSource s, String m) {
        s.sendFeedback(Component.literal("\u00a7a[cobblestone++] \u00a7f" + m));
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> onOff(
            String name, String label, Consumer<Boolean> set) {
        return ClientCommandManager.literal(name)
                .then(ClientCommandManager.literal("on").executes(c -> {
                    set.accept(true);
                    Cfg.save();
                    return say(c.getSource(), label + " on.");
                }))
                .then(ClientCommandManager.literal("off").executes(c -> {
                    set.accept(false);
                    Cfg.save();
                    return say(c.getSource(), label + " off.");
                }));
    }

    static void register(CommandDispatcher<FabricClientCommandSource> d) {
        var bp = onOff("backpack", "Backpack HUD", v -> Cfg.I.backpackHud = v)
                .then(ClientCommandManager.literal("mode")
                        .then(ClientCommandManager.argument("mode", StringArgumentType.word()).executes(c -> {
                            String m = StringArgumentType.getString(c, "mode").toLowerCase();
                            if (!m.equals("recent") && !m.equals("all")) return say(c.getSource(), "Use: recent or all.");
                            Cfg.I.backpackMode = m;
                            Cfg.save();
                            return say(c.getSource(), "Mode: " + m);
                        })))
                .then(ClientCommandManager.literal("secs")
                        .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 120)).executes(c -> {
                            Cfg.I.backpackSecs = IntegerArgumentType.getInteger(c, "n");
                            Cfg.save();
                            return say(c.getSource(), "Items stay " + Cfg.I.backpackSecs + "s in recent mode.");
                        })))
                .then(ClientCommandManager.literal("rows")
                        .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 30)).executes(c -> {
                            Cfg.I.backpackMax = IntegerArgumentType.getInteger(c, "n");
                            Cfg.save();
                            return say(c.getSource(), "Max rows: " + Cfg.I.backpackMax);
                        })))
                .then(ClientCommandManager.literal("value").executes(c -> {
                    Cfg.I.backpackValue = !Cfg.I.backpackValue;
                    Cfg.save();
                    return say(c.getSource(), "Sell value " + (Cfg.I.backpackValue ? "shown." : "hidden."));
                }))
                .then(ClientCommandManager.literal("reset").executes(c -> {
                    Backpack.resetSession();
                    return say(c.getSource(), "Session gains reset.");
                }))
                .then(ClientCommandManager.literal("wipe").executes(c -> {
                    Backpack.wipe();
                    return say(c.getSource(), "Backpack data cleared. Open /backpack to re-sync.");
                }))
                .then(ClientCommandManager.literal("list").executes(c -> {
                    if (Backpack.items.isEmpty()) return say(c.getSource(), "Nothing tracked yet - open /backpack.");
                    for (Backpack.Entry e : Backpack.items.values()) {
                        say(c.getSource(), e.name + ": " + Nums.fmt(e.amount) + (e.unit > 0 ? " (~" + Nums.fmt(e.value()) + " " + e.cur + ")" : ""));
                    }
                    return 1;
                }));

        var bo = onOff("boosters", "Multiplier HUD", v -> Cfg.I.boostersHud = v);

        var pn = onOff("pins", "Pins HUD", v -> Cfg.I.pinsHud = v)
                .then(ClientCommandManager.literal("forget").executes(c -> {
                    Pins.forget();
                    return say(c.getSource(), "Learned recipes cleared. Open the crafting menus to re-learn.");
                }));

        var tr = onOff("treasure", "Treasure HUD", v -> Cfg.I.treasureHud = v)
                .then(ClientCommandManager.literal("mode")
                        .then(ClientCommandManager.argument("mode", StringArgumentType.word()).executes(c -> {
                            String m = StringArgumentType.getString(c, "mode").toLowerCase();
                            if (!m.equals("completion") && !m.equals("max")) return say(c.getSource(), "Use: completion or max.");
                            Cfg.I.treasureMode = m;
                            Cfg.save();
                            return say(c.getSource(), "Treasure goal: " + m + (m.equals("max") ? " (every relic 3/3)" : " (1 of each relic)"));
                        })))
                .then(ClientCommandManager.literal("forget").executes(c -> {
                    Treasure.forget();
                    return say(c.getSource(), "Treasure data cleared. Open The Treasury to re-learn.");
                }));

        var sh = onOff("shiny", "Shiny finder", v -> Cfg.I.shinyHud = v)
                .then(ClientCommandManager.literal("echo").executes(c -> {
                    Cfg.I.shinyEcho = !Cfg.I.shinyEcho;
                    Cfg.save();
                    return say(c.getSource(), "Echo tracking " + (Cfg.I.shinyEcho ? "on." : "off."));
                }))
                .then(ClientCommandManager.literal("rush").executes(c -> {
                    Cfg.I.shinyRush = !Cfg.I.shinyRush;
                    Cfg.save();
                    return say(c.getSource(), "Rush tracking " + (Cfg.I.shinyRush ? "on." : "off."));
                }))
                .then(ClientCommandManager.literal("range")
                        .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(16, 512)).executes(c -> {
                            Cfg.I.shinyRange = IntegerArgumentType.getInteger(c, "n");
                            Cfg.save();
                            return say(c.getSource(), "Range: " + Cfg.I.shinyRange + " blocks.");
                        })))
                .then(ClientCommandManager.literal("debug").executes(c -> {
                    List<String> l = Shiny.debug(net.minecraft.client.Minecraft.getInstance());
                    if (l.isEmpty()) return say(c.getSource(), "No glowing entities within 64 blocks.");
                    say(c.getSource(), "Glowing entities near you:");
                    for (String line : l) say(c.getSource(), line);
                    return 1;
                }));

        var menu = ClientCommandManager.literal("menu").executes(c -> {
            CobblestonePlus.openLater(1);
            return 1;
        });
        var edit = ClientCommandManager.literal("edit").executes(c -> {
            CobblestonePlus.openLater(2);
            return 1;
        });

        d.register(ClientCommandManager.literal("cpp").then(bp).then(bo).then(pn).then(tr).then(sh).then(menu).then(edit)
                .executes(c -> {
                    CobblestonePlus.openLater(1);
                    return 1;
                }));
    }
}
