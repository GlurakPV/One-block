package de.oneblock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Gesamte Spiellogik (Port des Datapacks "oneblock"). */
public final class Game {
    public static final Game I = new Game();

    // Spielstatus wie im Datapack: 0 aus, 1 startet, 2 laeuft, 3 fertig (Drache besiegt)
    static final int OFF = 0, STARTING = 1, RUNNING = 2, DONE = 3;
    // Inselstatus
    static final int NONE = 0, READY = 1, GENERATING = 2;

    static final int MAX_ISLANDS = 12;
    static final int LAYERS = 32;
    static final String[] TIMER_COLORS = {"#9cff7a", "#87e86d", "#72d060", "#5db953", "#49a245", "#348b38", "#1f732b", "#0a5c1e"};

    /** Eine Insel. */
    static final class Island {
        int status = NONE;
        int phase = 1, mined, len = 200, left = 200;
        boolean chest, bucketGiven;
        int hasteCount, hasteNeed = 150;
        int genWait;
    }

    /** Was in oneblock_mod.json gespeichert wird. */
    static final class Save {
        int state, count;
        long ticks;
        boolean normal;
        List<IslandSave> islands = new ArrayList<>();
        Map<String, Integer> owners = new HashMap<>();
    }

    static final class IslandSave {
        int status, phase, mined, len, left, hasteCount, hasteNeed;
        boolean chest, bucketGiven;
    }

    MinecraftServer server;
    Data data;
    int state, count;
    long ticks;
    boolean normal;
    final Island[] isl = new Island[MAX_ISLANDS];
    final Map<UUID, Integer> owner = new HashMap<>();
    final Map<UUID, Integer> joinTeleport = new HashMap<>();

    // fluechtig
    long tickCounter;
    int placeTimer = -1, placeWait;
    ClearJob clear;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private Game() {
        for (int i = 0; i < MAX_ISLANDS; i++) isl[i] = new Island();
    }

    // ------------------------------------------------------------------ Hilfen

    static int X(int i) { return (i % 4) * 48; }
    static int Z(int i) { return (i / 4) * 48; }
    static int rnd(int min, int max) { return ThreadLocalRandom.current().nextInt(min, max + 1); }

    ServerLevel level() { return server.overworld(); }

    void run(String cmd) { Cmd.run(server, cmd); }

    /** Fuehrt einen Befehl im Overworld an der Position des OneBlocks (i = 0-basiert) aus. */
    void at(int i, String cmd) {
        run("execute in minecraft:overworld positioned " + X(i) + " 64 " + Z(i) + " run " + cmd);
    }

    void tellAll(String snbt) { run("tellraw @a " + snbt); }
    void tell(UUID id, String snbt) { run("tellraw " + id + " " + snbt); }

    UUID ownerOf(int n) {
        for (var e : owner.entrySet()) if (e.getValue() == n) return e.getKey();
        return null;
    }

    ServerPlayer player(UUID id) { return server.getPlayerList().getPlayer(id); }

    ServerPlayer nearest(double x, double y, double z, double radius) {
        ServerPlayer best = null;
        double bestD = radius * radius;
        for (ServerPlayer p : level().players()) {
            if (p.isSpectator()) continue;
            double d = p.distanceToSqr(x, y, z);
            if (d <= bestD) { bestD = d; best = p; }
        }
        return best;
    }

    static String islandLabel(Data.IslandInfo info, int n) { return "#" + n + " (" + info.name + ")"; }

