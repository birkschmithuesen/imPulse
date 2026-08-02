# Preset-System im Web-UI

Stand 2026-07-30, Branch `integration/webui-presets`. Grundlage: `webui_preset_brief.md`
(Birks Auftrag) und `docs/superpowers/specs/2026-07-30-preset-system-design.md`
(die Java-Seite, die hier nur benutzt und nicht angefasst wird).

## Ziel

Presets im Browser laden und speichern, ohne die Kommandozeile. Bisher geht das
nur per OSC von Hand oder ueber den Scheduler.

## Was schon da ist

Java (unveraendert): `PresetManager` hoert auf Port 8001 auf `/preset/load <name>`,
`/preset/save <name>` und `/preset/next`. `PresetStore` schreibt und liest
`data/presets/<name>.txt` im **gleichen Sechs-Spalten-Tab-Format wie
`remoteSettings.txt`** und laesst nur Namen aus `a-z 0-9 _ -` mit Laenge 1..64 zu
(Pfad-Traversal-Schutz, Grossbuchstaben ausgeschlossen wegen Windows-Case-Kollision).

Python (`webui/server.py`, Flask + Vanilla JS): liest `remoteSettings.txt`, baut
daraus Regler, schickt Aenderungen per OSC an `127.0.0.1:8001`. `parse_settings()`
liest genau das Format, in dem auch Presets liegen. `OscSender` kann bisher nur
`int` und `float`.

Zwei Gegebenheiten, die den Entwurf bestimmen:

- **Der Server hat Dateisystemzugriff auf `data/presets/`** — er laeuft auf
  derselben Maschine wie imPulse. Es braucht deshalb keinen OSC-Rueckkanal, um
  die Preset-Liste zu erfahren, und auch keinen, um nach dem Laden die Werte zu
  kennen.
- **`/preset/save` ist asynchron** — imPulse schreibt die Datei erst im naechsten
  `draw()`-Durchlauf. Wer sofort danach den Ordner liest, sieht sie
  moeglicherweise noch nicht.

## Architektur

```
Browser  --HTTP:8080-->  webui/server.py  --OSC:8001-->  imPulse
                              |
                              +-- liest data/presets/*.txt direkt vom Dateisystem
```

Der Server schreibt **nie** in `data/presets/`. Das Schreiben bleibt allein bei
imPulse — nur dort ist bekannt, welche Werte gerade tatsaechlich laufen.

## Endpoints

Drei getrennte Routen statt einer `/api/preset` mit `action`-Feld: verschiedene
Methoden, verschiedene Fehlerfaelle, verschiedene Antworten.

### `GET /api/presets`

```json
{"ok": true, "presets": ["hang_drum_slow", "standby"], "dir": "…/data/presets", "error": null}
```

`list_presets(dir)` spiegelt `PresetStore.list()`: nur regulaere Dateien mit
Endung `.txt`, Endung abgeschnitten, alphabetisch sortiert, Namen ohne gueltige
Form uebergangen (sie liessen sich ohnehin nicht laden). Ist der Ordner nicht
lesbar, ist `presets` leer und `error` nennt den Grund — kein HTTP-Fehler, das
UI soll auch dann bedienbar bleiben.

### `POST /api/preset/load` — Body `{"name": "standby"}`

1. Name validieren (`400` bei ungueltig).
2. Datei mit `parse_settings()` lesen (`404`, wenn sie fehlt; `400` bei
   unlesbarem Inhalt).
3. `/preset/load <name>` per OSC an imPulse.
4. Fuer jede Adresse, die auch in `remoteSettings.txt` steht: Wert mit der
   **aktuellen** Parameter-Range `coerce()`n und in den `ParameterStore`
   schreiben.

```json
{"ok": true, "name": "standby",
 "values": {"/net/impulse/speed": 16, "…": 0.001},
 "unknown": ["/foo/bar"],
 "outOfRange": [{"address": "/net/impulse/speed", "value": 160, "shown": 100}]}
```

