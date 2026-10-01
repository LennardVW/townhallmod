# AzubiCraft als Stadtserver

Diese Anleitung gilt ab Townhall **1.15.0**. Die Stadtfunktionen laufen auf dem Fabric-Server; die Spieler verbinden sich weiterhin mit Vanilla-Minecraft 26.3.

Für den Start reichen ein Rathaus, ein Wahlraum, ein Gefängnis und ein paar Läden. Grundstücke bleiben standardmäßig ausgeschaltet. Alle können auf freien Flächen weiter Survival spielen, soweit die bestehenden Weltregeln das erlauben.

## Schnell einrichten

Diese Befehle führt ein Admin im Spiel aus. Ersetze die Namen durch eure Spielernamen.

```text
/role list
/role grant buergermeister Max
/role grant polizei Lena
/role grant haendler Tom
/role grant architekt Anna
/polizei location gefaengnis
/polizei maxminutes 15
```

Die Grundrollen werden beim ersten Laden der neuen Version angelegt. Niemand bekommt automatisch eine Rolle. Die Zuweisung funktioniert auch bei Spielern, die gerade offline sind. Der Server muss den Namen über seinen Namenscache oder den Minecraft-Profilservice auflösen können.

Am gewünschten Rathaus-Eingang: `/townhall setspawn`. Im Gefängnis: `/gefaengnis setspawn`. Mit `/location info gefaengnis` prüfst du den Ort. Das Gefängnis muss `escapable: false` haben; dafür gibt es `/location set gefaengnis escapable false`.

## Stadtrollen

Ein Spieler darf mehrere Rollen haben. Im Chat und in der Tab-Liste erscheint der Präfix der Rolle mit der höchsten Anzeigepriorität. Ein vorhandener Nickname bleibt erhalten. Über dem Kopf wird weiterhin der Nickname angezeigt.

| Rollen-ID | Anzeige | Rechte beim ersten Anlegen |
|---|---|---|
| `buergermeister` | Bürgermeister | Stadtankündigungen |
| `polizei` | Polizei | Ins festgelegte Gefängnis schicken und daraus freilassen |
| `haendler` | Händler | Angebot und Warenbestand des eigenen Ladens verwalten |
| `architekt` | Architekt | Zunächst ein Titel; Baurechte werden für konkrete Grundstücke oder über `/builder` vergeben |
| `wahlhelfer` | Wahlhelfer | Bücher und Ergebnisse aller Wahlen auszählen |

Admins können zusätzlich Wahlhelfer für eine einzelne Wahl benennen. Das ist sinnvoll, wenn jemand nur bei dieser Wahl mitzählen soll.

### Rollen verwalten

Alle Befehle in dieser Tabelle sind Admin-Befehle. `/rolle` ist ein Alias für `/role`.

| Befehl | Zweck |
|---|---|
| `/role list` | Rollen anzeigen |
| `/role info <id>` | Rechte und Mitglieder ansehen |
| `/role create <id> <name>` | Eigene Rolle anlegen |
| `/role delete <id>` | Rolle samt Mitgliedschaften entfernen |
| `/role grant <id> <spieler>` | Rolle vergeben, auch offline |
| `/role revoke <id> <spieler>` | Rolle entziehen, auch offline |
| `/role name <id> <name>` | Anzeigenamen ändern |
| `/role prefix <id> <text>` | Präfix ändern; `-` entfernt ihn |
| `/role priority <id> <0–1000>` | Welche Rolle zuerst angezeigt wird |
| `/role permission <id> <recht> <true|false>` | Einzelnes Stadtrecht hinzufügen oder entfernen |
| `/role display chat <true|false>` | Rollen im Chat anzeigen |
| `/role display tab <true|false>` | Rollen in der Tab-Liste anzeigen |

Beispiel für eine eigene Rolle:

```text
/role create gaertner Stadtgärtner
/role prefix gaertner &2[Gärtner] &r
/role grant gaertner Paul
```

Rollen-IDs verwenden kleine ASCII-Buchstaben, Zahlen, `_` oder `-`, beginnen mit einem Buchstaben und sind höchstens 32 Zeichen lang. Der Anzeigename kann Umlaute und Leerzeichen enthalten. Maximal 100 Rollen sind möglich.

Verfügbare Stadtrechte:

