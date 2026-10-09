import re, json, os, shutil, glob
DP = "/home/claude/dp/data/oneblock"
FN = DP + "/function"
OUT = "src/main/resources/oneblock_data"
os.makedirs(OUT, exist_ok=True)
rd = lambda p: open(p, encoding="utf-8").read()

# ---- layers
layers = []
for n in range(1, 33):
    t = rd(f"{FN}/layer/l{n:02d}.mcfunction")
    name = re.search(r"# Ebene \d+: (.*)", t).group(1).strip()
    bound = int(re.search(r"random value 1\.\.(\d+)", t).group(1))
    slots = {}
    for m in re.finditer(r"matches (\d+)(?:\.\.(\d+))? run setblock ~ ~ ~ (\S+)", t):
        a = int(m.group(1)); b = int(m.group(2) or a)
        for i in range(a, b + 1):
            slots[i] = m.group(3)
    assert sorted(slots) == list(range(1, bound + 1)), (n, bound, sorted(slots))
    layers.append({"name": name, "blocks": [slots[i] for i in range(1, bound + 1)]})
lens = {}
for m in re.finditer(r"matches (\d+) run scoreboard players set #len ob_len (\d+)", rd(FN + "/layer_len.mcfunction")):
    lens[int(m.group(1))] = int(m.group(2))
for i, l in enumerate(layers, 1):
    l["length"] = lens[i]

# ---- waves
waves = []
for n in range(1, 33):
    t = rd(f"{FN}/wave/w{n:02d}.mcfunction")
    mobs = re.findall(r"^summon (\S+) ", t, re.M)
    effects = [{"id": m.group(1), "amp": int(m.group(3))} for m in
               re.finditer(r"^effect give @e\[tag=ob_wave_new\] (\S+) (\d+) (\d+) true", t, re.M)]
    dur = int(re.search(r"^effect give @e\[tag=ob_wave_new\] \S+ (\d+)", t, re.M).group(1))
    c = re.search(r"(\d+) Gegner \(Staerke (\d+)/8\)", t)
    assert int(c.group(1)) == len(mobs), n
    waves.append({"mobs": mobs, "effects": effects, "duration": dur, "strength": int(c.group(2))})

# ---- mob classification
mobinfo = {}
for p in glob.glob(FN + "/mob/*.mcfunction"):
    name = os.path.basename(p)[:-11]
    t = rd(p)
    mobinfo[name] = {"persistent": "PersistenceRequired" in t,
                     "hand": (re.search(r'HandItems:\[\{id:"([^"]+)"', t) or [None, None])[1]}
mobinfo = {"minecraft:" + k: v for k, v in mobinfo.items()}

# ---- spawn rules
t = rd(FN + "/mobs.mcfunction")
rules = []
for m in re.finditer(r"matches (\d+)(?:\.\.(\d+))? if score #cur ob_rand matches (\d+)(?:\.\.(\d+))? run function oneblock:mob/(\w+)", t):
    pa = int(m.group(1)); pb = int(m.group(2) or pa)
    ra = int(m.group(3)); rb = int(m.group(4) or ra)
    rules.append({"phase": [pa, pb], "roll": [ra, rb], "mob": "minecraft:" + m.group(5)})
# the second roll starts at the line with 'blaze'
blaze_idx = next(i for i, r in enumerate(rules) if r["mob"].endswith("blaze"))
animals, nether = rules[:blaze_idx], rules[blaze_idx:]
night = []
for m in re.finditer(r"matches (\d+)(?:\.\.(\d+))? run function oneblock:mob/(\w+)", rd(FN + "/night.mcfunction")):
    a = int(m.group(1)); b = int(m.group(2) or a)
    night.append({"roll": [a, b], "mob": "minecraft:" + m.group(3)})

# ---- chest mapping
chest = []
for m in re.finditer(r"matches (\d+)(?:\.\.(\d+))? run loot insert ~ ~ ~ loot oneblock:chest/(\w+)", rd(FN + "/chest.mcfunction")):
    a = int(m.group(1)); b = int(m.group(2) or a)
    chest.append({"phase": [a, b], "table": "oneblock:chest/" + m.group(3)})

# ---- biome wood
def biome(fname):
    out = []
    for m in re.finditer(r"if biome ~ ~ ~ (\S+) run setblock ~ ~ ~ (\S+)", rd(FN + "/" + fname)):
        out.append({"biome": m.group(1), "block": m.group(2)})
    return out
logs, leaves = biome("biome_wood_log.mcfunction"), biome("biome_wood_leaf.mcfunction")
tags = sorted({e["biome"] for e in logs + leaves if e["biome"].startswith("#")})

# ---- islands
islands = {}
mf = rd(FN + "/inseln_menu_fill.mcfunction")
for m in re.finditer(r"with minecraft:(\w+)\[custom_name='\{\"text\": \"Insel (\d+) \((\w+)\)\", \"italic\": false, \"color\": \"(#\w+)\"", mf):
    islands[int(m.group(2))] = {"dye": m.group(1), "name": m.group(3), "color": m.group(4)}
assert sorted(islands) == list(range(1, 13)), sorted(islands)
for n in range(1, 13):
    h = rd(f"{FN}/hud_{n}.mcfunction")
    cols = re.findall(r'color:"(#\w+)"', h)
    islands[n]["light"], islands[n]["dark"] = cols[0], cols[10 if n < 10 else 11]
# dark = end of first line (title). Use last title colour:
for n in range(1, 13):
    h = rd(f"{FN}/hud_{n}.mcfunction")
    first_line = h.split('{text:"\\n"}')[0]
    cols = re.findall(r'color:"(#\w+)"', first_line)
    islands[n]["light"], islands[n]["dark"] = cols[0], cols[-1]
    islands[n]["name_ascii"] = islands[n]["name"]

data = {"layers": layers, "waves": waves, "mobs": mobinfo, "animalRules": animals,
        "netherRules": nether, "nightRules": night, "chest": chest,
        "biomeLogs": logs, "biomeLeaves": leaves,
        "islands": [islands[i] for i in range(1, 13)]}
json.dump(data, open(OUT + "/data.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)

# ---- loot tables
dst = "src/main/resources/data/oneblock/loot_table/chest"
os.makedirs(dst, exist_ok=True)
for p in glob.glob(DP + "/loot_table/chest/*.json"):
    shutil.copy(p, dst)

print("layers", len(layers), "waves", len(waves), "animal rules", len(animals), "nether", len(nether), "night", len(night))
print("chest", len(chest), "logs", len(logs), "leaves", len(leaves), "biome tags", tags)
print("effects used", sorted({e["id"] for w in waves for e in w["effects"]}))
print("wave mobs", sorted({m for w in waves for m in w["mobs"]}))
print("islands", [(i["name"], i["dye"], i["color"], i["light"], i["dark"]) for i in data["islands"]][:3])
print("equip", {k: v["hand"] for k, v in mobinfo.items() if v["hand"]})
