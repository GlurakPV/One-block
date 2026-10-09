package de.oneblock;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OneBlockMod implements ModInitializer {
    public static final String MOD_ID = "oneblock";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                OneBlockCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(Game.I::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Game.I.save());
        ServerTickEvents.END_SERVER_TICK.register(server -> Game.I.tick());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Game.I.onJoin(handler.player));

        // Ersetzt das Advancement "killed_dragon" des Datapacks.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof EnderDragon && source.getEntity() instanceof ServerPlayer killer) {
                Game.I.dragonKilled(killer);
            }
        });
    }
}