| Recht | Erlaubt |
|---|---|
| `city.announce` | `/stadt announce <text>` |
| `police.jail` | `/polizei jail <spieler> <minuten> <grund>` |
| `police.release` | `/polizei release <spieler>` |
| `shop.manage` | Angebot und Einzahlungen beim eigenen Laden |
| `election.count` | Alle Wahlen auszählen |

Eine Rolle vergibt weder OP noch Minecraft-Befehle, Creative oder WorldEdit. Weitere Rechte müssen bewusst über die dafür vorgesehenen Admin-Befehle vergeben werden.

### Bürgermeister und Polizei

```text
/stadt announce Am Samstag findet die Bürgermeisterwahl statt.
/polizei jail Max 5 Diebstahl im Laden
/polizei release Max
```

Polizeistrafen zählen echte Zeit, solange der betroffene Spieler online ist. Ausloggen pausiert die Restzeit. Standardmäßig sind höchstens 15 Minuten erlaubt. Admins legen Gefängnis und Grenze fest:

```text
/polizei location gefaengnis
/polizei maxminutes 15
```

Die Polizei kann keine Admins einsperren und eine bestehende Haft nicht durch einen neuen Aufruf ersetzen. Freilassen ist auf das konfigurierte Polizeigefängnis begrenzt. Bei einem fehlgeschlagenen Rückteleport bleibt die Haft bestehen. Admins behalten die bisherigen Befehle wie `/gefaengnis return <spieler>`.

## Wahlen mit Büchern im Rathaus

Die Spieler schreiben ihre Wahl selbst in ein Buch. Die Mod liest keinen Kandidaten daraus und zählt nichts automatisch aus. Eure Wahlhelfer entscheiden nach euren Wahlregeln, welche Bücher gültig sind, zählen von Hand und tragen die Zahlen ein.

Die Urne ist ein reserviertes, leeres Fass. Es lässt sich während der Reservierung nicht als normales Fass öffnen. Beim Abgeben wandern die Buchseiten in ein geschütztes Serverarchiv. Nach Wahlschluss holen Wahlhelfer einzelne, schreibgeschützte Buchkopien daraus. Dadurch können Spieler keine Zettel aus dem Fass nehmen und niemand kann abgegebene Bücher nachträglich umschreiben.

### 1. Admin legt die Wahl an

```text
/wahl create buergermeister_1 Bürgermeisterwahl Oktober
/wahl candidate add buergermeister_1 Max
/wahl candidate add buergermeister_1 Lena
```

Für Kandidatennamen mit Leerzeichen verwendest du Anführungszeichen:

```text
/wahl candidate add buergermeister_1 "Anna Beispiel"
```

Im Wahlraum stehen bleiben und den Radius in Blöcken setzen:

```text
/wahl room buergermeister_1 8
```

Der Wahlraum ist eine Kugel um diese Position in genau dieser Dimension. Der Radius beträgt 1 bis 32 Blöcke. Es ist keine Raum- oder Bauverwaltung.

Ein leeres Fass innerhalb des Wahlraums ansehen und reservieren:

```text
/wahl urn buergermeister_1
/wahl helper add buergermeister_1 Tom
/wahl helper add buergermeister_1 Anna
/wahl open buergermeister_1
```

Das Fass muss geladen und höchstens sechs Blöcke entfernt sein. Es kann nicht gleichzeitig Laden und Urne sein. Kandidaten, Raum und Urne lassen sich bis zum Öffnen ändern. Wahlhelfer können bis zur Veröffentlichung hinzugefügt oder entfernt werden, auch offline.

### 2. Spieler stimmt ab

Im Wahlraum:

```text
/wahl list
/wahl info buergermeister_1
/wahl stimmzettel buergermeister_1
```

Das erhaltene Buch öffnen, eine Stimme hineinschreiben, fertig bearbeiten und in die Haupthand nehmen. Zur Urne gehen und rechtsklicken oder eingeben:

```text
/wahl abgeben buergermeister_1
```

Das Buch darf unsigniert bleiben. Signierte Bücher werden ebenfalls angenommen, solange sie der registrierte Zettel sind. Der Autor und der selbst gewählte Buchtitel werden nicht in das Wahlarchiv übernommen.

