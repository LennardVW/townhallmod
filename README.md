# Townhall

Serverseitige Fabric-Mod für den Minecraft-Server **AzubiCraft** (Minecraft 26.3).

Townhall fasst die Dinge zusammen, für die man sonst mehrere Plugins bräuchte: Teleport ins Rathaus und exakt zurück, ein Gefängnis mit Zeitstrafen, eigene Regeln pro Welt, Baurechte für einzelne Spieler, Türschlösser mit Schlüsseln, Nicknamen, AFK-Anzeige, Spielzeit-Bestenliste, eigene Join-Nachrichten und eine gestaltete Tab-Liste.

Die Mod läuft nur auf dem Server. Spieler joinen mit einem ganz normalen Minecraft-Client und müssen nichts installieren.

Aktuelle Version: **1.12.2**. Was sich wann geändert hat, steht in [CHANGELOG.md](CHANGELOG.md).

---

## Inhalt

- [Funktionen im Überblick](#funktionen-im-überblick)
- [Voraussetzungen](#voraussetzungen)
- [Installation](#installation)
- [Für Spieler](#für-spieler)
- [Für Admins](#für-admins)
  - [Orte: Rathaus, Gefängnis und eigene Ziele](#orte-rathaus-gefängnis-und-eigene-ziele)
  - [Orte per Befehl verwalten](#orte-per-befehl-verwalten)
  - [Regeln pro Welt](#regeln-pro-welt)
  - [Zeit und Wetter festhalten](#zeit-und-wetter-festhalten)
  - [Bauer: Baurechte für einzelne Spieler](#bauer-baurechte-für-einzelne-spieler)
  - [Schlüssel für Türen](#schlüssel-für-türen)
  - [Nicknamen](#nicknamen)
  - [AFK und Spielzeit](#afk-und-spielzeit)
  - [Join-Nachrichten](#join-nachrichten)
  - [Tab-Liste: Kopfzeile, Fußzeile, Tode](#tab-liste-kopfzeile-fußzeile-tode)
  - [Begrüßung und Regeln beim ersten Join](#begrüßung-und-regeln-beim-ersten-join)
  - [Konfigurationsdatei](#konfigurationsdatei)
  - [Wo die Daten liegen](#wo-die-daten-liegen)
  - [Update](#update)
  - [Probleme und Lösungen](#probleme-und-lösungen)
- [Alle Befehle auf einen Blick](#alle-befehle-auf-einen-blick)
- [Bekannte Grenzen](#bekannte-grenzen)
- [Selbst bauen und entwickeln](#selbst-bauen-und-entwickeln)
- [Sicherheit](#sicherheit)
- [Lizenz](#lizenz)

---

## Funktionen im Überblick

| Bereich | Was die Mod macht |
|---|---|
| Rathaus | `/townhall` bringt dich in die Rathaus-Welt, `/townhall return` genau an die alte Stelle zurück (gleiche Welt, Position und Blickrichtung). |
| Gefängnis | Admins schicken Spieler hinein, auf Wunsch für eine bestimmte Online-Zeit. Ausbrechen, Befehle und Sterben helfen nicht. |
| Eigene Orte | Beliebig viele Ziele mit eigenem Befehl, zum Beispiel `/arena` oder `/bunker`. Anlegen und ändern geht komplett per Befehl im Spiel. |
| Regeln pro Welt | Schwierigkeit, PvP, Bauschutz, Hunger, Fallschaden, Mob-Spawn, Feuer, Explosionen und Laubzerfall lassen sich für jede Welt einzeln festlegen. |
| Zeit und Wetter | Eine Welt kann dauerhaft Tag und trockenes Wetter haben, während die anderen Welten normal weiterlaufen. |
| Bauer | Einzelne Spieler dürfen in einer geschützten Welt bauen, selbst zwischen Creative und Survival wechseln und WorldEdit benutzen. |
| Türschlösser | Mit einem benannten Schlüssel wird jede Tür abgeschlossen. Schlüssel lassen sich kopieren und weitergeben, Admins haben einen Generalschlüssel. |
| Nicknamen | Admins vergeben Nicknamen (bis 32 Zeichen, mit Farben). Sie erscheinen im Chat, in der Tab-Liste und über dem Kopf. |
| AFK und Spielzeit | `[AFK]` in der Tab-Liste nach 5 Minuten Inaktivität, `/playtime` mit Top-10-Bestenliste. |
| Anzeige | Eigene Join-, Leave- und Erstjoin-Nachrichten, Kopf- und Fußzeile der Tab-Liste, rote Todeszahl neben jedem Namen. |
| Onboarding | Neue Spieler sehen beim ersten Join Begrüßung, Tutorial und Regeln und müssen die Regeln akzeptieren, bevor sie spielen können. |

Alle Admin-Einstellungen lassen sich im Spiel per Befehl ändern. Sie gelten sofort, ohne `/reload` und ohne Neustart.

---

## Voraussetzungen

| | Version |
|---|---|
| Minecraft (Server) | 26.3 |
| Fabric Loader | 0.19.5 oder neuer |
| Fabric API | passend zu 26.3, getestet mit `0.161.0+26.3` |
| Java | 25 |
| Client der Spieler | Vanilla 26.3, keine Mod nötig |

Townhall erstellt selbst keine Welten. Die Rathaus- oder Flatworld-Welt kommt von einer Multiworld-Mod (auf AzubiCraft: mc-worlds).

Getestet zusammen mit mc-worlds, WorldEdit 7.4.6, C2ME, Lithium, spark und ChestLock. WorldEdit ist optional; ohne WorldEdit fehlt nur die WorldEdit-Freigabe für Bauer.

---

## Installation

1. Server stoppen und die Welt sichern.
2. Die aktuelle `townhall-<version>.jar` von der [Releases-Seite](https://github.com/LennardVW/townhallmod/releases) herunterladen.
3. Diese Jar und die Fabric API in den Ordner `mods/` des Servers legen.
4. Server starten. Beim ersten Start legt die Mod `config/townhall.json` an.
5. Im Server-Log nach `dimension ... found` oder `not loaded` suchen. Steht dort „not loaded“, listet das Log alle vorhandenen Welten auf. Den richtigen Namen (zum Beispiel `multiworld:flatworld`) mit `/location set townhall dimension <welt>` eintragen.
6. In die Rathaus-Welt gehen, an die gewünschte Ankunftsstelle stellen und `/townhall setspawn` eingeben.

Danach funktioniert `/townhall` für alle Spieler.

> Lege keinen Ort in die normale Oberwelt. Wer in der Welt eines Ortes steht, gilt als „drin“, und dann wird keine Rückkehrstelle gespeichert.

---

## Für Spieler

### Beim ersten Join

Du siehst eine Begrüßung, ein kurzes Tutorial und die Serverregeln. Klick auf **[Regeln akzeptieren]** oder tippe `/rules accept` (auf Deutsch: `/regeln akzeptieren`).

Bis du akzeptiert hast, kannst du dich nicht bewegen, nicht chatten und nichts abbauen. Dafür kann dir in dieser Zeit auch nichts passieren. Die Regeln kannst du später jederzeit mit `/rules` oder `/regeln` nachlesen.

### Befehle für alle

| Befehl | Was passiert |
|---|---|
| `/townhall` | Du gehst ins Rathaus. Deine aktuelle Stelle wird gespeichert. |
| `/townhall return` | Du kommst an die gespeicherte Stelle zurück. |
| `/rules` oder `/regeln` | Serverregeln anzeigen |
| `/afk` | Dich selbst sofort als AFK melden |
| `/playtime` | Deine Spielzeit |
| `/playtime <spieler>` | Spielzeit eines anderen Spielers, auch wenn er offline ist |
| `/playtime top` | Bestenliste der zehn Spieler mit der meisten Spielzeit |
| `/key new <name>` | Neuen Türschlüssel mit Namen erzeugen |
| `/key copy [spieler]` | Den Schlüssel in deiner Hand kopieren, auf Wunsch direkt für einen anderen Spieler |
| `/key info` | Zeigt, mit welchem Schlüssel die Tür vor dir abgeschlossen ist |

### Rathaus: so funktioniert die Rückkehr

- „Zurück“ heißt exakt zurück: gleiche Welt, gleiche Stelle, gleiche Blickrichtung.
- Die Stelle bleibt gespeichert, auch wenn du dich ausloggst, stirbst oder der Server neu startet.
- Tippst du im Rathaus noch einmal `/townhall`, passiert nichts. Deine alte Stelle wird nicht überschrieben.
- Ist die alte Stelle inzwischen zugebaut oder liegt dort Lava, landest du auf dem nächsten sicheren Platz daneben. Findet die Mod keinen, bringt sie dich zum Spawn.
- Zwischen zwei Teleport-Befehlen liegen standardmäßig 3 Sekunden Wartezeit.

### Türen abschließen

1. `/key new Haus` gibt dir einen Schlüssel mit dem Namen „Haus“ (ein Haken-Item mit Namen und Beschreibung).
2. Rechtsklick mit dem Schlüssel auf eine Tür schließt sie ab.
3. Schleichen und Rechtsklick mit demselben Schlüssel schließt sie wieder auf.
4. `/key copy Max` gibt Max eine Kopie. Die Kopie öffnet dieselben Türen.

Es reicht, wenn der Schlüssel irgendwo im Inventar liegt. Du musst ihn nicht in der Hand halten. Ohne passenden Schlüssel lässt sich die Tür weder öffnen noch abbauen, und auch Knöpfe, Hebel, Redstone und Dorfbewohner öffnen sie nicht. Das funktioniert mit Holz-, Kupfer- und Eisentüren.

- Abschließen kannst du nur Türen, an denen du bauen darfst. In einer Welt mit `build false` bleiben öffentliche Türen also offen (außer für Bauer und Admins).
- Eine abgeschlossene Tür bleibt stehen, auch wenn der Block darunter verschwindet. Den Block darunter abbauen darf nur, wer den Schlüssel hat. Kolben, Explosionen und Zombies zerstören abgeschlossene Türen nicht.
- Verschwindet eine abgeschlossene Tür trotzdem (zum Beispiel per Befehl), ist das Schloss weg. Eine neue Tür an derselben Stelle ist nicht abgeschlossen.
- `/key new` und `/key copy` gehen je einmal alle 10 Sekunden (Admins ohne Wartezeit). Schlüssel lassen sich nicht zu Fallenkisten oder Armbrüsten verarbeiten.

### Im Gefängnis

Hat dich ein Admin ins Gefängnis geschickt, kommst du nicht selbst heraus.

- Du kannst nur noch wenige Befehle benutzen, etwa `/msg` oder `/list`.
- Entkommst du trotzdem (Enderperle, Portal), holt dich die Mod nach einer Sekunde zurück.
- Wenn du stirbst, wachst du wieder im Gefängnis auf.

Hat der Admin eine Dauer angegeben, siehst du unten auf dem Bildschirm die Restzeit. Sie läuft nur, solange du online bist. Ist sie abgelaufen, bist du automatisch frei und stehst wieder dort, wo du vorher warst.

---

## Für Admins

Admin ist, wer Op-Level 2 oder höher hat (`/op <name>`). Das Level lässt sich in der Config ändern. Die meisten Befehle funktionieren auch in der Server-Konsole (ohne `/`). Ausgenommen sind Befehle, die deine Position brauchen, wie `setspawn`.

### Orte: Rathaus, Gefängnis und eigene Ziele

Jeder Ort hat einen eigenen Befehl. Mitgeliefert werden `/townhall` und `/gefaengnis`. Alle Befehle unten funktionieren für jeden Ort; statt `townhall` setzt du einfach den Befehl des Ortes ein.

| Befehl | Was passiert |
|---|---|
| `/townhall send Max` | Schickt Max hin und speichert seine Stelle. |
| `/townhall send Max 30` | Schickt Max für 30 Minuten Online-Zeit hin. Danach kommt er automatisch zurück. |
| `/townhall return Max` | Holt Max zurück und lässt ihn frei, falls er eingesperrt war. |
| `/townhall setspawn` | Ankunftspunkt ist ab jetzt deine Position und Blickrichtung. Du musst dafür in der Welt des Ortes stehen. |
| `/townhall status` | Zeigt alle Orte, ihre Welten und wie viele Stellen gespeichert sind. |
| `/townhall debug Max` | Zeigt, was über Max gespeichert ist. Klappt auch, wenn er offline ist. |
| `/townhall clearreturn Max` | Löscht die gespeicherte Stelle von Max und lässt ihn frei. |
| `/townhall reload` | Lädt die Config neu. Ist die Datei fehlerhaft, bleibt die alte Einstellung aktiv. |

**Beispiel Gefängnis**

```
/gefaengnis send Max          Max sitzt, bis du ihn freilässt
/gefaengnis send Max 30       Max sitzt 30 Minuten (nur Online-Zeit zählt)
/gefaengnis send Max 60       Neue Zeit überschreibt die alte
/gefaengnis return Max        Max ist sofort frei und wieder an seiner alten Stelle
```

Hat Max keine gespeicherte Stelle (etwa weil er schon vorher in der Gefängniswelt war), bringt `return` ihn zum Spawn und lässt ihn frei.

Admins werden nie eingesperrt, auch dann nicht, wenn sie sich selbst ins Gefängnis schicken. Sie behalten alle Befehle und kommen mit `/gefaengnis return` oder `/tp` wieder heraus.

### Orte per Befehl verwalten

Mit `/location` legst du Orte an und änderst sie, ohne die Config-Datei zu öffnen. Jede Änderung wird sofort gespeichert. Neue oder umbenannte Befehle stehen allen Spielern sofort zur Verfügung.

| Befehl | Was er macht |
|---|---|
| `/location list` | Alle Orte anzeigen |
| `/location info bunker` | Alle Einstellungen eines Ortes anzeigen |
| `/location create bunker` | Neuer Ort mit dem Befehl `/bunker` an deiner Position |
| `/location create bunker prison` | Neuer Ort als Gefängnis: nur für Admins, kein Ausbruch |
| `/location setspawn bunker` | Ankunftspunkt und Welt auf deine aktuelle Position setzen |
| `/location set bunker escapable false` | `false` macht den Ort zum Gefängnis, `true` erlaubt freies Gehen |
| `/location set bunker adminOnly true` | Nur Admins sehen und benutzen den Befehl |
| `/location set bunker radius 32` | So viele Blöcke dürfen sich Gefangene vom Ankunftspunkt entfernen |
| `/location set bunker command knast` | Befehl umbenennen: aus `/bunker` wird `/knast` |
| `/location set bunker dimension multiworld:flatworld` | Welt des Ortes ändern |
| `/location set bunker name the bunker` | Name, der in Nachrichten erscheint |
| `/location set bunker message Du bist im Bunker.` | Nachricht bei Ankunft |
| `/location set bunker title &cBunker` | Großer Titel bei Ankunft (`subtitle` funktioniert genauso, `-` entfernt ihn) |
| `/location set bunker alreadyHereMessage ...` | Nachricht, wenn man schon dort ist |
| `/location delete bunker` | Ort löschen. Das geht nicht, solange dort noch jemand festsitzt. |

Befehlsnamen dürfen Umlaute und `:` enthalten. Dann gehören sie in Anführungszeichen, zum Beispiel `"townhall:gefängnis"`. Namen, die schon Minecraft oder eine andere Mod benutzt, lehnt die Mod ab.

### Regeln pro Welt

Jede Welt, auch die Welten anderer Mods, kann eigene Regeln bekommen. Den Weltnamen schlägt die Tab-Taste vor.

```
/townhall worldrule multiworld:flatworld                 alle Regeln dieser Welt anzeigen
/townhall worldrule multiworld:flatworld build false     Regel setzen
/townhall worldrule multiworld:flatworld build default   zurück zum normalen Minecraft-Verhalten
/townhall difficulty multiworld:flatworld peaceful       Schwierigkeit nur für diese Welt
```

| Regel | Wirkung bei `false` |
|---|---|
| `pvp` | Spieler können sich nicht gegenseitig schaden. |
| `build` | Nur Admins und eingetragene Bauer dürfen Blöcke setzen oder abbauen, Eimer, Feuerzeug, Rahmen und Rüstungsständer benutzen, Schilder beschreiben oder färben, Blumentöpfe, Verstärker, Komparatoren, Notenblöcke und Tageslichtsensoren verstellen, Farbstoff, Tintenbeutel, Honigwaben, Scheren, Pinsel, Enderaugen und Wasserflaschen auf Blöcke anwenden. Pfeile, Dreizacke und Schneebälle von anderen Spielern zerstören keine Rahmen, Bilder, Rüstungsständer, Boote, Loren oder Krüge und zünden kein TNT. Benutzbar bleiben Türen, Falltüren, Zauntore, Knöpfe, Hebel, Betten, Kisten und alle Blöcke mit Menü (Werkbank, Ofen, Amboss, Zaubertisch und so weiter), auch mit Werkzeug oder Eimer in der Hand. Wer schleicht, benutzt das Item statt des Blocks. |
| `hunger` | Kein Hunger. Beim Betreten der Welt wird die Hungerleiste aufgefüllt. |
| `fallDamage` | Kein Fallschaden. |
| `mobs` | Mobs spawnen nicht von selbst, auch nicht aus Spawnern, als Patrouille oder als Phantom. Spawn-Eier und Befehle funktionieren weiter. |
| `fire` | Feuer geht sofort aus, breitet sich nicht aus und verbrennt nichts. |
| `explosions` | Explosionen zerstören keine Blöcke. Schaden an Spielern und Mobs bleibt. |
| `leafDecay` | Laub zerfällt nie. |

Die Schwierigkeit kennt die Werte `peaceful`, `easy`, `normal`, `hard` und `default`. Sie wirkt auf Monster, Schaden, Hunger und Heilung in dieser Welt, und Spieler sehen im Menü die Schwierigkeit der Welt, in der sie gerade stehen.

### Zeit und Wetter festhalten

Damit ist zum Beispiel im Rathaus immer Mittag und es regnet nie, während in den anderen Welten Tag, Nacht und Wetter normal weiterlaufen.

```
/townhall worldrule multiworld:flatworld time noon
/townhall worldrule multiworld:flatworld weather clear
```

| Regel | Mögliche Werte |
|---|---|
| `time` | `day` (Morgen), `noon` (Mittag), `night` (Abend), `midnight` oder eine Zahl von `0` bis `23999` (`6000` ist Mittag). `default` hebt die Regel auf. |
| `weather` | `clear` (nie Regen), `rain` (immer Regen), `thunder` (immer Gewitter), `default` |

`/time set` und `/weather` wirken danach nur noch auf die anderen Welten. Dorfbewohner füllen ihren Handel trotzdem einmal pro Tag auf. Nether und End können kein Wetter haben; `time` funktioniert dagegen überall.

### Bauer: Baurechte für einzelne Spieler

Gilt in einer Welt `build false`, dürfen dort normalerweise nur Admins bauen. Mit der Bauer-Rolle gibst du einzelnen Spielern dieses Recht, und zwar nur für die Welten, in die du sie einträgst.

| Befehl | Wer | Was er macht |
|---|---|---|
| `/builder add multiworld:flatworld Max` | Admin | Max wird Bauer in der Flatworld. Max muss dafür nicht online sein; auch wer noch nie auf dem Server war, wird über Mojang gefunden. |
| `/builder remove multiworld:flatworld Max` | Admin | Recht wieder entziehen. Max wechselt sofort zurück in Survival. |
| `/builder list [welt]` | Admin | Alle Bauer anzeigen |
| `/builder creative` | Bauer | In der eigenen Bauwelt in den Creative-Modus wechseln |
| `/builder survival` | Bauer | Zurück in Survival |

In seiner Welt darf ein Bauer bauen und abbauen wie ein Admin und WorldEdit benutzen (`//wand`, `//set`, `//copy` und so weiter). Gesperrt bleiben WorldEdit-Befehle für Admins: `/we reload`, `//world`, `/butcher`, `/remove…`, Snapshots, unbegrenzte Limits, CraftScripts (`/cs`), Schematics löschen, `setnbt` und ähnliche. Schematics speichern und laden dürfen Bauer.

Verlässt der Bauer seine Welt durch Teleport oder Tod oder loggt er sich woanders ein, ist er automatisch wieder im Survival-Modus und hat dort kein WorldEdit.

> Das Inventar ist in allen Welten dasselbe. Was ein Bauer im Creative-Modus holt, kann er in andere Welten mitnehmen. Vergib das Recht deshalb nur an Leute, denen du vertraust.

### Schlüssel für Türen

Die Spielerbefehle stehen oben unter [Türen abschließen](#türen-abschließen). Admins haben zwei zusätzliche Befehle:

| Befehl | Was er macht |
|---|---|
| `/key admin` | Generalschlüssel, der jede abgeschlossene Tür öffnet |
| `/key unlock` | Entfernt das Schloss der Tür, die du gerade anschaust |

Der Generalschlüssel wirkt nur, solange sein Besitzer Admin ist. Findet ein normaler Spieler einen verlorenen Generalschlüssel, kann er damit nichts öffnen.

### Nicknamen

Nur Admins vergeben Nicknamen, Spieler können ihren eigenen nicht ändern.

| Befehl | Was er macht |
|---|---|
| `/nick set Max &6Bürgermeister` | Max heißt jetzt „Bürgermeister“ in Gold. Farben mit `&`, höchstens 32 Zeichen. Funktioniert auch, wenn Max offline ist. |
| `/nick reset Max` | Wieder der echte Name |
| `/nick list` | Alle vergebenen Nicknamen |

Der Nickname erscheint im Chat, in der Tab-Liste und über dem Kopf des Spielers. Fährt man im Chat mit der Maus darüber, sieht man den echten Namen. Befehle wie `/tp Max` oder `/msg Max` benutzen weiter den echten Namen. Ein Nickname darf nicht so heißen wie ein anderer Spieler; Farbcodes zählen dabei nicht mit.

### AFK und Spielzeit

Wer 5 Minuten lang nicht läuft, sich nicht umschaut und nichts schreibt, bekommt in der Tab-Liste ein graues `[AFK]`. Im Chat erscheint „Max ist jetzt AFK“ und später „Max ist zurück“.

`/playtime` zählt nur die Zeit, in der jemand nicht AFK ist. Spielzeit aus der Zeit vor der Mod übernimmt sie aus der Minecraft-Statistik.

| Befehl | Was er macht |
|---|---|
| `/townhall afktime 10` | AFK erst nach 10 Minuten. `0` bedeutet: nur noch per `/afk`. |

### Join-Nachrichten

Statt „Max joined the game“ schreibt der Server eigene Nachrichten. `{player}` wird durch den Namen ersetzt, bei Spielern mit Nicknamen durch den Nicknamen. Ein `-` statt eines Textes schaltet die jeweilige Nachricht ab.

| Befehl | Was er macht |
|---|---|
| `/joinmessage join &a+ &f{player} &7ist AzubiCraft beigetreten.` | Nachricht beim Betreten |
| `/joinmessage leave &c- &f{player} &7hat AzubiCraft verlassen.` | Nachricht beim Verlassen |
| `/joinmessage firstjoin &6{player} ist zum ersten Mal hier!` | Nachricht beim allerersten Betreten |
| `/joinmessage set Max &6Der Chef ist da!` | Eigene Join-Nachricht nur für Max |
| `/joinmessage reset Max` | Max bekommt wieder die normale Nachricht |
| `/joinmessage off` | Zurück zu den Minecraft-Nachrichten (`on` schaltet die eigenen wieder ein) |

### Tab-Liste: Kopfzeile, Fußzeile, Tode

Oben in der Tab-Liste steht **AzubiCraft** mit einer Begrüßung, unten stehen Online-Spieler, Ping und Spielzeit. Neben jedem Namen zeigt eine rote Zahl, wie oft der Spieler gestorben ist. Tode aus der Zeit vor der Mod zählen mit.

| Befehl | Was er macht |
|---|---|
| `/tablist header &6&lAzubiCraft\|&7Hallo {player}!` | Kopfzeile setzen. `\|` beginnt eine neue Zeile, `-` bedeutet leer. |
| `/tablist footer &7Online: {online}/{max}` | Fußzeile setzen |
| `/tablist off` | Kopf- und Fußzeile ausschalten (`on` schaltet sie wieder ein) |
| `/townhall deathsintab off` | Todeszahl ausblenden (`on` zeigt sie wieder) |

Platzhalter für Kopf- und Fußzeile: `{player}`, `{online}`, `{max}`, `{ping}`, `{playtime}`, `{world}`.

Die Tab-Liste kann neben dem Namen nur eine Zahl zeigen. Nutzt du dort schon eine eigene Scoreboard-Anzeige (`/scoreboard objectives setdisplay list ...`), ersetzt die Mod sie beim Start. In dem Fall `/townhall deathsintab off` eingeben.

### Begrüßung und Regeln beim ersten Join

Texte, Regeln und Verhalten stehen in der Config unter `onboarding`. Ändern sich die Regeln, erhöhst du `onboarding.rulesVersion`. Dann müssen alle Spieler sie beim nächsten Join neu akzeptieren.

| Befehl | Was er macht |
|---|---|
| `/rules reset Max` | Max muss die Regeln erneut lesen und akzeptieren |

Admins sehen die Texte ebenfalls, werden aber nicht eingeschränkt.

### Konfigurationsdatei

Alles lässt sich per Befehl einstellen. Wer lieber direkt in `config/townhall.json` arbeitet, lädt die Datei danach mit `/townhall reload` neu. Ist die Datei fehlerhaft, nennt die Meldung den Fehler, und die alte Einstellung bleibt aktiv.

**Einstellungen pro Ort** (unter `locations`)

| Einstellung | Bedeutung |
|---|---|
| `command` | Befehlsname ohne `/` |
| `displayName` | Name in Nachrichten |
| `dimension` | Welt des Ortes. Mehrere Orte dürfen in derselben Welt liegen. |
| `spawn` | Ankunftspunkt mit `x`, `y`, `z`, `yaw` (Blickrichtung) und `pitch` (Blick nach oben oder unten) |
| `adminOnly` | `true`: nur Admins können den Befehl benutzen |
| `escapable` | `false`: Gefängnis, nur ein Admin oder das Ende der Zeit lässt einen heraus |
| `confineRadius` | Bewegungsradius für Gefangene in Blöcken. `0` prüft nur die Welt. |
| `arrivedMessage`, `alreadyHereMessage` | Nachrichten bei Ankunft und wenn man schon dort ist |
| `title`, `subtitle` | Großer Titel in der Bildschirmmitte, zum Beispiel `"&6Townhall"`. Leer bedeutet kein Titel. |

**Allgemeine Einstellungen**

| Einstellung | Standard | Bedeutung |
|---|---|---|
| `return.clearAfterSuccessfulReturn` | `true` | Gespeicherte Stelle nach der Rückkehr löschen |
| `safeTeleport.enabled` | `true` | Vor der Rückkehr prüfen, ob die Stelle sicher ist |
| `safeTeleport.horizontalRadius` / `verticalRadius` | `5` / `5` | Suchradius für einen sicheren Platz (höchstens 16) |
| `fallback.useWorldSpawn` | `true` | Notfallziel ist der Welt-Spawn |
| `fallback.dimension`, `fallback.position` | Oberwelt, `0.5 70 0.5` | Notfallziel, wenn `useWorldSpawn` auf `false` steht |
| `commands.cooldownSeconds` | `3` | Wartezeit für Spieler zwischen zwei Befehlen |
| `commands.operatorsBypassCooldown` | `true` | Admins müssen nicht warten |
| `commands.operatorPermissionLevel` | `2` | Op-Level für Admin-Befehle (1 bis 4) |
| `confinement.allowedCommands` | `msg`, `tell`, `w`, `r`, `me`, `teammsg`, `tm`, `list`, `help`, `trigger` | Befehle, die Gefangene benutzen dürfen |
| `dimensions` | leer | Regeln pro Welt, siehe oben |
| `deathsInTab` | `true` | Rote Todeszahl in der Tab-Liste |
| `afkMinutes` | `5` | Minuten bis AFK, `0` nur manuell |
| `joinMessages.*` | AzubiCraft-Texte | Join-, Leave- und Erstjoin-Nachrichten, eigene Nachrichten pro Spieler |
| `tabList.*` | AzubiCraft-Kopf- und Fußzeile | Zeilen mit Platzhaltern |
| `onboarding.enabled` | `true` | Begrüßung und Regeln beim ersten Join |
| `onboarding.welcome`, `tutorial`, `rules` | Beispieltexte | Listen von Zeilen. Farben mit `&` (`&a` grün, `&l` fett), `%player%` für den Namen. |
| `onboarding.rulesVersion` | `1` | Erhöhen, wenn sich die Regeln ändern |
| `onboarding.restrictUntilAccepted` | `true` | Bis zum Akzeptieren kein Bewegen, Chatten, Bauen und keine Befehle |
| `onboarding.reminderSeconds` | `30` | Abstand, in dem der Knopf erneut erscheint |
| `messages.*` | Englisch | Alle Chat-Texte der Teleport-Befehle |
| `debugLogging` | `false` | Jeden Teleport ins Log schreiben |

Beispiel für Weltregeln in der Datei:

```json
"dimensions": {
  "multiworld:flatworld": {
    "difficulty": "peaceful",
    "pvp": false,
    "build": false,
    "hunger": false,
    "fallDamage": false,
    "mobs": false,
    "fire": false,
    "explosions": false,
    "leafDecay": false,
    "time": "noon",
    "weather": "clear"
  }
}
```

### Wo die Daten liegen

| Datei | Inhalt |
|---|---|
| `config/townhall.json` | Alle Einstellungen, Orte, Weltregeln, Bauer, Texte |
| `<welt>/data/townhall/players.dat` | Gespeicherte Stellen, Gefangene, Restzeiten |
| `<welt>/data/townhall/onboarding.dat` | Welche Regelversion jeder Spieler akzeptiert hat |
| `<welt>/data/townhall/nicknames.dat` | Nicknamen |
| `<welt>/data/townhall/playtime.dat` | Spielzeiten |
| `<welt>/data/townhall/door_locks.dat` | Abgeschlossene Türen |

Alle Spielerdaten sind nach UUID gespeichert, nicht nach Namen. Ein Namenswechsel bei Mojang ändert also nichts. Die Dateien werden zusammen mit der Welt gespeichert (Autosave und beim Stoppen). Wer die Welt und den Ordner `config/` sichert, hat alles gesichert.

### Update

Server stoppen, die alte Townhall-Jar aus `mods/` löschen, die neue hineinlegen und den Server starten. Config und Daten bleiben erhalten; ältere Datendateien werden weiterhin gelesen.

### Probleme und Lösungen

| Problem | Lösung |
|---|---|
| „... is currently unavailable“ | Falscher Weltname. `/townhall status` zeigt alle Welten; den richtigen Namen mit `/location set <ort> dimension <welt>` eintragen. |
| Spieler landen in der Luft oder in einem Block | An der richtigen Stelle `/townhall setspawn` eingeben |
| Admin-Befehle fehlen | Der Spieler braucht Op-Level 2 (`/op <name>`). |
| „No player was found“ | Für `send` und `return` muss der Spieler online sein. `/builder`, `/nick` und `/playtime` gehen auch offline. |
| Gefangener kann einen Befehl nicht benutzen, der erlaubt sein soll | Den Befehl zu `confinement.allowedCommands` hinzufügen, dann `/townhall reload` |
| Schwierigkeit im Menü stimmt nach `/difficulty` nicht | Die Weltschwierigkeit gilt trotzdem. Einmal die Welt wechseln oder `/townhall reload`, dann stimmt die Anzeige wieder. |
| Bauer kann kein WorldEdit benutzen | Er muss in der Welt stehen, für die er eingetragen ist. `/builder list` prüfen. |
| „Config not reloaded“ | Die Meldung nennt den Fehler in der Datei. Die alte Config bleibt aktiv. |

---

## Alle Befehle auf einen Blick

**Alle Spieler:** `/townhall`, `/townhall return`, `/rules`, `/regeln`, `/rules accept`, `/afk`, `/playtime [spieler|top]`, `/key new <name>`, `/key copy [spieler]`, `/key info`

**Bauer:** `/builder creative`, `/builder survival`, dazu WorldEdit in der eigenen Bauwelt

**Admins:**

| Bereich | Befehle |
|---|---|
| Orte | `/<ort> send <spieler> [minuten]`, `/<ort> return <spieler>`, `/<ort> setspawn`, `/townhall status`, `/townhall debug <spieler>`, `/townhall clearreturn <spieler>`, `/townhall reload` |
| Ortsverwaltung | `/location list`, `info`, `create <id> [prison]`, `delete`, `setspawn`, `set <id> <einstellung> <wert>` |
| Welten | `/townhall difficulty <welt> [wert]`, `/townhall worldrule <welt> [regel] [wert]` |
| Bauer | `/builder add <welt> <spieler>`, `/builder remove <welt> <spieler>`, `/builder list [welt]` |
| Schlüssel | `/key admin`, `/key unlock` |
| Nicknamen | `/nick set <spieler> <nick>`, `/nick reset <spieler>`, `/nick list` |
| Anzeige | `/joinmessage ...`, `/tablist ...`, `/townhall deathsintab on\|off`, `/townhall afktime <minuten>` |
| Regeln | `/rules reset <spieler>` |

---

## Bekannte Grenzen

- **Gemeinsames Inventar:** Bauer können Creative-Items in andere Welten mitnehmen. Getrennte Inventare pro Welt gibt es noch nicht.
- **Abgeschlossene Türen:** Wer an einer Stelle bauen darf, kann dort auch Türen anderer Spieler abschließen, solange sie noch offen sind. Grundstücke gibt es nicht.
- **Nicknamen:** Bei Spielern mit Nicknamen fehlt über dem Kopf die Farbe von Scoreboard-Teams (`/team`). In der Befehlsvervollständigung erscheinen sie als leerer Eintrag; dort den echten Namen tippen.
- **Schwierigkeit pro Welt:** Einige wenige Stellen im Spiel lesen weiterhin die Server-Schwierigkeit, etwa Endermiten aus Enderperlen oder Piglins aus Netherportalen.
- **Wetter:** Welten ohne Wetter (Nether, End) können kein festes Wetter bekommen.
- **Getestet:** Alle Funktionen haben automatische Tests auf einem echten Minecraft-Testserver. Nicht automatisch geprüft sind die Darstellung auf einem echten Client (Nickname über dem Kopf, Tab-Kopfzeile, Zeit und Wetter) sowie WorldEdit mit echten Bauern. Rückmeldungen dazu sind willkommen.

---

## Selbst bauen und entwickeln

```bash
git clone https://github.com/LennardVW/townhallmod.git
cd townhallmod
./gradlew build
```

Die fertige Jar liegt danach in `build/libs/townhall-<version>.jar`. Java 25 muss installiert sein; Gradle selbst lädt der Wrapper herunter.

| Befehl | Zweck |
|---|---|
| `./gradlew build` | Kompilieren, alle Tests ausführen, Jar bauen |
| `./gradlew runGameTest` | Nur die 36 GameTests auf einem echten Testserver ausführen |
| `./gradlew runServer` | Entwicklungsserver in `run/` starten (vorher `run/eula.txt` mit `eula=true` anlegen) |

**Aufbau des Codes** (`src/main/java/dev/townhall/`)

| Paket | Inhalt |
|---|---|
| `command` | Alle Befehle (Brigadier) |
| `config` | Config-Modell, Prüfung und atomares Speichern |
| `teleport` | Teleport, sichere Landeplätze, Gefängnis und Zeitstrafen |
| `dimension` | Regeln pro Welt, feste Zeit und festes Wetter |
| `protection` | Bauschutz, PvP, Bauer-Rechte, WorldEdit-Freigabe |
| `key` | Türschlüssel und Schlösser |
| `nick` | Nicknamen in Chat, Tab-Liste und über dem Kopf |
| `activity` | AFK und Spielzeit |
| `display` | Join-Nachrichten, Tab-Liste, Todeszahl |
| `onboarding` | Begrüßung und Regeln beim ersten Join |
| `storage` | Gespeicherte Spielerzustände |
| `mixin` | Eingriffe in Minecraft, wo es kein Fabric-Event gibt |

Technische Details für Entwickler und KI-Agenten stehen in [AGENTS.md](AGENTS.md). Die wichtigsten Regeln daraus:

- Spieler immer nach UUID speichern, nie nach Namen.
- Kein `@Redirect` in Mixins. Auf dem Server laufen andere Mods, und zwei Redirects auf denselben Aufruf lassen den Server beim Start abstürzen. Stattdessen `@WrapOperation` oder `@ModifyExpressionValue` verwenden.
- Keine Arbeit pro Tick über alle Spieler.
- Jede Änderung bekommt eine neue Versionsnummer in `gradle.properties` und einen Eintrag in [CHANGELOG.md](CHANGELOG.md).

---

## Sicherheit

- Alle Admin-Befehle prüfen das Op-Level auf dem Server. Spieler können sie nicht über den Client umgehen.
- Die Mod öffnet keine Ports und stellt keine eigenen Netzwerkverbindungen her. Die einzige Anfrage nach außen läuft über Minecraft selbst: Beim Eintragen eines Bauers, der noch nie auf dem Server war, fragt der Server die UUID bei Mojang ab.
- Generalschlüssel funktionieren nur für Spieler, die im Moment Admin sind.
- Das Repository enthält keine Serverdaten. Die `.gitignore` schließt Welten, Logs, Op- und Whitelist-Dateien, `server.properties`, Konfigurationen, Schlüssel und Zugangsdaten aus.

Sicherheitslücken bitte nicht als öffentliches Issue melden, sondern direkt an den Maintainer über GitHub.

---

## Lizenz

MIT, siehe [LICENSE](LICENSE).