    static String gradientText(String text, String light, String dark, boolean bold) {
        int a = Integer.parseInt(light.substring(1), 16), b = Integer.parseInt(dark.substring(1), 16);
        StringBuilder sb = new StringBuilder();
        int n = text.length();
        for (int k = 0; k < n; k++) {
            double t = n <= 1 ? 0 : k / (double) (n - 1);
            int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
            int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
            int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
            if (k > 0) sb.append(',');
            sb.append("{text:\"").append(text.charAt(k)).append("\",color:\"")
                    .append(String.format("#%02x%02x%02x", r, g, bl)).append('"')
                    .append(bold ? ",bold:true" : "").append('}');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Start / Laden / Speichern

    void init(MinecraftServer s) {
        server = s;
        try {
            data = Data.load();
        } catch (IOException e) {
            throw new IllegalStateException("OneBlock-Daten konnten nicht geladen werden", e);
        }
        load();
        // Anzeigen (Scoreboard) wie im Datapack
        run("scoreboard objectives add ob_blocks dummy \"Blöcke\"");
        run("scoreboard objectives add ob_deaths deathCount \"Tode\"");
        run("scoreboard objectives setdisplay list ob_deaths");
        run("scoreboard objectives setdisplay below_name ob_blocks");
    }

    Path saveFile() { return server.getWorldPath(LevelResource.ROOT).resolve("oneblock_mod.json"); }

    void save() {
        if (server == null) return;
        Save s = new Save();
        s.state = state; s.count = count; s.ticks = ticks; s.normal = normal;
        for (Island is : isl) {
            IslandSave o = new IslandSave();
            o.status = is.status; o.phase = is.phase; o.mined = is.mined; o.len = is.len; o.left = is.left;
            o.hasteCount = is.hasteCount; o.hasteNeed = is.hasteNeed; o.chest = is.chest; o.bucketGiven = is.bucketGiven;
            s.islands.add(o);
        }
        owner.forEach((k, v) -> s.owners.put(k.toString(), v));
        try {
            Files.writeString(saveFile(), gson.toJson(s), StandardCharsets.UTF_8);
        } catch (IOException e) {
            OneBlockMod.LOG.warn("Speichern fehlgeschlagen", e);
        }
    }

    void load() {
        Path f = saveFile();
        if (!Files.exists(f)) return;
        try {
            Save s = gson.fromJson(Files.readString(f, StandardCharsets.UTF_8), Save.class);
            if (s == null) return;
            state = s.state; count = s.count; ticks = s.ticks; normal = s.normal;
            for (int i = 0; i < MAX_ISLANDS && i < s.islands.size(); i++) {
                IslandSave o = s.islands.get(i);
                Island is = isl[i];
                is.status = o.status; is.phase = o.phase; is.mined = o.mined; is.len = o.len; is.left = o.left;
                is.hasteCount = o.hasteCount; is.hasteNeed = o.hasteNeed; is.chest = o.chest; is.bucketGiven = o.bucketGiven;
            }
            owner.clear();
            s.owners.forEach((k, v) -> owner.put(UUID.fromString(k), v));
            // Ein beim Start unterbrochenes Spiel kann nicht fortgesetzt werden.
            if (state == STARTING) {
                state = OFF;
                for (Island is : isl) is.status = NONE;
            }
        } catch (Exception e) {
            OneBlockMod.LOG.warn("Laden fehlgeschlagen, starte leer", e);
        }
    }

    // ------------------------------------------------------------------ Tick

    void tick() {
        if (server == null) return;
        tickCounter++;

        if (tickCounter % 20 == 0) run("scoreboard players add @a ob_blocks 0");
        if (clear != null) clear.step();
        if (placeTimer >= 0) tickPlacing();

        if (state == RUNNING || state == DONE) {
            for (int i = 0; i < MAX_ISLANDS; i++) {
                Island is = isl[i];
                if (is.status == READY) {
                    ServerLevel lv = level();
                    if (lv.getBlockState(new BlockPos(X(i), 64, Z(i))).isAir()) mine(i);
                    collectItems(i);
                } else if (is.status == GENERATING) {
                    tickGenerating(i);
                }
            }
        }
        if (state == RUNNING) ticks++;
        if ((state == RUNNING || state == DONE) && tickCounter % 5 == 0) {
            run("title @a actionbar " + timerSnbt());
        }

        if (!joinTeleport.isEmpty()) {
            var it = joinTeleport.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.setValue(e.getValue() - 1) <= 1) {
                    it.remove();
                    Integer n = owner.get(e.getKey());
                    if (n != null && isl[n - 1].status == READY && player(e.getKey()) != null) {
                        tpTo(e.getKey(), n - 1);
                        tell(e.getKey(), "{text:\"Willkommen zurueck auf deiner Insel!\",color:\"gray\"}");
                    }
                }
            }
        }
        if (tickCounter % 1200 == 0) save();
    }

    void tpTo(UUID id, int i) {
        run("execute in minecraft:overworld run tp " + id + " " + (X(i) + 0.5) + " 65 " + (Z(i) + 0.5));
    }

    void collectItems(int i) {
        int x = X(i), z = Z(i);
        AABB box = new AABB(x - 1, -64, z - 1, x + 2, 101, z + 2);
        for (ItemEntity it : level().getEntitiesOfClass(ItemEntity.class, box)) {
            it.setPos(x + 0.5, 65.0, z + 0.5);
            it.setDeltaMovement(Vec3.ZERO);
        }
    }

    // ------------------------------------------------------------------ Mine / Platzieren