Pro Spieler-UUID ist genau eine Abgabe je Wahl möglich. Eine Kopie erzeugt keine zweite Stimme. Wer vor der Abgabe ein neues Buch anfordert, macht seinen alten Zettel ungültig. Ein volles Inventar verbraucht keine Wahlberechtigung. Die Registrierung und bereits abgegebene Stimmen werden mit der Welt gespeichert.

### 3. Wahl schließen und manuell zählen

Ein Admin beendet die Stimmabgabe:

```text
/wahl close buergermeister_1
```

Wahlhelfer sehen die Buchanzahl und holen jedes Buch über seine Nummer:

```text
/wahl info buergermeister_1
/wahl buch buergermeister_1 1
/wahl buch buergermeister_1 2
```

Die Nummer bezeichnet das Buch im Archiv. Die Reihenfolge wird während der Abgabe zufällig gemischt und ist erst nach Wahlschluss lesbar. Kopien haben einen neutralen Autor und können nicht beschrieben werden. Eine erneut abgeholte Nummer ist dasselbe Buch, keine zusätzliche Stimme. Teilt die Nummern beim gemeinsamen Auszählen untereinander auf.

Anschließend Zahlen eintragen, hier als Beispiel für zwölf abgegebene Bücher:

```text
/wahl tally buergermeister_1 Max 7
/wahl tally buergermeister_1 Lena 4
/wahl invalid buergermeister_1 1
```

Jeder Aufruf ersetzt den bisherigen Wert. Auch Kandidaten mit null Stimmen und null ungültige Stimmen müssen ausdrücklich eingetragen werden. Alle Kandidatenzahlen zusammen mit den ungültigen Stimmen müssen genau die Zahl der abgegebenen Bücher ergeben.

Ein Admin veröffentlicht das vollständige Ergebnis:

```text
/wahl publish buergermeister_1
/role grant buergermeister Max
```

Die Rolle des Gewinners wird bewusst von einem Admin vergeben. Alte Amtsinhaber bei Bedarf mit `/role revoke` absetzen. Nach der Veröffentlichung sind die Zahlen unveränderlich.

### Weitere Wahlbefehle

| Befehl | Wer? | Zweck |
|---|---|---|
| `/wahl list`, `/wahl info <id>` | Alle | Wahlen und Status sehen; Auszählung vor Veröffentlichung nur für Helfer |
| `/wahl candidate remove <id> <kandidat>` | Admin | Kandidaten vor Öffnung entfernen |
| `/wahl helper remove <id> <spieler>` | Admin | Helfer dieser Wahl entfernen |
| `/wahl cancel <id>` | Admin | Unveröffentlichte Wahl abbrechen |
| `/wahl releaseurn <id>` | Admin | Nicht mehr geöffnete Urne freigeben; Archiv und Ergebnis behalten |

Ein freigegebenes Fass lässt sich wieder abbauen oder für die nächste Wahl reservieren. Veröffentlichte Wahlen bleiben gespeichert. Es können bis zu 64 Wahlen, 32 Kandidaten und 4096 teilnehmende UUIDs pro Wahl gespeichert werden. Jeder kann ohne eine Bürgerrolle teilnehmen.

**Zum Wahlgeheimnis:** Das Archiv speichert nur die Buchseiten. Die Liste der UUIDs, die abgestimmt haben, wird getrennt davon gespeichert; Buchinhalte und Abgaben stehen nicht im Änderungsprotokoll. Was Spieler selbst in ihre Bücher schreiben oder anderen erzählen, kann die Mod nicht anonymisieren. Admins mit Dateizugriff sind weiterhin eine Vertrauensperson. Das System ist für eure Spielwahl gedacht.

## Spielerläden mit Smaragden oder Diamanten

Ein Laden verkauft ein Angebot an einem reservierten Fass. Warenbestand und Einnahmen werden auf dem Server gespeichert; das Fass bleibt als Markierung leer. Käufer müssen höchstens sechs Blöcke entfernt sein. Der Verkäufer kann offline bleiben.

### Laden einrichten

Ein Admin sieht ein leeres Fass an:

```text
/shop create holzladen Tom
/role grant haendler Tom
```

Tom stellt sich an sein Ladenfass, nimmt beispielsweise Eichenstämme in die Haupthand und legt das Angebot fest:

```text
/shop offer holzladen 16 2 emerald
/shop stock holzladen 128
```

