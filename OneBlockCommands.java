package de.oneblock;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.function.Function;

/** Ersetzt die /trigger-Befehle des Datapacks durch echte Befehle. */
final class OneBlockCommands {
    private OneBlockCommands() {}

    /** Operator-Pruefung (Level 2). Falls sich die Permission-API aendert, nur diese Zeile anpassen. */
    private static boolean isOp(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("oneblock")
                .then(Commands.literal("islands")
                        .then(Commands.argument("anzahl", IntegerArgumentType.integer(1, Game.MAX_ISLANDS))
                                .executes(c -> {
                                    int n = IntegerArgumentType.getInteger(c, "anzahl");
                                    String err = Game.I.setIslands(n);
                                    if (err != null) return fail(c.getSource(), err);
                                    Game.I.tellAll("[{text:\"" + c.getSource().getTextName() + "\",color:\"white\"},"
                                            + "{text:\" hat die Inselanzahl auf \",color:\"gray\"},{text:\"" + n + "\",color:\"green\"},"
                                            + "{text:\" gesetzt. Jetzt /oneblock start (normale Welt) oder /oneblock start void (Void-Welt).\",color:\"gray\"}]");
                                    return 1;
                                })))
                .then(Commands.literal("start")
                        .executes(c -> start(c.getSource(), true))
                        .then(Commands.literal("void").executes(c -> start(c.getSource(), false))))
                .then(Commands.literal("reset").requires(OneBlockCommands::isOp)
                        .executes(c -> { Game.I.reset(); return 1; }))
                .then(Commands.literal("status").requires(OneBlockCommands::isOp)
                        .executes(c -> {
                            c.getSource().sendSuccess(() -> Component.literal("[OneBlock] " + Game.I.status()), false);
                            return 1;
                        }))
                .then(Commands.literal("island")
                        .then(Commands.argument("nummer", IntegerArgumentType.integer(1, Game.MAX_ISLANDS))
                                .executes(c -> withPlayer(c.getSource(),
                                        p -> { Game.I.claim(p, IntegerArgumentType.getInteger(c, "nummer")); return 1; }))))
                .then(Commands.literal("new")
                        .executes(c -> withPlayer(c.getSource(), p -> { Game.I.claimNew(p); return 1; })))
                .then(Commands.literal("list")
                        .executes(c -> withPlayer(c.getSource(), p -> { Game.I.list(p); return 1; })))
                .then(Commands.literal("menu")
                        .executes(c -> withPlayer(c.getSource(), p -> { IslandMenu.open(p); return 1; }))));
    }

    private static int start(CommandSourceStack src, boolean normal) {
        String err = Game.I.start(normal);
        return err == null ? 1 : fail(src, err);
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal(msg));
        return 0;
    }

    private static int withPlayer(CommandSourceStack src, Function<ServerPlayer, Integer> f) {
        ServerPlayer p = src.getPlayer();
        if (p == null) return fail(src, "Nur fuer Spieler.");
        return f.apply(p);
    }
}