    void mine(int i) {
        Island is = isl[i];
        int x = X(i), z = Z(i);
        is.mined++;
        is.left--;

        ServerPlayer near = nearest(x, 64, z, 8);
        if (near != null) run("scoreboard players add " + near.getStringUUID() + " ob_blocks 1");

        at(i, "particle minecraft:crit 0.5 65.2 0.5 0.35 0.35 0.35 0.05 10");
        at(i, "particle minecraft:cloud 0.5 65.0 0.5 0.3 0.15 0.3 0.02 6");
        at(i, "playsound minecraft:block.stone.break block @a[distance=..30] " + (x + 0.5) + " 65 " + (z + 0.5) + " 1 1");
        at(i, "playsound minecraft:entity.experience_orb.pickup player @a[distance=..30] " + (x + 0.5) + " 65 " + (z + 0.5) + " 0.6 1.4");

        int roll = rnd(1, 100);
        if (near != null) {
            if (roll <= 10) gamble(near, true);
            else if (roll <= 20) gamble(near, false);
        }

        if (is.chest) {
            for (ServerPlayer p : level().players()) {
                if (p.isSpectator()) continue;
                double dx = p.getX() - (x + 0.5), dy = p.getY() - 65, dz = p.getZ() - (z + 0.5);
                if (dx * dx + dy * dy + dz * dz <= 2.5 * 2.5) {
                    run("execute in minecraft:overworld as " + p.getStringUUID() + " at @s run tp @s ~ ~0.5 ~");
                }
            }
        }

        boolean bucket = false;
        if (is.left <= 0) bucket = levelUp(i);

        is.chest = place(i, bucket);

        is.hasteCount++;
        if (is.hasteCount >= is.hasteNeed) haste(i);
        hud(i);
    }

    void gamble(ServerPlayer p, boolean good) {
        String id = p.getStringUUID();
        String[][] goodList = {
                {"speed 20", "Tempo"}, {"jump_boost 20", "Sprungkraft"}, {"regeneration 10", "Regeneration"},
                {"absorption 30", "Extra-Herzen"}, {"luck 60", "Glueck"}, {"fire_resistance 20", "Feuerresistenz"},
                {"night_vision 30", "Nachtsicht"}, {"strength 15", "Staerke"}};
        String[][] badList = {
                {"slowness 10", "Langsamkeit"}, {"weakness 15", "Schwaeche"}, {"mining_fatigue 10", "Bergbaumuedigkeit"},
                {"nausea 5", "Uebelkeit"}, {"blindness 5", "Blindheit"}, {"hunger 15", "Hunger"},
                {"poison 4", "Vergiftung"}, {"unluck 30", "Pech"}};
        String[] pick = (good ? goodList : badList)[rnd(0, 7)];
        run("effect give " + id + " minecraft:" + pick[0] + " 0");
        String col = good ? "green" : "red";
        tell(p.getUUID(), "[{text:\"" + (good ? "Glueck gehabt! " : "Pech gehabt! ") + "\",color:\"" + col + "\",bold:true},{text:\"" + pick[1] + "\",color:\"" + col + "\"}]");
        if (good) {
            run("execute at " + id + " run particle minecraft:happy_villager ~ ~1 ~ 0.3 0.3 0.3 0.1 12");
            run("execute at " + id + " run playsound minecraft:entity.player.levelup player " + id + " ~ ~ ~ 0.6 1.6");
        } else {
            run("execute at " + id + " run particle minecraft:angry_villager ~ ~1 ~ 0.3 0.3 0.3 0 8");
            run("execute at " + id + " run playsound minecraft:entity.villager.no player " + id + " ~ ~ ~ 0.6 0.8");
        }
    }

    void haste(int i) {
        Island is = isl[i];
        ServerPlayer near = nearest(X(i), 64, Z(i), 8);
        if (near != null) {
            String id = near.getStringUUID();
            run("effect give " + id + " minecraft:haste 180 0");
            tell(near.getUUID(), "{text:\"Eile fuer 3 Minuten!\",color:\"gold\"}");
            run("execute at " + id + " run particle minecraft:happy_villager ~ ~1 ~ 0.5 1 0.5 0.1 15");
            run("execute at " + id + " run playsound minecraft:entity.beacon.power_select player " + id + " ~ ~ ~ 1 1.3");
        }
        is.hasteCount = 0;
        is.hasteNeed = rnd(100, 200);
    }