Das bedeutet: 16 Eichenstämme kosten zwei Smaragde. `diamond` verwendet Diamanten. `stock` nimmt die Waren aus Toms eigenem Inventar. Ohne Zahl lagert `/shop stock holzladen` alle exakt passenden Waren aus seinem Inventar ein.

Das Angebot vergleicht auch Gegenstandsdaten, zum Beispiel Namen oder Verzauberungen. Für andere Waren muss der alte Bestand zuerst vollständig abgeholt werden. Preis und Paketgröße derselben Ware können geändert werden.

### Kaufen und Einnahmen abholen

```text
/shop list
/shop info holzladen
/shop buy holzladen
/shop buy holzladen 3
```

Ein Kauf liefert ein Angebotspaket; die optionale Zahl kauft entsprechend viele Pakete. Bezahlung, Warenbestand und Platz im Inventar werden vorab geprüft. Fehlt etwas, bleiben Inventar und Laden unverändert. Es werden unveränderte Vanilla-Smaragde beziehungsweise Diamanten als Bezahlung verwendet.

Der Eigentümer holt am Fass seine Waren oder Einnahmen ab:

```text
/shop withdraw holzladen 32
/shop collect holzladen
/shop collect holzladen emerald 64
```

Ohne Zahl holt `withdraw` den ganzen Bestand ab. `collect` ohne Währung holt beide Währungen ab; für ein volles Inventar können kleinere Mengen einzeln abgeholt werden. Die Zahl ist die Anzahl der Gegenstände, keine Zahl von Stacks.

Die Händlerrolle wird für neue Angebote und Einzahlungen benötigt. Eigentümer können nach einem Rollenentzug vorhandene Waren und Einnahmen weiterhin abholen. Kaufen, Einzahlen und Angebote sind im Creative-Modus gesperrt. Schlüssel, Wahlzettel und darin verschachtelte Gegenstände sind keine zulässige Handelsware.

### Admin-Verwaltung und Wiederherstellung

| Befehl | Zweck |
|---|---|
| `/shop enabled <true|false>` | Handel an- oder ausschalten; vorhandene Werte bleiben abholbar |
| `/shop owner <id> <spieler>` | Leeren Laden einem neuen Eigentümer zuweisen, auch offline |
| `/shop delete <id>` | Laden ohne Bestand oder Einnahmen entfernen; das Fass bleibt |
| `/shop recover <id> stock [anzahl]` | Admin holt Waren in sein eigenes Inventar |
| `/shop recover <id> earnings [diamond|emerald [anzahl]]` | Admin holt Einnahmen in sein eigenes Inventar |

`/shop change-owner` ist ein Alias für `owner`. Eine Wiederherstellung landet im Inventar des ausführenden Admins, der die Werte anschließend an den Eigentümer weitergeben muss. Sie wird protokolliert. Für normale Abholungen muss das ursprüngliche, leere Ladenfass vorhanden sein. Für Admin-Wiederherstellungen ist kein intaktes Fass erforderlich.

Ein geladener Laden mit echten Gegenständen in seinem Markierungsfass wird nicht einfach gelöscht. Erst die Ursache klären und Werte erhalten. Ein Laden darf höchstens 1728 Warenstücke enthalten; Angebote haben 1 bis 64 Warenstücke, Preise 1 bis 1.000.000 Währungseinheiten.

## Grundstücke bei Bedarf einschalten

Standardmäßig ist `city.claimsEnabled` false. Ihr könnt zunächst frei bauen und später nur Rathaus, Geschäfte oder einzelne Häuser schützen. Vorbereitete Grundstücke wirken erst nach dem Einschalten.

Ein Admin geht zu zwei gegenüberliegenden Ecken. Die Höhe ist egal: Der Bereich umfasst Keller bis Himmel.

```text
/plot pos1
/plot pos2
/plot create haus_max Max
/plot enabled true
```

Alternativ Koordinaten angeben: `/plot pos1 100 64 100`, `/plot pos2 115 64 115`. Konsolenbefehle mit Koordinaten sind möglich; die Dimension des Befehlskontexts gilt. Nach einem Weltwechsel beide Punkte neu setzen.