Die Werte gehen **nicht** einzeln als OSC raus — das Anwenden macht imPulse
selbst, und dort gelten die Code-Grenzen. Das JS setzt die Regler still per
`control.set(wert, true)` (kein `onChange`, also kein zweites OSC) und laesst sie
per `control.flash()` kurz aufleuchten, genau wie bei der Speed-Kopplung.

Zwei Sonderfaelle werden gemeldet statt verschluckt:

- `unknown` — Adressen aus dem Preset, die es in `remoteSettings.txt` nicht gibt
  (Preset und Dump aus verschiedenen Codestaenden).
- `outOfRange` — Werte ausserhalb der verengten UI-Range (`UI_RANGE_OVERRIDES`).
  Der Regler klemmt sichtbar — `apply()` in `app.js` tut das ohnehin —, und die
  Statuszeile nennt Adresse, echten und angezeigten Wert. Bei den zwei
  vorhandenen Presets tritt das nicht auf; ein Preset aus der Zeit vor der
  Range-Verengung wuerde es ausloesen, und dann darf der Regler nicht
  stillschweigend etwas anderes behaupten als der Sketch faehrt.

### `POST /api/preset/save` — Body `{"name": "abendshow"}`

1. Name validieren (`400` bei ungueltig).
2. `mtime` einer eventuell vorhandenen Datei merken.
3. `/preset/save <name>` per OSC.
4. Bis zu **1 s** in 50-ms-Schritten warten, bis `<name>.txt` existiert **und**
   eine andere `mtime` hat als vorher gemerkt. Ohne den `mtime`-Vergleich waere
   ein Ueberschreiben nicht von "nichts passiert" zu unterscheiden.

```json
{"ok": true, "name": "abendshow", "overwritten": false,
 "presets": ["abendshow", "hang_drum_slow", "standby"]}
```

Erscheint die Datei nicht: `{"ok": false, "error": "imPulse hat … nicht geschrieben — laeuft der Sketch?"}`
mit Status `504`. Das ist der haeufigste Fehlerfall im Betrieb (Web-UI laeuft,
imPulse nicht) und soll deshalb eine klare Meldung geben, kein stilles Nichts.

Ein bestehendes Preset wird ohne Rueckfrage ueberschrieben (Vorgabe aus dem
Brief: keine Bestaetigungsdialoge). `overwritten` sagt es hinterher ehrlich in
der Statuszeile.

## Namensvalidierung

`valid_preset_name(name) -> Optional[str]` in `server.py`, wortgleich zu
`PresetStore.isValidName()`: Laenge 1..64, nur `a-z`, `0-9`, `_`, `-`. Rueckgabe
ist die Fehlermeldung oder `None`.

Drei Ebenen, absichtlich redundant:

| Ebene | Zweck |
|---|---|
| JS | Speichern-Knopf gesperrt, Grund sichtbar — Bedienkomfort |
| Python | `400` mit Begruendung — kein sinnloses OSC ins Netz |
| Java | **Autoritaet** — Pfad-Traversal-Schutz |

## OSC-String-Argument

`build_osc_message()` bekommt einen `str`-Zweig: Typtag `,s`, UTF-8, mit
mindestens einem Nullbyte auf 4 Byte aufgefuellt (`_osc_string()` gibt es schon).
`OscSender.send()` reicht Strings durch. `/preset/*` hat einen fuehrenden
Schraegstrich, laeuft im Betrieb also ueber `python-osc`; der eigene Encoder
bleibt der byteweise gepruefte Gegenpart wie bei `int` und `float`.

## UI

Neue Sektion **Presets** ueber den Parametergruppen, im Markup von `index.html`
fest verdrahtet — anders als die Parametergruppen, die aus der Datei kommen und
deshalb im JS gebaut werden.

```
Presets
[ Dropdown: standby ▾ ]  [ Laden ]
[ Textfeld: neuer Name ]  [ Speichern ]
```

- Dropdown wird beim Seitenaufruf aus dem Bootstrap-JSON gefuellt und nach jedem
  erfolgreichen Speichern aus der Antwort aktualisiert. Kein Websocket, kein
  Polling.
- Meldungen laufen durch das vorhandene `setStatus()`.
- Ist der Ordner leer, steht im Dropdown "keine Presets" und **Laden** ist
  gesperrt.

