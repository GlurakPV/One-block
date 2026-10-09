package de.oneblock;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Spieldaten (Ebenen, Wellen, Spawn-Regeln ...), erzeugt aus dem Datapack und als JSON in der Mod gebuendelt. */
public final class Data {
    public List<Layer> layers;
    public List<Wave> waves;
    public Map<String, MobInfo> mobs;
    public List<Rule> animalRules;
    public List<Rule> netherRules;
    public List<Rule> nightRules;
    public List<ChestRule> chest;
    public List<BiomeBlock> biomeLogs;
    public List<BiomeBlock> biomeLeaves;
    public List<IslandInfo> islands;

    public static final class Layer { public String name; public int length; public List<String> blocks; }
    public static final class Wave { public List<String> mobs; public List<Effect> effects; public int duration; public int strength; }
    public static final class Effect { public String id; public int amp; }
    public static final class MobInfo { public boolean persistent; public String hand; }
    /** phase kann bei Nacht-Regeln fehlen (null). */
    public static final class Rule { public int[] phase; public int[] roll; public String mob; }
    public static final class ChestRule { public int[] phase; public String table; }
    public static final class BiomeBlock { public String biome; public String block; }
    public static final class IslandInfo { public String dye; public String name; public String color; public String light; public String dark; }

    public static Data load() throws IOException {
        try (var in = Data.class.getResourceAsStream("/oneblock_data/data.json")) {
            if (in == null) throw new IOException("oneblock_data/data.json fehlt in der Mod");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new Gson().fromJson(r, Data.class);
            }
        }
    }
}