| Befehl | Wer? | Zweck |
|---|---|---|
| `/plot info [id]`, `/plot list` | Alle | Bereich und Schutzstatus sehen |
| `/plot enabled <true|false>` | Admin | Grundstücksschutz global umschalten |
| `/plot pos1 [x y z]`, `/plot pos2 [x y z]` | Admin | Ecken wählen |
| `/plot create <id> [spieler]` | Admin | Bereich anlegen, optional mit Eigentümer |
| `/plot owner <id> <spieler>` | Admin | Eigentümer ändern |
| `/plot unowned <id>` | Admin | Eigentümer entfernen; geschützte öffentliche Fläche |
| `/plot trust <id> <spieler>`, `/plot untrust <id> <spieler>` | Admin | Mitbauer hinzufügen oder entfernen, auch offline |
| `/plot role <id> <rolle>`, `/plot unrole <id> <rolle>` | Admin | Bestimmte Stadtrolle zum Bauen zulassen |
| `/plot publicuse <id> <true|false>` | Admin | Besucher dürfen Türen, Knöpfe und Arbeitsblöcke benutzen |
| `/plot delete <id>` | Admin | Bereichsdefinition entfernen; Gebäude bleiben stehen |

Beispiel für Architekten am Rathaus:

```text
/plot pos1
/plot pos2
/plot create rathaus
/plot role rathaus architekt
/role grant architekt Anna
```

Anna darf dort in Survival bauen, auch wenn die Welt sonst `build false` hat. Mitbauer erhalten auch Containerzugriff. Besucher können bei `publicuse true` Türen und Arbeitsblöcke benutzen; Kisten und Fässer bleiben privat. Schlüssel bleiben zusätzlich erforderlich, wenn eine Tür abgeschlossen ist.

Außerhalb der Grundstücke gelten die normalen Weltregeln. Admins können in allen Grundstücken bauen. Läden und Wahlurnen bleiben als eigene Markierungen geschützt, selbst für Admins; erst ihren Verwaltungsbefehl zum Freigeben verwenden.

Grundstücke dürfen sich nicht überlappen und sind höchstens 256 × 256 Blöcke groß. Es gibt maximal 2000 Bereiche und insgesamt 32.000 im Suchindex erfasste Grundstücks-Chunks. Automatisierte Übergänge zwischen Bereichen werden für Kolben, Flüssigkeiten und Trichter begrenzt; Kupfergolems bedienen dort keine Container. Explosionen und Feuerverbrennen sind in Grundstücken blockiert.

WorldEdit: Sobald Grundstücksschutz aktiv ist, entzieht Townhall Nicht-Admins seine WorldEdit-Freigabe. Es gibt noch keine Integration, die jede WorldEdit-Operation auf erlaubte Grundstücke begrenzt. Andere Mods oder externe Rechteanbieter können eigene Schreibwege haben; deren Verhalten ist nicht vollständig abgedeckt. OPs und Serverbefehle können Bereiche bewusst ändern. Das Protokoll hilft beim Nachsehen.

## Änderungsprotokoll für Admins

Das Protokoll erfasst erfolgreiche synchrone Blockänderungen durch Spieler und Befehle, neue Verwaltungsaktionen, gespeicherte Config-Änderungen, Türschlösser, Shop-Transaktionen und manuell eingetragene Wahlergebnisse. Es enthält Zeitpunkt, handelnde UUID/Name, Aktion und soweit verfügbar Position sowie alten und neuen Blockzustand. Chat und Stimmzettelseiten werden nicht gespeichert.

```text
/audit inspect
/audit near 10
/audit near 10 30
/audit player Max 20
/audit status
```

`inspect` zeigt die letzten Änderungen am angesehenen Block in höchstens sechs Blöcken Entfernung. `near` durchsucht das gespeicherte Protokoll für die aktuelle Position und Dimension, keine Weltblöcke. Der Radius ist auf 64 und die Ausgabe auf 100 Einträge begrenzt. `player` funktioniert auch offline, wenn der Name auflösbar ist.

```text
/audit enabled false
/audit enabled true
/audit limit 50000
```

Standardmäßig bleiben 50.000 Einträge erhalten. Einstellbar sind 100 bis 100.000. Werden es mehr, entfallen die ältesten Einträge. Eine kleinere Grenze entfernt ältere überzählige Einträge sofort. Ausgeschaltetes Logging lässt bestehende Einträge lesbar.