    /** @return true, wenn als Naechstes die Wasser-/Lavakiste kommt. */
    boolean levelUp(int i) {
        Island is = isl[i];
        Data.IslandInfo info = data.islands.get(i);
        is.phase = is.phase + 1;
        if (is.phase > LAYERS) is.phase = 1;
        is.len = data.layers.get(is.phase - 1).length;
        is.left = is.len;
        is.mined = 0;
        tellAll("[{text:\"OneBlock " + islandLabel(info, i + 1) + ": \",color:\"" + info.color + "\"},"
                + "{text:\"Neue Ebene \",color:\"yellow\"},{text:\"" + is.phase + "\",color:\"yellow\"},{text:\"/32!\",color:\"yellow\"}]");
        boolean bucket = false;
        if (is.phase == 2 && !is.bucketGiven) { is.bucketGiven = true; bucket = true; }
        at(i, "particle minecraft:totem_of_undying ~ ~1.2 ~ 0.6 0.6 0.6 0.4 50 force");
        at(i, "particle minecraft:firework ~ ~1.2 ~ 0.6 0.6 0.6 0.3 25 force");
        at(i, "playsound minecraft:ui.toast.challenge_complete master @a[distance=..80] ~ ~ ~ 1 1");
        at(i, "playsound minecraft:entity.player.levelup master @a[distance=..80] ~ ~ ~ 1 1.2");
        wave(i);
        return bucket;
    }

    void wave(int i) {
        Island is = isl[i];
        Data.Wave w = data.waves.get(is.phase - 1);
        int x = X(i), z = Z(i);
        for (String mob : w.mobs) spawn(mob, x, z, true);
        for (Data.Effect e : w.effects) {
            at(i, "effect give @e[tag=ob_wave_new,distance=..4] " + e.id + " " + w.duration + " " + e.amp + " true");
        }
        at(i, "particle minecraft:smoke ~ ~1 ~ 1 1 1 0.02 20");
        at(i, "playsound minecraft:block.bell.use block @a[distance=..64] ~ ~ ~ 1 0.7");
        at(i, "tag @e[tag=ob_wave_new,distance=..4] remove ob_wave_new");
        at(i, "tellraw @a[distance=..64] [{text:\"Monsterwelle! \",color:\"red\",bold:true},{text:\"" + w.mobs.size() + " Gegner (Staerke " + w.strength + "/8)\",color:\"gray\"}]");
    }

    /** Spawnt ein Monster/Tier ueber dem OneBlock (mit normaler Ausruestung, z. B. Skelett mit Bogen). */
    void spawn(String id, int x, int z, boolean waveMob) {
        var type = EntityType.byString(id);
        if (type.isEmpty()) {
            OneBlockMod.LOG.warn("Unbekannte Entity: {}", id);
            return;
        }
        Entity e = type.get().spawn(level(), new BlockPos(x, 65, z), EntitySpawnReason.COMMAND);
        if (e == null) return;
        Data.MobInfo mi = data.mobs.get(id);
        if (e instanceof Mob m && mi != null && mi.persistent) m.setPersistenceRequired();
        if (waveMob) e.addTag("ob_wave_new");
    }

    /** Platziert den naechsten Block. @return true, wenn eine Truhe entstanden ist. */
    boolean place(int i, boolean bucket) {
        int x = X(i), z = Z(i);
        boolean chestNow;
        if (bucket) {
            at(i, "setblock ~ ~ ~ minecraft:chest");
            at(i, "item replace block ~ ~ ~ container.0 with minecraft:water_bucket");
            at(i, "item replace block ~ ~ ~ container.1 with minecraft:lava_bucket");
            at(i, "particle minecraft:enchant ~0.5 ~0.5 ~0.5 0.3 0.3 0.3 0.4 30");
            at(i, "playsound minecraft:block.amethyst_block.chime record @a[distance=..40] ~ ~ ~ 1 0.8");
            at(i, "tellraw @a[distance=..40] {text:\"Eine besondere Kiste ist erschienen: Wasser- und Lavaeimer!\",color:\"aqua\"}");
            chestNow = true;
        } else {
            chestNow = placeNormal(i);
        }
        if (level().getBlockState(new BlockPos(x, 64, z)).isAir()) at(i, "setblock ~ ~ ~ minecraft:stone");
        mobs(i);
        return chestNow;
    }

    boolean placeNormal(int i) {
        Island is = isl[i];
        int x = X(i), z = Z(i);
        if (rnd(1, 100) <= 4) {
            at(i, "setblock ~ ~ ~ minecraft:chest");
            at(i, "particle minecraft:enchant ~0.5 ~0.5 ~0.5 0.3 0.3 0.3 0.4 20");
            at(i, "playsound minecraft:block.amethyst_block.chime record @a[distance=..30] ~ ~ ~ 1 1");
            for (Data.ChestRule r : data.chest) {
                if (is.phase >= r.phase[0] && is.phase <= r.phase[1]) {
                    at(i, "loot insert ~ ~ ~ loot " + r.table);
                    break;
                }
            }
            return true;
        }
        List<String> blocks = data.layers.get(is.phase - 1).blocks;
        String b = blocks.get(rnd(0, blocks.size() - 1));
        at(i, "setblock ~ ~ ~ " + b);
        biomeFix(i, x, z);
        return false;
    }

