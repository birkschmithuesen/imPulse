# Sprechende Regler-Beschriftungen im Web-UI — Umsetzungsplan

> **Fuer agentische Bearbeiter:** Ausfuehrung Task fuer Task, Tests nach jedem Task.

**Ziel:** Jeder Regler in allen sieben Tabs traegt einen deutschen Klartext-Titel
und (ausser bei selbsterklaerenden) eine ein- bis zweisaetzige Erklaerung; die
OSC-Adresse bleibt sichtbar, aber als kleinste, gedimmte Zeile.

**Architektur:** Die Zuordnung Adresse → (Titel, Erklaerung) steht **auf der
Server-Seite** (`webui/server.py`), nicht in `app.js` — dasselbe Argument wie
bei `TAB_RULES` und `TREE_HELP`: es ist eine inhaltliche Aussage ueber die
Java-Seite, und nur dort ist sie ohne jsdom pruefbar. `app.js` rendert nur, was
der Server liefert (`param.label`, `param.help`, `param.address`).

**Tech-Stack:** Python 3 (stdlib + Flask), Vanilla JS, kein Build-Schritt.

## Global Constraints

- Kein Push, kein Deploy, kein Merge. Nur dieser Worktree (`~/github/imPulse-labels`).
- `python3 -m pytest webui/test_webui.py -q` muss gruen bleiben.
- Keine neue Abhaengigkeit — `webui/` kommt weiter ohne Node/npm aus.
- Deutsch, ASCII-nah (Umlaute als `ae/oe/ue` in Python-Quelltexten wie bisher;
  im UI-Text sind Umlaute erlaubt, die Datei ist UTF-8 — bestehender Stil in
  `server.py` mischt beides, `TREE_HELP` benutzt echte Umlaute).
- Der OSC-Wertebereich und das Sendeverhalten aendern sich **nicht**. Dies ist
  eine reine Beschriftungsaenderung.

---

## Entwurfsentscheidungen (vorab, damit die Tasks kurz bleiben)

1. **Eine Tabelle statt zwei.** Das vorhandene `DESCRIPTIONS: Dict[str, str]`
   geht in `ADDRESS_LABELS: Dict[str, Tuple[str, Optional[str]]]` auf
   (Adresse → Titel, Erklaerung). Zwei Tabellen fuer dieselbe Adresse waeren
   zwei Orte, die auseinanderlaufen: ein Parameter bekaeme einen Titel und
   verloere seine Erklaerung, ohne dass es auffaellt.