Es gibt keinen Rollback-Befehl. Automatische Naturänderungen ohne zugeordneten Spieler, Blockinventare/NBT, spätere Aufgaben und asynchrone oder direkt in Chunks schreibende WorldEdit-Operationen werden nicht vollständig erfasst. Das Protokoll ersetzt eure Welt-Backups nicht.

## Config, Speichern und Updates

Die Admin-Befehle ändern ihre Einstellungen sofort und speichern sie in `config/townhall.json`. Der neue Abschnitt sieht standardmäßig so aus:

```json
"city": {
  "claimsEnabled": false,
  "shopsEnabled": true,
  "rolesInChat": true,
  "rolesInTab": true,
  "policeLocation": "gefaengnis",
  "policeMaxMinutes": 15,
  "auditEnabled": true,
  "auditMaxEntries": 50000
}
```

Bestehende Configs ohne `city` bekommen diese Standardwerte beim Laden. Falls die Config ungültig ist, bewahrt Townhall die bisherige aktive Config und überschreibt die fehlerhafte Datei nicht. Datei reparieren und `/townhall reload` ausführen.

Die Definitionen und Spielerdaten werden mit der Minecraft-Welt gespeichert:

| Datei unter `<welt>/data/townhall/` | Inhalt |
|---|---|
| `roles.dat` | Rollendefinitionen und Mitgliedschaften nach UUID |
| `plots.dat` | Grundstücke, Eigentümer, Mitbauer und Rollenfreigaben |
| `elections.dat` | Wahlen, Registrierung, anonyme Buchseiten und Handzählung |
| `shops.dat` | Eigentümer, Angebote, Bestand und Einnahmen |
| `audit.dat` | Begrenztes Änderungsprotokoll |

Für ein Update: Server sauber stoppen, Welt und `config/` sichern, die alte Townhall-Jar ersetzen, neu starten. Es darf nur eine Townhall-Jar in `mods/` liegen. Die Mod nutzt Minecraft-Autosave und den normalen Shutdown; ein harter Prozessabbruch zwischen zwei Speicherungen ist keine garantierte Transaktion zwischen Welt-, Laden- und Spielerdateien.

## Häufige Fragen

| Problem | Was du prüfen kannst |
|---|---|
| Architekt kann kein Creative verwenden | Eine Stadtrolle ist kein `/builder`-Recht. Für Creative bewusst `/builder add <dimension> <spieler>` vergeben. |
| Händler kann seinen Laden nicht bearbeiten | Eigentümer, Händlerrolle, Nähe zum leeren Fass, Survival-Modus und `shopsEnabled` prüfen. |
| Einnahmen passen nicht ins Inventar | Eine kleine Menge mit Währung und Zahl abholen. |
| Urne lässt sich nicht öffnen | Das ist beabsichtigt. Stimmzettel abgeben; nach Schluss Bücher mit `/wahl buch` holen. |
| Stimmzettel ist ungültig | Das neueste registrierte Buch des eigenen Spielers und der richtigen Wahl verwenden. |
| Veröffentlichung scheitert | Alle Kandidaten und ungültige Stimmen ausdrücklich zählen; Summe muss der Buchanzahl entsprechen. |
| Fass bleibt nach Wahl geschützt | `/wahl releaseurn <id>` nach Wahlschluss ausführen. |
| Grundstück schützt nichts | `/plot enabled true` und `/plot info` prüfen. |
| Bauer verliert WorldEdit | Aktivierter Grundstücksschutz sperrt Townhalls WorldEdit-Freigabe für Nicht-Admins. |
| Ein alter Ortsbefehl heißt etwa `role`, `wahl` oder `shop` | Dieser Name gehört jetzt zu einem festen Befehl. Der Ort und seine gespeicherten Daten bleiben erhalten. Als Admin den Ortsbefehl mit `/location set <ort-id> command <anderer-name>` umbenennen. |

Wirtschaft und Creative: Builder haben weiterhin ein gemeinsames Inventar mit Survival. Eine Sperre während Creative verhindert nicht, dass jemand dort erzeugte Gegenstände später in Survival benutzt. Wenn euch eine faire Währung wichtig ist, gebt Creative nur vertrauenswürdigen Bauhelfern und trennt diese Tätigkeit organisatorisch. Getrennte Inventare sind in dieser Version nicht enthalten.
