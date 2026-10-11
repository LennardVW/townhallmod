# Prüfung von Townhall 1.16.0

Stand: 1. Oktober 2026. Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25.0.1 und der vorhandene Gradle-Wrapper.

## Build und Tests

`./gradlew build --offline` ist erfolgreich. Die vollständige Suite mit 149 erforderlichen GameTests bestand fünf aufeinanderfolgende Durchläufe. Die fertige JAR liegt unter `build/libs/townhall-1.16.0.jar` und enthält keine GameTest-Klassen.

Die 13 neuen Tests prüfen tatsächliche Minecraft-Befehle und den Netzwerkhandler:

- Bauhelfer ohne OP können `setblock` und `fill` verwenden, auch mit `strict`.
- Unverwandte Befehle wie `op`, `execute`, `gamerule`, `data` und `summon` werden nicht freigegeben.
- Survival, fehlende Baurolle, andere Dimension und Entzug sperren das Einfügen.
- Ein Fill über ein fremdes Grundstück verändert auch im erlaubten Teil nichts und leert keine Container. Derselbe Bereich ist mit Eigentümerfreigabe zugänglich.
- Reservierte Ladenfässer und eine über echte Admin-Befehle eingerichtete Wahlurne bleiben geschützt.
- Eine abgeschlossene Tür wird ohne passenden Schlüssel nicht überschrieben. Mit dem echten Schlüssel funktioniert dieselbe Änderung.
- Admin-Blöcke und NBT werden für Bauhelfer verweigert; gewöhnliche Container bleiben platzierbar. Die Konsole behält ihre bisherigen Vanilla-Rechte.
- Haft, ausstehende Regelannahme und künstlich erhöhte Source-Rechte umgehen die Prüfung nicht.
- Weltgrenze, Mengenlimit und ungeladene Chunks werden geprüft. Die Prüfung lädt keine Zielchunks.
- Ein Burst von 32 Befehlen durch den realen Unsigned-Command-Handler verändert Blöcke ohne OP und erhöht den normalen Command-Spam-Zähler nicht. Chat und andere Befehle erhöhen ihre Zähler weiterhin.
- Der 65. direkte Paste-Befehl im selben Servertick verändert keine Blöcke und wird normal als Spam gezählt.

Mixin-Signaturen wurden anhand der tatsächlichen Minecraft-JAR geprüft. Alle neuen Mixins sind registriert; der normale Dedicated-Server-Einstieg an `127.0.0.1:25580` startete ohne Mixin-Fehler. Konsolenhilfe für `setblock`/`fill` und Verwaltungsbefehle wurden geprüft; der Server wurde mit `stop` beendet. Die erwartete Meldung zur fehlenden Flatworld ist im isolierten Testserver unkritisch, da dort keine Multiworld-Mod installiert ist.

## Lokale Laufzeitmessung

`./gradlew runGameTest --offline -Dtownhall.benchOnly=true` lief separat mit 60 Mock-Spielern. Für die neue Prüfung wurden Zielchunks vorher initialisiert. Warm-up und fünf Messrunden, gemessen mit `System.nanoTime`:

| Bereich mit Luftblöcken | Durchschnitt der Bereichsprüfung |
|---|---|
| 1 Block | etwa 0,0022 ms |
| 512 Blöcke | etwa 0,0555 ms |
| 32.768 Blöcke | etwa 2,243 ms |

Diese Werte messen nur die Prüfung, keine tatsächliche Platzierung, Blockupdates, NBT, Netzwerklatenz oder Chunk-Generierung. Viele vorhandene Container, externe Schutz-Events, mehrere gleichzeitig bauende Spieler und andere Mods können mehr Zeit beanspruchen. Die Tickrate für 40 echte Spieler wurde hier nicht gemessen.

## Praxistest auf AzubiCraft

Eine reale Litematica-/Vanilla-Clientsitzung wurde in dieser Runde nicht ausgeführt. Vor dem breiten Einsatz mit einem Bauhelfer eine kleine Schematic in der echten Flatworld einfügen und dieselbe Platzierung über einer Schutzgrenze versuchen. Die neuen Tests verwenden native Command-Ausführung und den echten Netzwerkhandler mit Mock-Verbindungen; sie ersetzen nicht die Client-Bedienprüfung.

Auch die konkrete Kombination mit ChestLock, WorldEdit, Multiworld und externen Rechte-Mods muss auf eurem Server geprüft werden. Die Freigabe verwendet Fabric-Blockabbau-Vetos, integriert aber keine Servux-Paste-Pakete. Einrichtung und Client-Einstellungen stehen in [LITEMATICA.md](LITEMATICA.md).
