# OneBlock (Fabric-Mod, Minecraft 26.2)

Port des Datapacks `oneblock_26_2_v13` als Fabric-Mod. Bis zu 12 Inseln, 32 Ebenen, Monsterwellen,
Truhen-Loot, Wasser-/Lavakiste, Haste-Belohnung, Gamble-Effekte, Timer, Drachen-Ende.

## Jar ohne lokale Installation bauen (GitHub)

1. Auf github.com ein neues (privates) Repository anlegen und den Inhalt dieses Ordners hochladen
   (inklusive des versteckten Ordners `.github`).
2. Reiter **Actions** oeffnen, den Lauf "build" abwarten (ca. 3-5 Minuten).
3. Im fertigen Lauf unter **Artifacts** `oneblock-jar` herunterladen. Darin liegt die `.jar`.
4. Diese `.jar` bei Modrinth hochladen (Loader: Fabric, Version: 26.2, Abhaengigkeit: Fabric API).

## Bauen (lokal)

Voraussetzung: JDK 25.

1. Gradle-Wrapper aus der offiziellen Vorlage (https://fabricmc.net/develop) in diesen Ordner kopieren
   (`gradlew`, `gradlew.bat`, Ordner `gradle/`), oder lokal `gradle wrapper` ausfuehren.
2. `./gradlew build`
3. Fertige Mod: `build/libs/oneblock-1.0.0.jar` (nicht die `-sources.jar`) in den `mods`-Ordner,
   zusammen mit der Fabric API. Die Spieler brauchen die Mod nicht auf dem Client (Server-Mod).

Falls Gradle eine Version nicht findet: `gradle.properties` und die Loom-Version in `build.gradle`
mit den aktuellen Werten von https://fabricmc.net/develop fuer 26.2 abgleichen.

## Befehle (ersetzen die /trigger-Befehle)

| Befehl | Wirkung |
|---|---|
| `/oneblock islands <1-12>` | Inselanzahl waehlen |
| `/oneblock start` | Start in normaler Welt (Gelaende wird um die Inseln geleert, Biome bleiben) |
| `/oneblock start void` | Start in Void-Welt |
| `/oneblock island <1-12>` | Insel uebernehmen |
| `/oneblock new` | freie Insel generieren und uebernehmen |
| `/oneblock list` | Inselliste |
| `/oneblock menu` | Inselauswahl als Truhen-Menue |
| `/oneblock reset` (OP) | Spiel zuruecksetzen |
| `/oneblock status` (OP) | Status anzeigen |

## Was sich gegenueber dem Datapack geaendert hat

- Spiellogik ist Java statt tausender `.mcfunction`-Zeilen. Daten (Ebenen, Wellen, Spawn-Regeln,
  Biom-Holz) liegen in `src/main/resources/oneblock_data/data.json`, erzeugt von `convert.py`.
- Die Loot Tables (`data/oneblock/loot_table/chest/*.json`) sind unveraendert uebernommen.
- Inselauswahl ist ein echtes Menue statt der Marker-Truhe.
- Drachen-Ende per Event statt Advancement.
- Monster werden mit normaler Ausruestung gespawnt. Im Datapack waren `HandItems`/`ArmorItems`
  (altes NBT-Format) wirkungslos, dadurch hatten Skelette/Strays/Plünderer keine Waffen.
- Nachtraeglich uebernommene Inseln (ueber `count` hinaus) werden jetzt ebenfalls abgebaut
  (im Datapack prueft `check` nur die ersten `count` Inseln).
- Inselbesitzer werden pro UUID gespeichert (auch offline) und in `oneblock_mod.json` im Weltordner
  gesichert; das Spiel ueberlebt einen Serverneustart.
- Insel-Befehle (`island`, `new`, `menu`) funktionieren nur, solange das Spiel laeuft.

## Hinweis

Der Code konnte hier nicht gegen Minecraft 26.2 kompiliert oder im Spiel getestet werden. Falls der
Compiler einen Namen nicht kennt, ist das meist eine kleine API-Aenderung. Heikelste Stellen:
`Permissions.COMMANDS_GAMEMASTER` (OneBlockCommands), `Holder.getRegisteredName()` und
`EntityType.spawn(...)` (Game).
