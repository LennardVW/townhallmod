# Schematics als Bauhelfer einfügen

Ab Townhall 1.16.0 dürfen eingetragene Bauhelfer Litematicas Command-Paste verwenden. Das bestehende `/builder`-Recht reicht. Es gilt jeweils für eine Dimension und wird nach einem Serverneustart wie bisher aus der Config geladen.

## Admin: Recht vergeben

```text
/builder add minecraft:flatworld Max
/builder list minecraft:flatworld
```

Verwende die tatsächliche Dimension-ID aus `/townhall status`. Sie kann bei einer Multiworld-Mod anders heißen. Die Zuweisung funktioniert auch offline, sobald der Server den Spielernamen auflösen kann.

Die Stadtrolle `architekt` ist ein Titel mit örtlichen Grundstücksfreigaben. Für Creative und Schematic-Paste braucht der Spieler zusätzlich das hier beschriebene `/builder`-Recht.

Entziehen:

```text
/builder remove minecraft:flatworld Max
```

Die Freigabe entfällt sofort. Vorhandene Bauhelfer müssen für das Update nicht erneut eingetragen werden.

## Bauhelfer: Schematic platzieren

1. In die freigegebene Bauwelt gehen und `/builder creative` eingeben.
2. Litematica und MaLiLib passend zu deiner Minecraft-Version auf deinem Rechner verwenden.
3. Schematic laden, eine Platzierung erstellen und richtig ausrichten.
4. In Litematica den Modus `Paste schematic in world` wählen.
5. Die richtige Platzierung auswählen und mit dem konfigurierten `executeOperation`-Hotkey starten.

Für diese Freigabe den normalen Command-Paste verwenden. Unter `Generic`:

| Einstellung | Wert |
|---|---|
| `commandUseWorldEdit` | `false` |
| `pasteIgnoreEntities` | `true` |
| `pasteIgnoreInventories` | `true` |
| `pasteNbtRestoreBehavior` | `None` |
| `pasteUseFillCommand` | `true` |
| `commandLimitPerTick` | Zum Einstieg `8`; höchstens `64` |

Die Standard-Befehlsnamen `setblock` und `fill` beibehalten. Falls deine Litematica-Version einen anderen Server-Paste-Modus anbietet, wähle den Command-Modus. Diese Freigabe enthält keine Servux-Paketberechtigungen.

Du musst die Blöcke nicht einzeln setzen. Litematica schickt die passenden `/setblock`- und `/fill`-Befehle; Townhall erlaubt diese in deiner Bauwelt. Große Schematics werden über mehrere Ticks eingefügt. Bei Last das Befehlslimit senken oder `commandTaskInterval` erhöhen.

Nach dem Bauen: `/builder survival`. Andere Spieler können weiterhin mit Vanilla-Clients spielen; der Townhall-Server benötigt Litematica nicht.

## Was die Freigabe umfasst

- `/setblock` und `/fill`, einschließlich `strict`, im Creative-Modus und in der eigenen Bauwelt.
- Grundstücksschutz bleibt aktiv: Ein fremder Bereich innerhalb eines Fill-Befehls verhindert den gesamten Befehl, bevor Container geleert oder Blöcke geändert werden. Ein aus mehreren Befehlen bestehender Paste kann deshalb nur teilweise fertig werden.
- Reservierte Ladenfässer und Wahlurnen bleiben geschützt. Blockabbau-Sperren von Türschlüsseln und installierten Fabric-Mods werden vor dem Überschreiben abgefragt.
- Höchstens 32.768 Zielblöcke je Befehl und 64 Paste-Befehle pro Spieler und Servertick. Der Zielbereich muss innerhalb der Weltgrenze liegen und seine Chunks müssen bereits geladen sein.
- Befehlsserien innerhalb dieser Grenze zählen nicht gegen Minecrafts normales Command-Spam-Limit. Zu viele Paste-Befehle werden verworfen und wieder normal als Spam gezählt. Chat und andere Befehle behalten ihre eigene Prüfung.
- Regeln müssen akzeptiert sein; während einer Haft ist das Einfügen gesperrt.

NBT-Daten, Kisteninhalte, Schildtexte, Entities und Admin-Blöcke wie Command- oder Structure-Blöcke werden über diese Bauhelfer-Freigabe nicht übernommen. Es werden auch keine Rechte für `/data`, `/summon`, `/execute`, `/op` oder `/gamerule` vergeben. Für solche Sonderfälle kann ein Admin selbst einfügen.

Da der Server eine Schematic nicht von einem manuell eingegebenen Befehl unterscheiden kann, dürfen Bauhelfer die freigegebenen `/setblock`- und `/fill`-Befehle auch selbst verwenden. Dieselben Prüfungen gelten dabei.

## Fehlerhilfe

| Meldung oder Problem | Prüfen |
|---|---|
| `Unknown command` | Townhall-Version, `/builder list`, aktuelle Dimension und Standard-Befehlsnamen prüfen. |
| `Nutze zuerst /builder creative` | In der eigenen Bauwelt in Creative wechseln. |
| `Geschützter Bereich` | Das gesamte Fill-Rechteck muss erlaubt sein. Als Admin Grundstücksfreigaben vergeben oder die Platzierung verschieben. |
| NBT-Daten gesperrt | Inventar-/NBT-Restore ausschalten; oben genannte Einstellungen verwenden. |
| Paste zu schnell / Spam-Kick | `commandLimitPerTick` senken. Der Server verwirft Befehle oberhalb von 64 pro Tick; den betroffenen Bereich danach erneut einfügen. |
| Ein Teil fehlt | Zielchunks laden, Schutzgrenzen und Litematicas Ersetzungsmodus prüfen. |

Grundlage für die Client-Bedienung: [Litematica – Schematic Pasting](https://github.com/maruohon/litematica/wiki/Schematic-Pasting). Die Schaltflächen und Standard-Hotkeys können sich je nach Client-Version unterscheiden.