    void biomeFix(int i, int x, int z) {
        BlockPos pos = new BlockPos(x, 64, z);
        BlockState st = level().getBlockState(pos);
        List<Data.BiomeBlock> table = st.is(BlockTags.LOGS) ? data.biomeLogs : st.is(BlockTags.LEAVES) ? data.biomeLeaves : null;
        if (table == null) return;
        Holder<Biome> biome = level().getBiome(pos);
        for (Data.BiomeBlock e : table) {
            if (biomeMatches(biome, e.biome)) {
                at(i, "setblock ~ ~ ~ " + e.block);
                return;
            }
        }
    }

    static boolean biomeMatches(Holder<Biome> biome, String cond) {
        if (cond.startsWith("#")) {
            return switch (cond) {
                case "#minecraft:is_taiga" -> biome.is(BiomeTags.IS_TAIGA);
                case "#minecraft:is_jungle" -> biome.is(BiomeTags.IS_JUNGLE);
                case "#minecraft:is_savanna" -> biome.is(BiomeTags.IS_SAVANNA);
                case "#minecraft:is_badlands" -> biome.is(BiomeTags.IS_BADLANDS);
                case "#minecraft:is_ocean" -> biome.is(BiomeTags.IS_OCEAN);
                case "#minecraft:is_river" -> biome.is(BiomeTags.IS_RIVER);
                case "#minecraft:is_beach" -> biome.is(BiomeTags.IS_BEACH);
                default -> false;
            };
        }
        return biome.getRegisteredName().equals(cond);
    }

    static boolean inRange(int[] r, int v) { return r != null && v >= r[0] && v <= r[1]; }

    void mobs(int i) {
        int phase = isl[i].phase;
        int x = X(i), z = Z(i);
        int roll = rnd(1, 100);
        for (Data.Rule r : data.animalRules) {
            if (inRange(r.phase, phase) && inRange(r.roll, roll)) { spawn(r.mob, x, z, false); break; }
        }
        roll = rnd(1, 100);
        for (Data.Rule r : data.netherRules) {
            if (inRange(r.phase, phase) && inRange(r.roll, roll)) { spawn(r.mob, x, z, false); break; }
        }
        if (phase >= 1 && phase <= 28) {
            int daytime = Cmd.query(server, "execute in minecraft:overworld run time query daytime");
            if (daytime >= 13000) {
                roll = rnd(1, 100);
                for (Data.Rule r : data.nightRules) {
                    if (inRange(r.roll, roll)) { spawn(r.mob, x, z, false); break; }
                }
            }
        }
    }

    // ------------------------------------------------------------------ HUD / Timer

