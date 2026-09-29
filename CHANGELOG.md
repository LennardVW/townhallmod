# Changelog – Townhall

Versionen: **x.y.z** – `x` große Umbauten, `y` neue Funktionen, `z` Fehlerbehebungen.

## 1.12.1
- Fix: Befehle auf Schildern laufen mit den Rechten des Spielers. Ein Schild mit `/townhall return` holt keine Gefangenen mehr heraus, öffnet keine Admin-Orte und umgeht weder Wartezeit noch die Befehlssperre.
- Fix: Ist `config/townhall.json` fehlerhaft (beim Start oder nach `/townhall reload`), überschreiben Befehle die Datei nicht mehr, sondern melden „Config not saved“. Schreibfehler werden ebenfalls gemeldet statt „gespeichert“.
- Fix: `/townhall reload` lehnt eine Datei ab, in der ein Ort mit Gefangenen fehlt, und Befehlsnamen, die schon Minecraft oder eine andere Mod benutzt (etwa `/list`). Beim Start bekommt so ein Ort keinen Befehl mehr, statt den fremden zu kapern.
- Fix: Onboarding aus oder Regelversion gesenkt: wartende Spieler sind sofort frei. `/rules accept` beendet das Warten immer.
- Fix: Wer die Regeln noch nicht akzeptiert hat und ins Gefängnis geschickt wird, springt nicht mehr jede Sekunde zwischen Gefängnis und Join-Stelle hin und her.
- Fix: Orte in der Oberwelt oder der Notfall-Welt werden abgelehnt (`/location create`, `setspawn`, `set dimension`). Steht so ein Ort schon in der Config, lädt sie trotzdem; das Log warnt.
- Fix: Wird ein Ort in eine andere Welt verlegt, behalten Besucher ihre gespeicherte Rückkehrstelle.
- Fix: `/location set <ort> escapable true` lässt alle frei, die dort gerade eingesperrt sind.
- Fix: Ein einzelnes `%` in Texten (z. B. `alreadyHereMessage`) führt nicht mehr zu Fehlern. Die Config und `/location set` prüfen die Platzhalter; ein Prozentzeichen schreibt man als `%%`. Fehlende Texte in der Config werden gemeldet.
- Fix: Wer von einem Admin an einen Ort mit `adminOnly` geschickt wurde, sieht dort `/<ort> return`.

## 1.12.0
- Neue Welt-Regeln: `mobs`, `fire`, `explosions`, `leafDecay` (`/townhall worldrule <welt> <regel> false`).

## 1.11.0
- Eigene Join-, Leave- und Erst-Join-Nachrichten, auch persönlich pro Spieler (`/joinmessage`).
- Kopf- und Fußzeile in der Tab-Liste mit Platzhaltern (`/tablist`), Standard: AzubiCraft.

## 1.10.0
- AFK-Anzeige in der Tab-Liste (automatisch nach 5 Minuten oder `/afk`), `/townhall afktime`.
- Spielzeit ohne AFK-Zeit: `/playtime`, `/playtime <spieler>`, `/playtime top`.

## 1.9.0
- Türschlüssel: `/key new <name>`, Rechtsklick verknüpft eine Tür, nur mit passendem Schlüssel im Inventar zu öffnen, `/key copy [spieler]`, Admin-Schlüssel `/key admin`, `/key info`, `/key unlock`. Redstone und Dorfbewohner öffnen abgeschlossene Türen nicht.

## 1.8.2
- Über dem Kopf steht jetzt der volle Nickname mit bis zu 32 Zeichen und Farben (vorher max. 16).

## 1.8.1
- Fix: Der Nickname steht jetzt auch **über dem Kopf** des Spielers (max. 16 Zeichen). Wechselt sofort nach `/nick`, die Todeszahl in der Tab-Liste bleibt erhalten.

## 1.8.0
- Nicknames, die nur Admins setzen: `/nick set <spieler> <nickname>`, `/nick reset <spieler>`, `/nick list`. Angezeigt im Chat und in der Tab-Liste, Farben mit `&`, auch für Offline-Spieler.

## 1.7.0
- Tode in der Tab-Liste: rote Zahl neben jedem Spieler (inkl. früherer Tode aus der Statistik). `/townhall deathsintab on|off`, Config `deathsInTab`.

## 1.6.0
- **Bauer**: `/builder add|remove <welt> <spieler>`, `/builder list` (Admin). Geht auch für Spieler, die offline sind.
- Bauer dürfen in ihrer Welt trotz `build false` bauen, sich selbst `/builder creative` / `/builder survival` geben und WorldEdit benutzen (ohne Admin-Befehle).
- Außerhalb ihrer Welt automatisch Survival und kein WorldEdit.

## 1.5.0
- Orte komplett per Befehl verwalten (nur Admins): `/location list | info | create [prison] | delete | setspawn | set <id> <einstellung> <wert>`.
- Neue, umbenannte und gelöschte Orte gelten sofort – kein `/reload` mehr nötig (auch nicht nach `/townhall reload`).

## 1.4.1
- Fix: Server stürzte beim Start ab, wenn **mc-worlds** (oder eine andere Welten-Mod) installiert ist („@Redirect conflict“). Townhall hängt sich jetzt so ein, dass beide Mods zusammen laufen.

## 1.4.0
- Feste Zeit pro Welt: `/townhall worldrule <welt> time noon` (`day`, `noon`, `night`, `midnight`, Zahl `0`–`23999`, `default`).
- Festes Wetter pro Welt: `/townhall worldrule <welt> weather clear` (`clear`, `rain`, `thunder`, `default`).
- Andere Welten bleiben normal. Auch in der Config unter `dimensions` (`time`, `weather`).

## 1.3.0
- Regeln pro Welt: `pvp`, `build` (Bau- und Abbauschutz, Admins ausgenommen), `hunger`, `fallDamage` – per `/townhall worldrule` oder Config (`dimensions`).
- Großer Begrüßungstitel pro Ort (`title`, `subtitle`).
- Erster Join: Begrüßung, Tutorial und Regeln nur für den neuen Spieler; erst nach **[Regeln akzeptieren]** darf man spielen. `/rules`, `/regeln`, `/rules reset <spieler>`, `rulesVersion`.

## 1.2.1
- Fix: Admins wurden eingesperrt, wenn sie sich selbst ins Gefängnis geschickt haben. Admins werden jetzt nie eingesperrt.
- Fix: `/<ort> return <spieler>` lässt Spieler ohne gemerkte Stelle frei (Spawn) statt nur eine Fehlermeldung zu zeigen.

## 1.2.0
- `escapable` pro Ort: nicht ausbrechbare Orte (Befehlssperre mit Whitelist, Zurückholen, Respawn am Ort).
- Zeitstrafen: `/<ort> send <spieler> <minuten>` – zählt nur Online-Zeit, übersteht Neustarts, Restzeit in der Actionbar.
- Schwierigkeit pro Welt: `/townhall difficulty <welt> <stufe>`.

## 1.1.0
- Beliebig viele Orte in der Config (`locations`), jeder mit eigenem Befehl.
- Admin-Orte (`adminOnly`), Beispiel-Gefängnis `gefaengnis`.
- Wechsel zwischen Orten behält die ursprüngliche Stelle.

## 1.0.0
- `/townhall` und `/townhall return` mit exakter Rückkehr, Sicherheitsprüfung, Fallback, Cooldown, Admin-Befehle.
