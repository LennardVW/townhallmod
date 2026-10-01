# Prüfung von Townhall 1.15.0

Stand: 1. Oktober 2026. Geprüft wurde lokal mit Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25.0.1 und dem Gradle-Wrapper des Projekts.

## Build und Funktionstests

```text
./gradlew build --offline
```

Ergebnis: `BUILD SUCCESSFUL`, `All 136 required tests passed`. Die vollständige Suite bestand fünf aufeinanderfolgende Durchläufe. Die normale Testsuite läuft mit einer neuen Testwelt und einer neuen Config. Der Build erzeugt `build/libs/townhall-1.15.0.jar`.

Die neuen Tests ergänzen die bisherigen Teleport-, Gefängnis-, Weltregel-, Nicknamen- und Türschloss-Tests. Sie prüfen unter anderem:

- Grundrollen, begrenzte Rechte, Offline-Zuweisung per Befehl, Entzug und UUID-Erhalt nach Namenswechsel.
- Feste Stadtbefehle behalten ihre Rechte trotz gleichnamiger konfigurierter Orte. Der neue Regressionstest schlug vor der Korrektur als einziger von 136 Tests fehl.
- Polizeistrafen mit Minutenlimit, Online-Zeit, unveränderter bestehender Strafe und exakter Rückkehr.
- Freie Welt bei ausgeschalteten Grundstücken, Eigentümer und örtliche Rollenfreigaben, Grenz- und Codec-Prüfung.
- Tatsächliches Platzieren, beide Bett-Hälften, Containerzugriff einschließlich bereits geöffneter Menüs, Kolben und Trichtergrenzen. Die Tests prüfen jeweils auch eine erlaubte Gegenprobe.
- Registrierte und fremde Wahlbücher, ungültige Kopien, zweite Abgabe, volle Inventare, Wahlraum/Dimension/Distanz, Handzählung, vollständige Ergebnissumme, Veröffentlichungsrechte und Archiv nach Freigabe der Urne.
- Anonyme Buchkopien ohne übernommene Autoren, Tokens oder ausführbare Text-Komponenten; Gefangene können nicht per Urnenklick abstimmen.
- Einkäufe, aufeinanderfolgende Käufer bei knappem Bestand, Bezahlung und Platzbedarf, Einnahmen in beiden Währungen, Entzug der Händlerrolle, Creative-Sperren, Offline-Eigentümer und ausdrücklich erlaubte Wiederherstellung.
- Schutz der Ladenfässer, Crafting mit amtlichen Gegenständen, verschachtelte Waren, Codec-Migration und Mengenüberlauf.
- Erfolgreiche und fehlgeschlagene protokollierte Änderungen, verschachtelte Aktionskontexte, begrenzte Einträge und Abfragen, Speicherung und Admin-Rechte.

Alle Mixin-Klassen sind in `townhall.mixins.json` registriert; die Liste wurde gegen die vorhandenen Dateien abgeglichen. `git diff --check` meldete keine Fehler.

## Normaler Server und Neustart

Der Entwicklungsserver wurde mit dem normalen Dedicated-Server-Einstieg gestartet, ohne GameTest-Mod, an `127.0.0.1:25579`. Er erreichte `Done` und wurde mit `stop` beendet. Konsolenbefehle für Rollen, Wahlen, Grundstücke, Läden und Protokoll funktionierten.

Die per Konsole angelegte Testwahl und ihr Protokolleintrag waren nach dem Neustart weiterhin vorhanden. Die neuen `.dat`-Dateien wurden unter `world/data/townhall/` geschrieben und beim nächsten Start geladen.

Der Testserver enthält keine Multiworld-Mod. Das erwartete Fehlen von `minecraft:flatworld` wurde protokolliert; der Server lief weiter. Die Teleport-Tests verwenden dafür den Nether. Die tatsächliche Flatworld-ID muss auf AzubiCraft wie bisher mit `/townhall status` geprüft werden.

## Isolierte Laufzeitmessung

```text
./gradlew runGameTest --offline -Dtownhall.benchOnly=true
```

Der Benchmark lief mit genau 60 simulierten Spielern und 20 Nicknamen in einer frischen Welt. Warm-up und fünf Messrunden verwenden `System.nanoTime`. `-Dtownhall.bench=true` führt ihn zusammen mit den normalen Tests aus; deren zusätzliche Mock-Spieler verfälschen dann die Spielerzahl. Für vergleichbare Messungen deshalb `benchOnly` verwenden.

Ausgewählte Durchschnittswerte dieser lokalen Messung:

| Vorgang | Durchschnitt |
|---|---|
| Grundstück nachschlagen, 2000 Grundstücke | etwa 0,010 µs |
| Eintrag anhängen, Protokoll bereits bei 50.000 Einträgen | etwa 0,175 µs |
| Erfolglos einen Block im vollen Protokoll suchen | etwa 0,411 ms |
| 2000 Grundstücke nach NBT serialisieren | etwa 2,79 ms |
| 50.000 Protokolleinträge nach NBT serialisieren | etwa 28,90 ms |
| Tab-Kopf-/Fußzeile für 60 Mock-Spieler aktualisieren | etwa 0,071 ms |

Die sehr kleinen Suchwerte stammen aus festen, aufgewärmten Mikrobenchmarks. Sie messen weder Netzwerklatenz noch reale Tickrate. Terrain-Generierung, echte Spielerclients, WorldEdit-Aufträge und eure übrigen Mods fehlen. Eine Zusage für 40 echte Spieler lässt sich daraus nicht ableiten.

Das volle Protokoll kostet beim Serialisieren deutlich mehr als einzelne Einträge. Die Speicherung läuft über Minecraft-SavedData, nicht pro Tick. Wer weniger Historie benötigt, kann beispielsweise `/audit limit 10000` verwenden; dabei fallen die ältesten überzähligen Einträge weg. Die Rohwerte schreibt der Benchmark nach `build/run/gameTest/perf-bench.txt`.

## Noch manuell auf AzubiCraft prüfen

1. Mit einem unveränderten Vanilla-Client verbinden, Rolle in Chat und Tab ansehen.
2. Buch im Rathaus wirklich bearbeiten, unsigniert und signiert abgeben, Kopien und zweite Abgabe versuchen.
3. Mit zwei Wahlhelfern dieselben nummerierten Bücher lesen, Zahlen eintragen und Ergebnis veröffentlichen.
4. Als Survival-Händler Ware einzahlen; ein zweiter Spieler kauft bei offline gegangenem Händler; Einnahmen später abholen.
5. Grundstücksschutz testweise aktivieren und echte Spieler sowie eure WorldEdit-/Multiworld-Mods prüfen.
6. Einen Spieler zeitlich einsperren, ausloggen und nach dem Login Restzeit und Rückkehr prüfen.

Es wurde in dieser Runde keine reale Vanilla-Clientsitzung ausgeführt. Shared-Inventar/Creative-Herkunft von Waren, externe Mod-Schreibwege, asynchrone WorldEdit-Zuordnung und harte Abstürze zwischen nativen Speichervorgängen bleiben die in der [Stadt-Anleitung](STADT.md) beschriebenen Grenzen.