    void hud(int i) {
        Island is = isl[i];
        Data.IslandInfo info = data.islands.get(i);
        String[] lines = {
                "ONEBLOCK #" + (i + 1),
                "Ebene: " + is.phase + "/" + LAYERS,
                "Abgebaut: " + is.mined + "/" + is.len,
                "Noch: " + Math.max(is.left, 0)};
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < lines.length; k++) {
            if (k > 0) sb.append(",{text:\"\\n\"},");
            sb.append(gradientText(lines[k], info.light, info.dark, k == 0));
        }
        sb.append(']');
        run("execute in minecraft:overworld run data modify entity @e[type=minecraft:text_display,tag=ob_hud" + (i + 1)
                + ",limit=1] text set value " + sb);
    }

    String timerString() {
        long h = (ticks / 72000) % 100, m = (ticks / 1200) % 60, s = (ticks / 20) % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    String timerSnbt() {
        String t = timerString();
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < t.length(); k++) {
            if (k > 0) sb.append(',');
            sb.append("{text:\"").append(t.charAt(k)).append("\",color:\"").append(TIMER_COLORS[k]).append("\",bold:true}");
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ Drache

    void dragonKilled(ServerPlayer killer) {
        if (state != RUNNING) return;
        state = DONE;
        save();
        String id = killer.getStringUUID();
        String time = timerSnbt();
        run("title @a times 10 100 30");
        run("execute at " + id + " run particle minecraft:totem_of_undying ~ ~1 ~ 1 1 1 0.6 150 force");
        run("playsound minecraft:ui.toast.challenge_complete master @a ~ ~ ~ 1 1");
        run("title @a title {text:\"Enderdrache besiegt!\",color:\"green\",bold:true}");
        run("title @a subtitle " + time);
        tellAll("[{text:\"Der Enderdrache wurde besiegt! Zeit: \",color:\"gold\"}," + time.substring(1, time.length() - 1) + "]");
    }

    // ------------------------------------------------------------------ Start / Reset

    /** @return Fehlermeldung oder null bei Erfolg. */
    String setIslands(int n) {
        if (state == STARTING || state == RUNNING)
            return "Waehrend das Spiel laeuft kann die Inselanzahl nicht geaendert werden. (Neustart: /oneblock reset)";
        count = n;
        save();
        return null;
    }

    /** @return Fehlermeldung oder null bei Erfolg. */
    String start(boolean normalWorld) {
        if (state == STARTING || state == RUNNING) return "OneBlock laeuft schon! Neustart mit /oneblock reset";
        if (count < 1 || count > MAX_ISLANDS) return "Erst die Inselanzahl waehlen: /oneblock islands 5  (1 bis 12)";
        prepare(normalWorld);
        if (normalWorld) clear = new ClearJob();
        else finishStart();
        return null;
    }

    void prepare(boolean normalWorld) {
        state = STARTING;
        normal = normalWorld;
        placeTimer = -1;
        ticks = 0;
        joinTeleport.clear();
        for (Island is : isl) is.status = NONE;
        run("tag @a remove ob_prev_c");
        run("tag @a remove ob_prev_a");
        run("tag @a[gamemode=creative] add ob_prev_c");
        run("tag @a[gamemode=adventure] add ob_prev_a");
        run("scoreboard players set @a ob_blocks 0");

        owner.clear();
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        Collections.shuffle(players);
        for (int i = 0; i < count; i++) {
            isl[i].status = GENERATING;
            if (i < players.size()) owner.put(players.get(i).getUUID(), i + 1);
        }
        run("gamemode spectator @a");
        tellAll("{text:\"OneBlock wird vorbereitet ...\",color:\"gray\"}");
        save();
    }

    void finishStart() {
        if (state != STARTING) return;
        for (int i = 0; i < count; i++) run("execute in minecraft:overworld run forceload add " + X(i) + " " + Z(i));
        run("title @a actionbar {text:\"Inseln werden gesetzt ...\",color:\"gray\"}");
        placeWait = 0;
        placeTimer = 20;
    }

    void tickPlacing() {
        if (state != STARTING) { placeTimer = -1; return; }
        if (--placeTimer > 0) return;
        placeTimer = 10;
        placeWait++;
        boolean ready = placeWait >= 120;
        if (!ready) {
            ready = true;
            for (int i = 0; i < count; i++) {
                if (!level().isLoaded(new BlockPos(X(i), 64, Z(i)))) { ready = false; break; }
            }
        }
        if (!ready) return;
        placeTimer = -1;

        for (int i = 0; i < count; i++) setupIsland(i);
        // Spieler ohne Insel auf Insel 1 setzen
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!owner.containsKey(p.getUUID())) {
                run("execute in minecraft:overworld run tp " + p.getStringUUID() + " 0.5 65 0.5");
            }
        }
        restoreModes();
        run("execute in minecraft:overworld run setworldspawn 0 65 0");
        ticks = 0;
        state = RUNNING;
        run("title @a times 10 40 20");
        run("title @a title {text:\"LOS GEHT'S!\",color:\"green\",bold:true}");
        run("title @a subtitle {text:\"Besiege den Enderdrachen\",color:\"gray\"}");
        tellAll("{text:\"OneBlock gestartet! Der Timer laeuft.\",color:\"gold\"}");
        save();
    }

    void restoreModes() {
        run("gamemode survival @a");
        run("gamemode creative @a[tag=ob_prev_c]");
        run("gamemode adventure @a[tag=ob_prev_a]");
    }

    void setupIsland(int i) {
        Island is = isl[i];
        Data.IslandInfo info = data.islands.get(i);
        int x = X(i), z = Z(i);
        is.status = READY;
        is.phase = 1;
        is.mined = 0;
        is.len = data.layers.get(0).length;
        is.left = is.len;
        is.chest = false;
        is.bucketGiven = false;
        is.hasteCount = 0;
        is.hasteNeed = rnd(100, 200);

        at(i, "setblock ~ ~ ~ minecraft:grass_block");
        run("execute in minecraft:overworld run kill @e[type=minecraft:text_display,tag=ob_hud" + (i + 1) + "]");
        run("execute in minecraft:overworld run summon minecraft:text_display " + (x + 0.5) + " 68 " + (z + 0.5)
                + " {Tags:[\"ob_hud\",\"ob_hud" + (i + 1) + "\"],billboard:\"center\",shadow:true,see_through:false,line_width:300,text:\"ONEBLOCK #" + (i + 1) + "\"}");
        hud(i);
        at(i, "particle minecraft:totem_of_undying 0.5 65.5 0.5 1 1 1 0.4 60 force");
        at(i, "playsound minecraft:entity.player.levelup master @a[distance=..80] " + (x + 0.5) + " 65 " + (z + 0.5) + " 1 1");

        UUID o = ownerOf(i + 1);
        if (o != null && player(o) != null) {
            tpTo(o, i);
            run("execute in minecraft:overworld run spawnpoint " + o + " " + x + " 65 " + z);
            tell(o, "[{text:\"Deine Insel: \",color:\"gray\"},{text:\"" + islandLabel(info, i + 1) + "\",color:\"" + info.color + "\",bold:true}]");
        }
    }

    void reset() {
        if (state == STARTING) restoreModes();
        state = OFF;
        clear = null;
        placeTimer = -1;
        joinTeleport.clear();
        run("execute in minecraft:overworld run kill @e[type=minecraft:text_display,tag=ob_hud]");
        run("execute in minecraft:overworld run forceload remove all");
        for (Island is : isl) is.status = NONE;
        owner.clear();
        run("title @a actionbar {text:\"OneBlock beendet.\",color:\"gray\"}");
        tellAll("{text:\"OneBlock zurueckgesetzt. Neu starten mit /oneblock start\",color:\"yellow\"}");
        save();
    }

    // ------------------------------------------------------------------ Inseln waehrend des Spiels

    void tickGenerating(int i) {
        Island is = isl[i];
        is.genWait++;
        if (is.genWait % 10 != 0) return;
        boolean ready = is.genWait >= 1200 || level().isLoaded(new BlockPos(X(i), 64, Z(i)));
        if (ready) {
            setupIsland(i);
            save();
        }
    }

    void generate(int i) {
        Island is = isl[i];
        if (is.status != NONE) return;
        is.status = GENERATING;
        is.genWait = 0;
        Data.IslandInfo info = data.islands.get(i);
        tellAll("{text:\"Insel " + (i + 1) + " (" + info.name + ") wird generiert ...\",color:\"gray\"}");
        run("execute in minecraft:overworld run forceload add " + X(i) + " " + Z(i));
    }

    /** Spieler uebernimmt Insel n (1-12). */
    void claim(ServerPlayer p, int n) {
        UUID id = p.getUUID();
        if (state != RUNNING && state != DONE) {
            tell(id, "{text:\"OneBlock laeuft gerade nicht.\",color:\"red\"}");
            return;
        }
        Integer cur = owner.get(id);
        if (cur != null && cur == n) {
            tell(id, "{text:\"Das ist schon deine Insel.\",color:\"yellow\"}");
            return;
        }
        UUID o = ownerOf(n);
        if (o != null) {
            tell(id, "{text:\"Insel " + n + " ist schon vergeben.\",color:\"red\"}");
            return;
        }
        owner.put(id, n);
        Island is = isl[n - 1];
        if (is.status == READY) {
            tpTo(id, n - 1);
            run("execute in minecraft:overworld run spawnpoint " + id + " " + X(n - 1) + " 65 " + Z(n - 1));
            run("execute at " + id + " run particle minecraft:happy_villager ~ ~1 ~ 0.3 0.3 0.3 0 10");
            run("execute at " + id + " run playsound minecraft:entity.enderman.teleport player " + id + " ~ ~ ~ 1 1");
        } else {
            generate(n - 1);
        }
        save();
    }

    void claimNew(ServerPlayer p) {
        UUID id = p.getUUID();
        tell(id, "{text:\"Suche eine freie Insel ...\",color:\"gray\"}");
        if (state != RUNNING && state != DONE) {
            tell(id, "{text:\"OneBlock laeuft gerade nicht.\",color:\"red\"}");
            return;
        }
        for (int i = 0; i < MAX_ISLANDS; i++) {
            if (isl[i].status == NONE && ownerOf(i + 1) == null) {
                owner.put(id, i + 1);
                generate(i);
                save();
                return;
            }
        }
        tell(id, "{text:\"Keine freie Insel mehr! (Max. 12)\",color:\"red\"}");
    }

    void list(ServerPlayer p) {
        UUID id = p.getUUID();
        tell(id, "{text:\"--- Inseln ---\",color:\"gold\"}");
        for (int i = 0; i < MAX_ISLANDS; i++) {
            Data.IslandInfo info = data.islands.get(i);
            String head = "{text:\"" + islandLabel(info, i + 1) + ": \",color:\"" + info.color + "\"}";
            String tail;
            if (isl[i].status == GENERATING) tail = "{text:\"wird generiert ...\",color:\"gray\"}";
            else if (isl[i].status == READY) {
                UUID o = ownerOf(i + 1);
                ServerPlayer op = o == null ? null : player(o);
                if (o == null) tail = "{text:\"herrenlos\",color:\"gray\"}";
                else if (op != null) tail = "{text:\"" + op.getName().getString() + "\"}";
                else tail = "{text:\"(offline)\",color:\"gray\"}";
            } else tail = "{text:\"noch nicht generiert\",color:\"dark_gray\"}";
            tell(id, "[" + head + "," + tail + "]");
        }
    }

    void onJoin(ServerPlayer p) {
        if ((state == RUNNING || state == DONE) && owner.containsKey(p.getUUID())) {
            joinTeleport.put(p.getUUID(), 10);
        } else if (state == OFF) {
            tell(p.getUUID(), "[{text:\"[OneBlock] geladen. 1) \",color:\"green\"},{text:\"/oneblock islands 5\",color:\"yellow\"},"
                    + "{text:\" (Anzahl 1-12) 2) \",color:\"green\"},{text:\"/oneblock start\",color:\"yellow\"},"
                    + "{text:\" (normale Welt) oder \",color:\"green\"},{text:\"/oneblock start void\",color:\"yellow\"},{text:\" (Void-Welt)\",color:\"green\"}]");
        }
    }

    String status() {
        return "Status=" + state + " (0=aus,1=startet,2=laeuft,3=fertig) | Inseln=" + count + " | Zeit=" + timerString();
    }

    // ------------------------------------------------------------------ Welt leeren (normale Welt)

    /** Leert den Bereich um die Inseln (wie die "tile"-Funktionen des Datapacks): 6x6 Kacheln a 128x128 Bloecke. */
    final class ClearJob {
        static final int X0 = -304, Z0 = -304, X1 = 447, Z1 = 399, TILE = 128;
        int tile = 0, phase = 0, waited = 0, idx = 0;
        int tx0, tz0, tx1, tz1;
        List<String> fills = new ArrayList<>();

        void step() {
            if (state != STARTING) { clear = null; return; }
            if (tile >= 36) {
                clear = null;
                finishStart();
                return;
            }
            if (phase == 0) {
                int col = tile % 6, row = tile / 6;
                tx0 = X0 + col * TILE;
                tz0 = Z0 + row * TILE;
                tx1 = Math.min(tx0 + TILE - 1, X1);
                tz1 = Math.min(tz0 + TILE - 1, Z1);
                run("execute in minecraft:overworld run forceload add " + tx0 + " " + tz0 + " " + tx1 + " " + tz1);
                run("title @a actionbar {text:\"Welt wird vorbereitet: " + (tile + 1) + "/36\",color:\"gray\"}");
                waited = 0;
                phase = 1;
            } else if (phase == 1) {
                if (allLoaded() || ++waited > 200) {
                    fills.clear();
                    for (int x = tx0; x <= tx1; x += 16) {
                        for (int z = tz0; z <= tz1; z += 16) {
                            int x2 = Math.min(x + 15, tx1), z2 = Math.min(z + 15, tz1);
                            for (int y = -64; y < 320; y += 64) {
                                fills.add("execute in minecraft:overworld run fill " + x + " " + y + " " + z + " " + x2 + " " + (y + 63) + " " + z2 + " minecraft:air");
                            }
                        }
                    }
                    idx = 0;
                    phase = 2;
                }
            } else {
                for (int k = 0; k < 16 && idx < fills.size(); k++) run(fills.get(idx++));
                if (idx >= fills.size()) {
                    run("execute in minecraft:overworld run forceload remove " + tx0 + " " + tz0 + " " + tx1 + " " + tz1);
                    tile++;
                    phase = 0;
                }
            }
        }

        boolean allLoaded() {
            for (int x = tx0; x <= tx1; x += 16)
                for (int z = tz0; z <= tz1; z += 16)
                    if (!level().isLoaded(new BlockPos(x, 64, z))) return false;
            return true;
        }
    }
}