Nicht im Umfang (bewusst, laut Brief): Loeschen, Vorschau, Diff,
Bestaetigungsdialoge, Live-Refresh. Der bestehende **Neu laden**-Knopf bleibt
unveraendert — er liest `remoteSettings.txt` neu, ein anderer Vorgang.

## Konfiguration

| Option / Umgebungsvariable | Vorgabe |
|---|---|
| `--presets` / `IMPULSE_PRESETS` | `dirname(--settings)/presets` |

Die Ableitung trifft den Normalfall: wer den Server ausserhalb des Repos startet,
gibt heute schon `--settings` mit und bekommt den Preset-Ordner damit
automatisch richtig. Der Schalter bleibt als Notausgang, falls die Ordner je
auseinanderliegen.

## Fehlerbehandlung

| Fall | Verhalten |
|---|---|
| Ungueltiger Name | `400`, Grund in der Statuszeile; JS faengt es schon vorher ab |
| Preset-Ordner fehlt/unlesbar | leere Liste + `error`, UI bleibt bedienbar |
| Preset-Datei fehlt beim Laden | `404`, Liste wird neu geholt (jemand hat sie geloescht) |
| Preset-Datei unlesbar | `400` mit Zeilenhinweis aus `parse_settings()` |
| imPulse laeuft nicht (Speichern) | `504` nach 1 s, klare Meldung |
| imPulse laeuft nicht (Laden) | kein Fehler erkennbar — UDP ist fire-and-forget. Die Regler zeigen dann den Preset-Stand, den der Sketch nicht faehrt. Hinnehmbar: derselbe blinde Fleck gilt fuer jeden Regler im UI |

## Tests

`webui/test_webui.py` (Standardbibliothek allein, kein Flask, kein python-osc):

- `valid_preset_name`: gueltig, leer, 64 Zeichen, 65 Zeichen, Grossbuchstabe,
  `/`, `.`, `..`, Leerzeichen, Umlaut
- `list_presets` gegen ein `tempfile`-Verzeichnis: Sortierung, `.txt` ab,
  Nicht-`.txt` ignoriert, Unterordner ignoriert, ungueltiger Name uebergangen,
  fehlender Ordner
- `build_osc_message` mit String: byteweise, plus Vergleich mit den
  `python-osc`-Referenzbytes wie bei den bestehenden Encoder-Tests
- Umsetzen einer geparsten Preset-Datei in Store-Werte: bekannte Adressen
  gesetzt, `unknown` gefuellt, `outOfRange` erkannt, Int bleibt Int

`test/run.sh` (Java) bleibt unveraendert gruen — an Java aendert sich keine Zeile.

## Offene Frage (nicht entschieden)

**Loeschen von Presets** fehlt komplett — auch in Java gibt es kein
`/preset/delete`. Ein Preset loszuwerden heisst derzeit: die Datei auf dem Laptop
von Hand loeschen. Ein Loeschen-Knopf im UI waere technisch einfach (der Server
hat Schreibrechte auf den Ordner), wuerde aber das Prinzip brechen, dass nur
imPulse in `data/presets/` schreibt. Bewusst offengelassen fuer eine spaetere
Entscheidung.

> **Entschieden am 2026-08-02** (Branch `feature/preset-tagging-ui`): es gibt
> ein Loeschen im UI, und es geht **ueber imPulse**. `PresetStore.delete()`
> und der OSC-Befehl `/preset/delete <name>` sind neu in Java;
> `POST /api/preset/delete` schickt ihn und wartet auf das *Verschwinden* der
> Datei (504 statt behaupteter Loeschung, analog zum mtime-Polling beim
> Speichern). Damit bleibt das Prinzip ungebrochen, statt es fuer die Bequem-
> lichkeit eines `os.remove()` aufzugeben. Im Browser steht davor ein
> `window.confirm()` — der einzige Bestaetigungsschritt im ganzen UI, weil
> Loeschen anders als Ueberschreiben nicht rueckgaengig zu machen ist.
> Details: CLAUDE.md, Abschnitt „Web-UI".