2. **Drei Nachschlagestufen**, in dieser Reihenfolge:
   - **exakt** — `/net/impulse/speed`
   - **Muster** — die Adresse mit `#` statt einer Ziffernfolge hinter einem
     Buchstaben (`/net/sequencer/track0/energy` → `/net/sequencer/track#/energy`).
     Sechs Tracks brauchen so **eine** Zeile statt sechs identischen.
   - **Suffix** — das letzte Segment (`Hue` → „Farbton"). Deckt die 18
     Farbkomponenten der sechs Node-Farbkarten ab.

3. **Selbsterklaerend = Titel ohne Erklaerung**, ausgedrueckt als `None` in der
   zweiten Spalte. Nicht „kein Eintrag": ein fehlender Eintrag heisst
   „vergessen", ein `None` heisst „bewusst nichts". Der Unterschied ist im Test
   pruefbar (Vollstaendigkeitstest ueber alle bekannten Adressen).

4. **`SC_PARAMS` bekommt ein `label`-Feld.** Die `description`-Felder bleiben,
   wo sie schon gut sind, und werden nur dort ergaenzt/geschaerft, wo sie
   fehlen oder zu knapp sind (`travelFreqMin`: „Untere harte Grenze." sagt
   nicht, wovon).

5. **Die Adresse wandert unter den Regler** (`<code class="param-addr">`),
   kleiner und gedimmt. Sie bleibt zusaetzlich im `title`-Attribut, damit die
   bestehende Hover-Zuordnung erhalten bleibt.

---

## Task 1: Nachschlagemechanismus + generische imPulse-Adressen

**Dateien:**
- Aendern: `webui/server.py` (`DESCRIPTIONS` → `ADDRESS_LABELS`, neue
  Funktionen `pattern_address()`, `label_for()`, `Parameter.as_dict()`)
- Test: `webui/test_webui.py` (neue Klasse `AddressLabelTest`)

**Schritte:**

- [ ] Test schreiben: `label_for("/net/impulse/speed")` liefert
      `("Grundgeschwindigkeit", <nicht leer>)`;
      `label_for("/net/sequencer/track3/energy")` liefert ueber das Muster
      `("Energie", …)`; `label_for("/nodes/colors/outer/fired/Hue")` liefert
      ueber das Suffix `("Farbton", None)`; eine unbekannte Adresse liefert
      `(None, None)`.
- [ ] Test laufen lassen — schlaegt fehl (`label_for` gibt es nicht).
- [ ] `ADDRESS_LABELS` anlegen, alle ~110 Adressen eintragen (Liste unten in
      „Anhang: Adressinventar"), `pattern_address()` und `label_for()`
      implementieren, `as_dict()` um `"label"` erweitern und `"help"` aus
      `label_for()` speisen.
- [ ] Vollstaendigkeitstest: jede Adresse aus `data/presets/random1.txt` plus
      die seither dazugekommenen (`split/*`, `songStructure/*`,
      `originTreeFilter`, `activateNode/Stripe`) hat einen Titel.
- [ ] Tests gruen, commit.

## Task 2: Farbkarten und Trigger

**Dateien:** `webui/server.py` (`build_groups`), `webui/test_webui.py`

- [ ] Test: die Farbkarte zu `/nodes/colors/outer/fired` traegt
      `label == "Node aussen: feuert"` und ein `help`; der Trigger
      `/net/activateNode` traegt `label` und `help`.
- [ ] Test laufen lassen — schlaegt fehl.
- [ ] `build_groups()`: Farbkarte und Trigger holen Titel/Erklaerung aus
      `label_for()` statt aus dem Adress-Suffix.
- [ ] Tests gruen, commit.

## Task 3: SC_PARAMS-Titel

**Dateien:** `webui/server.py` (`SC_PARAMS`), `webui/test_webui.py`

- [ ] Test: jeder Eintrag in `SC_PARAMS` hat ein nicht-leeres `label` und eine
      nicht-leere `description`.
- [ ] Test laufen lassen — schlaegt fehl.
- [ ] Alle 21 Eintraege um `label` ergaenzen, duenne `description`s schaerfen.
- [ ] Tests gruen, commit.

## Task 4: Frontend

**Dateien:** `webui/static/app.js`, `webui/static/style.css`

- [ ] `makeHead(param)`: Titel = `param.label || splitAddress(...).leaf`;
      Erklaerung und Adresszeile in einer gemeinsamen Hilfsfunktion
      `paramMeta(param)`, damit Slider, Toggle, Trigger und Farbkarte denselben
      Aufbau haben.
- [ ] `buildParam()`: die eigene `help`-Zeile entfaellt (steckt jetzt in
      `paramMeta`), sonst stuende sie zweimal da.
- [ ] `buildTrigger()`, `buildColorCard()`: dieselbe Bauform.
- [ ] `buildScParams()`: `param.label` als Titel, `/klangnetz/param/<name>` als
      Adresszeile.
- [ ] CSS: `.param-name` groesser/fett, `.help` klein, `.param-addr` am
      kleinsten und gedimmt, `monospace`.
- [ ] Commit.

## Task 5: Gesamtdurchlauf

- [ ] `python3 -m pytest webui/test_webui.py -q`
- [ ] `python3 webui/test_webui.py` (der dokumentierte Weg ohne pytest)
- [ ] CLAUDE.md-Abschnitt „Web-UI" um zwei Saetze zu `ADDRESS_LABELS` ergaenzen.
- [ ] Commit.

---

## Anhang: Adressinventar

Vollstaendig aus `data/presets/random1.txt` (105 Zeilen) plus den seither
dazugekommenen Adressen, ermittelt per `grep` ueber die `.java`/`.pde`-Quellen:
`/net/impulse/split/{staggerEnabled,staggerNoteValue,weight/{all,oneLess,single}}`,
`/net/sequencer/track<0..5>/originTreeFilter`, `/net/activateNode`,
`/net/activateStripe`, `/preset/scheduler/{enabled,interval}`,
`/songStructure/enabled`, `/songStructure/matrix/<von>/<nach>` (16),
`/songStructure/dwell/<level>/{min,max}` (8).
