package de.oneblock;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.MinecraftServer;

/**
 * Fuehrt Vanilla-Befehle als Server aus. Fuer Dinge wie Partikel, Sounds, Effekte, tellraw, title,
 * setblock, forceload, fill und loot nutzt die Mod bewusst die gleichen (bewaehrten) Befehle wie das
 * Datapack; die Spiellogik selbst laeuft komplett in Java.
 */
final class Cmd {
    private Cmd() {}

    static void run(MinecraftServer server, String command) {
        try {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), command);
        } catch (Exception e) {
            OneBlockMod.LOG.warn("Befehl fehlgeschlagen: {} ({})", command, e.toString());
        }
    }

    /** Gibt das Ergebnis eines Befehls zurueck (z. B. "time query daytime"), oder -1 bei Fehler. */
    static int query(MinecraftServer server, String command) {
        try {
            return server.getCommands().getDispatcher().execute(
                    command, server.createCommandSourceStack().withSuppressedOutput());
        } catch (CommandSyntaxException e) {
            return -1;
        }
    }
}
