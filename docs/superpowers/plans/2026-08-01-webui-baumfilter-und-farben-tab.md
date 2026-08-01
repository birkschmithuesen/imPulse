# Web-UI: Baum-Filter-Widget und Farben-Tab mit Palette

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Baum-Origin-Filter je Sequencer-Track wird ein beschrifteter
Auswahlbalken statt eines rohen 0..4-Schiebers, und die verstreuten
Farbparameter bekommen einen eigenen, sechsten Tab samt einer geteilten,
server-seitig gespeicherten Farbpalette.

**Architecture:** Beides bleibt reine UI-Praesentation — kein OSC-Wertebereich,
kein Java-Parameter und kein Preset-Format aendert sich. Der Server
(`webui/server.py`) liefert weiterhin nur Struktur (Tab-Zuordnung, Labels,
Palette-Eintraege), das Aussehen macht `webui/static/app.js`. Die Palette ist
eine neue, von Hand editierbare Datei `data/colorPalettes.txt` im selben
Zeilenformat-Geist wie `data/stripeTrees.txt`, geschrieben ueber einen neuen
Endpoint `POST /api/palette` mit Voll-Liste-Semantik.

**Tech Stack:** Python 3 / Flask (ohne Fremdabhaengigkeiten in der Testsuite),
Vanilla-JS ohne Build-Schritt, `unittest` in `webui/test_webui.py`.

## Global Constraints

- **Kein Node/npm, kein Build-Schritt** in `webui/` — Vanilla-JS bleibt Vanilla-JS.
- **Keine Fremdabhaengigkeiten in `webui/test_webui.py`** — nur Standardbibliothek; die Suite laeuft mit `python3 webui/test_webui.py`.
- **Der OSC-Wertebereich von `/net/sequencer/track<N>/originTreeFilter` bleibt `int 0..4`** (`RemoteControlledIntParameter`). Feature 9 ist Praesentation, kein Parameter-Redesign.
- **Jede Adresse aus `remoteSettings.txt` landet in genau einem Tab** — die vorhandene Pruefung in `test_webui.py` bleibt gruen.
- **Reihenfolge in `TAB_RULES` zaehlt: die erste passende Regel gewinnt.** Neue, spezifischere Praefixe muessen VOR den allgemeineren stehen.
- **Die Kern-Reihenfolge der fuenf bestehenden Tabs bleibt unveraendert**; „Farben" wird angehaengt.
- **Float-Werte in Dateien immer mit Dezimalpunkt** (Python schreibt das von selbst, aber der Test haelt es fest — dieselbe Falle wie `Locale.US` in `LedAnchorStore`).
- **Deutsche Kommentare/Beschriftungen ohne Umlaute im Quelltext** (bestehende Konvention in `webui/`: „Baeume", „laeuft", „zufaellig").

---

## Dateien im Ueberblick

| Datei | Rolle |
| --- | --- |
| `webui/server.py` | Tab-Regeln (+`TAB_COLORS`), `treeHelp` im Sequencer-Payload, Palette-Parser/-Schreiber, zwei neue Endpoints |
| `webui/static/app.js` | Auswahlbalken fuer den Baum-Filter, Konflikt-Hinweis je Track, Palette-Leiste, Palette-Swatches je Farbkarte |
| `webui/static/style.css` | Klassen fuer `.tree-bar`, `.track-note`, `.palette*` |
| `webui/test_webui.py` | Tests fuer `treeHelp`, sechs Tabs, Farb-Zuordnung, Palette-Parser und -Endpoints |
| `data/colorPalettes.txt` | Neue Datendatei (wird angelegt, sobald zum ersten Mal gespeichert wird; eine Startdatei mit Kopfkommentar kommt ins Repo) |
| `webui/README.md`, `CLAUDE.md` | Doku: sechs statt fuenf Tabs, Palette-Datei und -Endpoint |

---

### Task 1: Server liefert die Erklaerung zum Baum-Filter

**Files:**
- Modify: `webui/server.py` (Konstante bei `TREE_LABELS`, Rueckgabe von `build_sequencer`)
- Test: `webui/test_webui.py` (Klasse `SequencerSectionTest`)

**Interfaces:**
- Produces: `server.TREE_HELP` (str) und der Schluessel `"treeHelp"` im Rueckgabewert von `build_sequencer()`. `app.js` liest ihn als `seq.treeHelp`.

- [ ] **Step 1: Write the failing test**

In `webui/test_webui.py`, Klasse `SequencerSectionTest`, hinter
`test_tree_labels_cover_no_filter_plus_four_trees` einfuegen:

```python
    def test_sequencer_carries_a_help_line_for_the_tree_filter(self):
        """Ohne Erklaerung ist "0" fuer einen Operator ein Raetsel.

        Der Text muss den Vorrang von originStripeOverride nennen: das ist
        die Falle, bei der der Filter eingestellt ist und trotzdem nichts
        tut (siehe OriginSequencer.advanceOrigin).
        """
        seq = server.build_sequencer(self._params())
        self.assertTrue(seq["treeHelp"])
        self.assertIn("originStripeOverride", seq["treeHelp"])
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 webui/test_webui.py SequencerSectionTest -v`
Expected: FAIL mit `KeyError: 'treeHelp'`

- [ ] **Step 3: Write minimal implementation**

In `webui/server.py` direkt unter `TREE_LABELS` ergaenzen:

```python
# Erklaerung zum Baum-Filter, einmal je Sequencer-Sektion. Sie steht HIER und
# nicht sechsmal in app.js: derselbe Satz je Track waere Rauschen, und der
# Vorrang von originStripeOverride ist eine inhaltliche Aussage ueber die
# Java-Seite (OriginSequencer.advanceOrigin), die hier pruefbar bleibt.
TREE_HELP = ("Baum: schraenkt den Ursprungs-Stripe eines Tracks auf einen der "
             "vier Baeume ein. „alle“ = kein Filter, der Track wuerfelt "
             "aus allen Stripes. Der Filter wirkt nur, solange „Ursprung“ "
             "auf „zufall“ steht — ein fest gesetzter Stripe "
             "(originStripeOverride) hat Vorrang.")
```

In `build_sequencer()` den Rueckgabewert um einen Schluessel erweitern —
`"treeLabels": list(TREE_LABELS),` bleibt stehen, direkt darunter:

```python
        "treeHelp": TREE_HELP,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 webui/test_webui.py SequencerSectionTest -v`
Expected: PASS (alle Tests der Klasse)

- [ ] **Step 5: Commit**

```bash
git add webui/server.py webui/test_webui.py
git commit -m "webui: Erklaerung zum Baum-Filter kommt vom Server"
```

---

### Task 2: Baum-Filter als Auswahlbalken statt Zahlenregler

**Files:**
- Modify: `webui/static/app.js:687-735` (neue Funktion `treeBar` neben `miniSlider`), `webui/static/app.js:989-1006` (Track-Karte)
- Modify: `webui/static/style.css` (Klassen `.tree-bar`, `.track-note`)

**Interfaces:**
- Consumes: `seq.treeLabels` (Liste von 5 Strings) und `seq.treeHelp` aus Task 1.
- Produces: `treeBar(param, initial, labels, onPick) -> handle` mit derselben Handle-Form wie `noteBar`/`miniSlider` (`{ element, set(value, silent), get(), flash() }`), registriert sich unter `param.address` in der globalen `controls`-Map.

- [ ] **Step 1: `treeBar()` schreiben**

In `webui/static/app.js` direkt hinter `noteBar()` (nach Zeile 802) einfuegen:

```js
/* Baum-Filter als Auswahlbalken.
 *
 * Bewusst kein Schieber und auch kein Schalter-plus-Dropdown: der Parameter
 * hat fuenf Zustaende, von denen "alle" (=0) einer ist und keine
 * Sonderstellung braucht. Ein Balken zeigt alle fuenf gleichzeitig, jeder
 * ist einen Klick entfernt, und es gibt keinen verborgenen "zuletzt
 * gewaehlter Baum"-Zustand, der gegenueber dem Sketch auseinanderlaufen
 * koennte. Gleiche Bauform wie noteBar() eine Karte weiter oben.
 *
 * Der Wertebereich bleibt der rohe int 0..4 von
 * RemoteControlledIntParameter - hier wird nur beschriftet. */
function treeBar(param, initial, labels, onPick) {
  const bar = document.createElement('div');
  bar.className = 'tree-bar';
  bar.title = param.address;
  const buttons = [];

  function clampIndex(raw) {
    const v = Math.round(Number(raw));
    if (!isFinite(v)) { return 0; }
    return Math.max(0, Math.min(labels.length - 1, v));
  }

  function mark(value) {
    buttons.forEach((entry) => {
      entry.button.setAttribute('aria-pressed',
        entry.value === value ? 'true' : 'false');
    });
  }

  labels.forEach((label, index) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = label;
    button.title = index === 0
      ? 'Kein Filter - der Track wuerfelt aus allen Stripes'
      : 'Nur Stripes am Baum "' + label + '"';
    button.addEventListener('click', () => {
      mark(index);
      queueSend(param.address, index);
      if (onPick) { onPick(index); }
    });
    buttons.push({ value: index, button: button });
    bar.appendChild(button);
  });

  const start = clampIndex(initial);
  mark(start);
  if (onPick) { onPick(start); }

  const handle = {
    element: bar,
    set: (value, silent) => {
      const index = clampIndex(value);
      mark(index);
      if (!silent) { queueSend(param.address, index); }
      if (onPick) { onPick(index); }
    },
    get: () => {
      const active = buttons.find((entry) =>
        entry.button.getAttribute('aria-pressed') === 'true');
      return active ? active.value : 0;
    },
    flash: () => {},
  };
  controls.set(param.address, handle);
  return handle;
}
```

- [ ] **Step 2: Track-Karte umbauen**

In `buildSequencer()` den Block `if (fields.originTreeFilter) { ... }`
(Zeilen 989–997) und den Block `if (fields.originStripeOverride) { ... }`
(Zeilen 998–1005) durch Folgendes ersetzen. Die Reihenfolge bleibt: erst
Baum, dann Ursprung.

```js
    // Baum-Filter und fester Ursprung haengen zusammen: ein gesetzter
    // Ursprung schlaegt den Filter (OriginSequencer.advanceOrigin). Diese
    // Zeile sagt es, wenn es gerade zutrifft - ein statischer Satz je Track
    // waere sechsmal dasselbe Rauschen und stuende auch dann da, wenn kein
    // Konflikt vorliegt.
    let treeValue = 0;
    let originValue = -1;
    const conflict = document.createElement('p');
    conflict.className = 'track-note';
    conflict.hidden = true;

    function refreshConflict() {
      const shadowed = treeValue > 0 && originValue >= 0;
      conflict.hidden = !shadowed;
      if (shadowed) {
        conflict.textContent = 'Fester Ursprung S' + originValue
          + ' - der Baum-Filter wirkt nicht.';
      }
    }

    if (fields.originTreeFilter) {
      const labels = seq.treeLabels || ['alle'];
      const caption = document.createElement('span');
      caption.className = 'mini-caption';
      caption.textContent = 'Baum';
      mini.appendChild(caption);
      const bar = treeBar(fields.originTreeFilter,
        data.values[fields.originTreeFilter.address], labels,
        (v) => { treeValue = v; refreshConflict(); });
      mini.appendChild(bar.element);
    }
    if (fields.originStripeOverride) {
      mini.appendChild(miniSlider('Ursprung', fields.originStripeOverride,
        data.values[fields.originStripeOverride.address],
        // -1 heisst "zufaellig" - als Zahl waere das ein Raetsel. Steht hier
        // ein Stripe, hat er Vorrang vor dem Baum-Filter darueber (siehe
        // OriginSequencer.advanceOrigin); refreshConflict sagt das dann auch.
        (v) => (Math.round(v) < 0 ? 'zufall' : 'S' + Math.round(v)),
        (v) => { originValue = Math.round(v); refreshConflict(); }).element);
    }
    card.appendChild(mini);
    card.appendChild(conflict);
```

Wichtig: die Zeile `card.appendChild(mini);` steht danach **nicht** noch
einmal — die bisherige Zeile 1006 wird durch den Block oben ersetzt.

- [ ] **Step 3: `miniSlider()` um einen `onChange`-Rueckruf erweitern**

`miniSlider` kennt bisher nur `format`. Damit der Ursprungs-Regler den
Konflikt-Hinweis nachfuehren kann, bekommt er einen fuenften Parameter.
In `webui/static/app.js` die Signatur (Zeile 687) und `apply()` aendern:

```js
function miniSlider(labelText, param, initial, format, onChange) {
```

und in `apply()` direkt vor der Klammer, die die Funktion schliesst, hinter
`if (!silent) { queueSend(param.address, next); }`:

```js
    if (onChange) { onChange(next); }
```

- [ ] **Step 4: CSS ergaenzen**

An `webui/static/style.css` anhaengen (bei den Track-Klassen, hinter dem
`.notes`-Block):

```css
/* Baum-Filter: gleiche Bauform wie .notes, nur mit Woertern statt Symbolen. */
.tree-bar { display: flex; gap: 0.2rem; margin-top: 0.15rem; }
.tree-bar button {
  flex: 1 1 0;
  min-width: 0;
  padding: 0.3rem 0.1rem;
  font-size: 0.68rem;
  border: 1px solid var(--line);
  border-radius: 0.3rem;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.tree-bar button[aria-pressed=true] {
  border-color: var(--tc, var(--accent));
  color: var(--fg);
  background: color-mix(in srgb, var(--tc, var(--accent)) 22%, transparent);
}

.mini-caption { color: var(--muted); font-size: 0.7rem; }

/* Konflikt-Hinweis je Track. Steht nur da, wenn er zutrifft. */
.track-note {
  margin: 0.35rem 0 0;
  font-size: 0.68rem;
  color: var(--warn);
}
```

- [ ] **Step 5: Server-Suite laufen lassen (nichts darf kaputtgehen)**

Run: `python3 webui/test_webui.py`
Expected: OK, keine Fehler (die JS-Aenderung beruehrt sie nicht, aber die
Regression-Sicherung gehoert vor den Commit)

- [ ] **Step 6: Syntaxpruefung des JS ohne Node**

Run: `python3 -c "import re,sys; s=open('webui/static/app.js').read(); print('Klammern:', s.count('{')-s.count('}'), s.count('(')-s.count(')'))"`
Expected: `Klammern: 0 0`

(Falls `node` vorhanden ist, ist `node --check webui/static/app.js` die
bessere Pruefung — dann diese benutzen.)

- [ ] **Step 7: Commit**

```bash
git add webui/static/app.js webui/static/style.css
git commit -m "webui: Baum-Filter als beschrifteter Auswahlbalken statt Zahlenregler"
```

---

### Task 3: Sechster Tab „Farben"

**Files:**
- Modify: `webui/server.py:324-349` (`TAB_COLORS`, `TAB_TITLES`, `TAB_RULES`), `webui/server.py:474-546` (`build_tabs`)
- Modify: `webui/static/app.js:1287-1372` (`buildTabs`)
- Test: `webui/test_webui.py`, Klasse `TabLayoutTest`

**Interfaces:**
- Produces: `server.TAB_COLORS == "farben"`; jeder Tab-Eintrag im Snapshot traegt zusaetzlich `"expanded": bool`. `app.js` rendert Gruppen bei `expanded == true` direkt statt im `<details>`.

- [ ] **Step 1: Write the failing tests**

In `webui/test_webui.py`, Klasse `TabLayoutTest`, den vorhandenen Test
`test_five_tabs_in_the_briefed_order` **ersetzen** durch:

```python
    def test_six_tabs_in_the_briefed_order(self):
        tabs = self._snapshot()["tabs"]
        # Die fuenf Kern-Tabs behalten ihre Reihenfolge, "farben" haengt
        # hinten an - die Struktur aus Feature 8 wird nicht umsortiert.
        self.assertEqual([t["id"] for t in tabs],
                         ["mixer", "sound", "spawn", "noten", "physik",
                          "farben"])
```

und in derselben Klasse ergaenzen:

```python
    def test_colour_addresses_land_in_the_colour_tab(self):
        for address in ("/net/impulse/color/r",
                        "/net/impulse/color/gamma",
                        "/nodes/colors/central/fired/Hue",
                        "/nodes/colors/outer/waiting/Bright"):
            self.assertEqual(server.tab_for_address(address),
                             server.TAB_COLORS, address)

    def test_non_colour_node_and_impulse_addresses_stay_in_physics(self):
        # Die neuen Regeln stehen VOR den alten - wenn sie zu breit greifen,
        # leert sich der Physik-Tab, ohne dass irgendwo ein Fehler entsteht.
        for address in ("/net/impulse/speed", "/net/impulse/lifetime",
                        "/nodes/radius/central", "/nodes/times/recover"):
            self.assertEqual(server.tab_for_address(address),
                             server.TAB_PHYSICS, address)

    def test_colour_tab_shows_its_groups_without_the_details_fold(self):
        """Der Farben-Tab hat keine kuratierte Auswahl.

        Farbkarten tragen keine eigene Adresse (kind == "color"), TAB_PRIMARY
        kann sie also nicht nach oben holen. Ohne das expanded-Flag stuende
        der ganze Tab eingeklappt hinter "Erweitert" - ein Tab, dessen
        Inhalt man erst aufklappen muss.
        """
        tabs = {t["id"]: t for t in self._snapshot()["tabs"]}
        self.assertTrue(tabs[server.TAB_COLORS]["expanded"])
        self.assertFalse(tabs[server.TAB_PHYSICS]["expanded"])
```

Ausserdem im Fixture `_snapshot()` die Farbparameter ergaenzen, damit der
Tab ueberhaupt Inhalt hat — hinter der Zeile
`"float\t/net/impulse/color/r\tx\t1\t0\t1",`:

```python
            "float\t/net/impulse/color/g\tx\t1\t0\t1",
            "float\t/net/impulse/color/b\tx\t1\t0\t1",
            "float\t/net/impulse/color/gamma\tx\t1\t0\t4",
            "int\t/net/impulse/color/useRemoteCol\tx\t0\t0\t1",
            "float\t/nodes/colors/central/fired/Hue\tx\t0\t0\t1",
            "float\t/nodes/colors/central/fired/Sat\tx\t1\t0\t1",
            "float\t/nodes/colors/central/fired/Bright\tx\t1\t0\t1",
            "float\t/nodes/radius/central\tx\t2\t0\t20",
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 webui/test_webui.py TabLayoutTest -v`
Expected: FAIL — `AttributeError: module 'server' has no attribute 'TAB_COLORS'`

- [ ] **Step 3: Server-Seite implementieren**

In `webui/server.py` bei den Tab-Konstanten (Zeile 328) ergaenzen:

```python
TAB_PHYSICS = "physik"
TAB_COLORS = "farben"
```

`TAB_TITLES` um einen Eintrag am Ende erweitern:

```python
TAB_TITLES: List[Tuple[str, str]] = [
    (TAB_MIXER, "Mixer"),
    (TAB_SOUND, "Sound Design"),
    (TAB_SPAWN, "Spawn-Verhalten"),
    (TAB_NOTES, "Noten-Verhalten"),
    (TAB_PHYSICS, "Impuls-Verhalten"),
    (TAB_COLORS, "Farben"),
]
```

`TAB_RULES` um zwei Regeln ergaenzen — **vor** `/net/impulse/` bzw.
`/nodes/`, sonst faengt die Physik-Regel sie ab (dieselbe Falle wie
`speedQuantize`):

```python
TAB_RULES: List[Tuple[str, str]] = [
    ("/master/", TAB_MIXER),
    ("Master/", TAB_MIXER),
    # speedQuantize VOR /net/impulse/, sonst faengt die Physik-Regel es ab.
    ("/net/impulse/speedQuantize/", TAB_NOTES),
    # Und die zwei Farb-Praefixe ebenso VOR ihren allgemeinen Nachbarn:
    # /net/impulse/color/* wuerde sonst in der Physik landen, /nodes/colors/*
    # ebenfalls. Die Farbe eines Impulses ist Gestaltung, seine
    # Geschwindigkeit Physik - im selben Tab standen sie nur, weil die
    # Adresse denselben Praefix traegt.
    ("/net/impulse/color/", TAB_COLORS),
    ("/net/impulse/fadeOut/", TAB_COLORS),
    ("/nodes/colors/", TAB_COLORS),
    ("/net/sequencer/", TAB_SPAWN),
    ("/net/randomSpawn/", TAB_SPAWN),
    ("/net/activate", TAB_SPAWN),
    ("/net/impulse/", TAB_PHYSICS),
    ("/nodes/", TAB_PHYSICS),
]

# Tabs, deren Gruppen direkt sichtbar sind statt hinter "Erweitert".
# Gedacht fuer Tabs ohne kuratierte Auswahl: Farbkarten tragen keine eigene
# Adresse (kind == "color"), TAB_PRIMARY greift bei ihnen also nicht, und der
# Tab bestuende sonst nur aus einem zugeklappten <details>.
TAB_EXPANDED = {TAB_COLORS}
```

In `build_tabs()` das Flag mit in den Tab-Eintrag legen — der
Initialisierungsblock wird zu:

```python
    for tab_id, title in TAB_TITLES:
        by_tab[tab_id] = {
            "id": tab_id,
            "title": title,
            "sections": [],
            "primary": [],
            "groups": [],
            "scParams": [],
            "expanded": tab_id in TAB_EXPANDED,
        }
```

`GROUP_ORDER` bleibt unveraendert: die Gruppen `net/impulse/color` und
`nodes/colors` gibt es schon, sie wandern nur in einen anderen Tab.

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 webui/test_webui.py TabLayoutTest -v`
Expected: PASS

Danach die ganze Suite: `python3 webui/test_webui.py`
Expected: OK

- [ ] **Step 5: `app.js` das Flag auswerten lassen**

In `buildTabs()` den Abschnitt „3. Alles Uebrige eingeklappt" (Zeilen
1346–1363) ersetzen durch:

```js
    // 3. Alles Uebrige. Normalerweise eingeklappt -- ausser der Server
    //    markiert den Tab als "expanded" (Farben-Tab: dort gibt es keine
    //    kuratierte Auswahl, ein zugeklappter Tab waere der ganze Inhalt).
    const hasRest = (tab.groups || []).length || scRest.length;
    if (hasRest) {
      const body = document.createElement('div');
      body.className = 'tab-extra';
      (tab.groups || []).forEach((group) => {
        body.appendChild(buildGroupSection(group, data));
      });
      buildScParams(scRest, body, (data.scParams || {}).port,
        scPrimary.length === 0);
      if (tab.expanded) {
        panel.appendChild(body);
      } else {
        const details = document.createElement('details');
        const summary = document.createElement('summary');
        summary.textContent = 'Erweitert';
        details.appendChild(summary);
        details.appendChild(body);
        panel.appendChild(details);
      }
    }
```

- [ ] **Step 6: Klammerpruefung**

Run: `python3 -c "s=open('webui/static/app.js').read(); print('Klammern:', s.count('{')-s.count('}'), s.count('(')-s.count(')'))"`
Expected: `Klammern: 0 0`

- [ ] **Step 7: Commit**

```bash
git add webui/server.py webui/static/app.js webui/test_webui.py
git commit -m "webui: sechster Tab Farben, Farbparameter aus dem Physik-Tab geloest"
```

---

### Task 4: Palette-Datei lesen und schreiben (Server-Logik)

**Files:**
- Modify: `webui/server.py` (neuer Abschnitt hinter den Preset-Funktionen, ca. Zeile 925)
- Create: `data/colorPalettes.txt`
- Test: `webui/test_webui.py` (neue Klasse `PaletteFileTest`)

**Interfaces:**
- Produces:
  - `server.PALETTE_FILENAME == "colorPalettes.txt"`
  - `server.PALETTE_MAX_ENTRIES == 24`, `server.PALETTE_NAME_MAX_LENGTH == 32`
  - `server.parse_palette(text: str) -> Tuple[List[Dict[str, Any]], List[str]]` — Eintraege `{"name": str, "hue": float, "sat": float, "bright": float}` und Warnungen
  - `server.format_palette(entries: List[Dict[str, Any]]) -> str`
  - `server.load_palette(path: str) -> Tuple[List[Dict], List[str]]` — fehlende Datei = leere Liste, keine Warnung
  - `server.save_palette(path: str, entries: List[Dict]) -> None` — atomar ueber Temp-Datei + Rename
  - `server.validate_palette(raw: Any) -> Tuple[Optional[List[Dict]], Optional[str]]` — normalisierte Liste oder Fehlermeldung
  - `server.default_palette_path(settings_path: str) -> str`

- [ ] **Step 1: Write the failing tests**

Neue Klasse ans Ende von `webui/test_webui.py`, **vor** dem
`if __name__ == "__main__":`-Block:

```python
class PaletteFileTest(unittest.TestCase):
    """data/colorPalettes.txt: Parser, Schreiber, Validierung.

    Dieselben Regeln wie bei data/stripeTrees.txt: von Hand editierbar,
    Kommentare mit '#', und bei doppeltem Namen gewinnt die LETZTE Zeile -
    die natuerliche Handkorrektur ist eine angehaengte Zeile am Ende.
    """

    def test_parses_name_and_three_components(self):
        entries, warnings = server.parse_palette(
            "warm\t0.08\t0.9\t1.0\nkalt\t0.55\t0.7\t0.8\n")
        self.assertEqual(warnings, [])
        self.assertEqual([e["name"] for e in entries], ["warm", "kalt"])
        self.assertAlmostEqual(entries[0]["hue"], 0.08)
        self.assertAlmostEqual(entries[1]["bright"], 0.8)

    def test_comments_and_blank_lines_are_skipped_without_warning(self):
        entries, warnings = server.parse_palette(
            "# Kopf\n\n   \nwarm\t0.08\t0.9\t1.0\n# Ende\n")
        self.assertEqual(len(entries), 1)
        self.assertEqual(warnings, [])

    def test_broken_line_is_reported_not_fatal(self):
        entries, warnings = server.parse_palette(
            "warm\t0.08\t0.9\t1.0\nkaputt\t0.5\nnochwas\ta\tb\tc\n")
        self.assertEqual([e["name"] for e in entries], ["warm"])
        self.assertEqual(len(warnings), 2)

    def test_values_are_clamped_to_zero_one(self):
        entries, _w = server.parse_palette("weit\t-3\t7\t0.5\n")
        self.assertEqual(entries[0]["hue"], 0.0)
        self.assertEqual(entries[0]["sat"], 1.0)

    def test_duplicate_name_last_line_wins(self):
        # Wie StripeTreeStore: die Handkorrektur wird angehaengt, "erste
        # gewinnt" wuerde sie still verschlucken.
        entries, warnings = server.parse_palette(
            "warm\t0.08\t0.9\t1.0\nwarm\t0.10\t0.5\t0.5\n")
        self.assertEqual(len(entries), 1)
        self.assertAlmostEqual(entries[0]["hue"], 0.10)
        self.assertEqual(len(warnings), 1)

    def test_round_trip_through_the_file_format(self):
        entries = [{"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0},
                   {"name": "kalt", "hue": 0.55, "sat": 0.7, "bright": 0.8}]
        again, warnings = server.parse_palette(server.format_palette(entries))
        self.assertEqual(warnings, [])
        self.assertEqual(again, entries)

    def test_written_numbers_use_a_decimal_point(self):
        # Dieselbe Falle wie Locale.US in LedAnchorStore: ein Komma machte
        # die Datei fuer den Parser unlesbar.
        text = server.format_palette(
            [{"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0}])
        body = [l for l in text.splitlines() if l and not l.startswith("#")]
        self.assertIn(".", body[0])
        self.assertNotIn(",", body[0])

    def test_missing_file_is_an_empty_palette_not_an_error(self):
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        entries, warnings = server.load_palette(
            os.path.join(directory, "gibtsnicht.txt"))
        self.assertEqual(entries, [])
        self.assertEqual(warnings, [])

    def test_save_then_load_returns_the_same_palette(self):
        directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, directory)
        path = os.path.join(directory, server.PALETTE_FILENAME)
        entries = [{"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0}]
        server.save_palette(path, entries)
        loaded, warnings = server.load_palette(path)
        self.assertEqual(loaded, entries)
        self.assertEqual(warnings, [])

    def test_validate_rejects_a_non_list(self):
        entries, error = server.validate_palette({"name": "warm"})
        self.assertIsNone(entries)
        self.assertIsNotNone(error)

    def test_validate_rejects_an_unnamed_entry(self):
        entries, error = server.validate_palette(
            [{"name": "  ", "hue": 0.1, "sat": 1, "bright": 1}])
        self.assertIsNone(entries)
        self.assertIn("Name", error)

    def test_validate_rejects_a_tab_in_the_name(self):
        # Ein Tabulator im Namen zerlegte die Zeile beim naechsten Lesen in
        # fuenf Felder - die Datei waere still kaputt.
        entries, error = server.validate_palette(
            [{"name": "wa\trm", "hue": 0.1, "sat": 1, "bright": 1}])
        self.assertIsNone(entries)
        self.assertIsNotNone(error)

    def test_validate_rejects_nan(self):
        entries, error = server.validate_palette(
            [{"name": "warm", "hue": float("nan"), "sat": 1, "bright": 1}])
        self.assertIsNone(entries)
        self.assertIsNotNone(error)

    def test_validate_rejects_more_than_the_maximum(self):
        raw = [{"name": "f%d" % i, "hue": 0.1, "sat": 1, "bright": 1}
               for i in range(server.PALETTE_MAX_ENTRIES + 1)]
        entries, error = server.validate_palette(raw)
        self.assertIsNone(entries)
        self.assertIsNotNone(error)

    def test_validate_clamps_and_normalises(self):
        entries, error = server.validate_palette(
            [{"name": " warm ", "hue": 2, "sat": -1, "bright": "0.5"}])
        self.assertIsNone(error)
        self.assertEqual(entries, [{"name": "warm", "hue": 1.0, "sat": 0.0,
                                    "bright": 0.5}])

    def test_empty_list_is_allowed(self):
        # Die letzte Farbe entfernen muss moeglich sein.
        entries, error = server.validate_palette([])
        self.assertIsNone(error)
        self.assertEqual(entries, [])

    def test_default_path_sits_next_to_the_settings_file(self):
        path = server.default_palette_path(
            os.path.join("a", "data", "remoteSettings.txt"))
        self.assertEqual(os.path.basename(path), server.PALETTE_FILENAME)
        self.assertEqual(os.path.basename(os.path.dirname(path)), "data")

    def test_the_repo_file_parses_without_warnings(self):
        """Gegenprobe an der echten data/colorPalettes.txt."""
        path = os.path.join(server.REPO_ROOT, "data",
                            server.PALETTE_FILENAME)
        if not os.path.exists(path):
            self.skipTest("data/colorPalettes.txt fehlt")
        entries, warnings = server.load_palette(path)
        self.assertEqual(warnings, [])
        for entry in entries:
            for key in ("hue", "sat", "bright"):
                self.assertGreaterEqual(entry[key], 0.0)
                self.assertLessEqual(entry[key], 1.0)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 webui/test_webui.py PaletteFileTest -v`
Expected: FAIL — `AttributeError: module 'server' has no attribute 'parse_palette'`

- [ ] **Step 3: Implementieren**

In `webui/server.py` hinter `wait_for_preset_file()` (also vor
`def group_key(...)`, ca. Zeile 925) einfuegen:

```python
# ---------------------------------------------------------------------------
# Farbpalette
#
# Eine kleine Sammlung wiederverwendbarer Farben, die an JEDER Farbwaehler-
# Karte per Klick anwendbar ist. Sie liegt server-seitig in
# data/colorPalettes.txt und nicht im localStorage des Browsers: sie soll
# einen Neustart ueberleben und auf jedem Geraet dieselbe sein - genau das
# meint "von allen gewaehlt werden kann".
#
# Format wie data/stripeTrees.txt: Tab-getrennte Spalten, '#' leitet einen
# Kommentar ein, von Hand editierbar. Vier Spalten:
#   name  hue  sat  bright     (die drei Werte in 0..1, wie LedColor)
#
# Bei doppeltem Namen gewinnt die LETZTE Zeile - dieselbe Regel und derselbe
# Grund wie bei StripeTreeStore: die natuerliche Handkorrektur ist eine
# angehaengte Zeile am Ende, "erste gewinnt" wuerde sie still verschlucken.
# ---------------------------------------------------------------------------

PALETTE_FILENAME = "colorPalettes.txt"
PALETTE_COMPONENTS = ("hue", "sat", "bright")
# Deckel gegen eine Palette, die sich unbemerkt aufblaeht: die Swatch-Reihe
# steht unter JEDER Farbkarte, ab ein paar Dutzend Farben ist sie hoeher als
# die Karte selbst.
PALETTE_MAX_ENTRIES = 24
PALETTE_NAME_MAX_LENGTH = 32

PALETTE_HEADER = (
    "# Farbpalette fuer das Web-UI (webui/server.py, Sektion Farben).\n"
    "#\n"
    "# Format: name\\thue\\tsat\\tbright   -- die drei Werte in 0..1.\n"
    "# '#' leitet einen Kommentar ein. Bei doppeltem Namen gewinnt die\n"
    "# LETZTE Zeile (angehaengte Handkorrektur schlaegt den alten Eintrag).\n"
    "#\n"
    "# Wird vom Web-UI geschrieben, ist aber von Hand editierbar.\n")


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def parse_palette(text: str) -> Tuple[List[Dict[str, Any]], List[str]]:
    """Parst den Inhalt von colorPalettes.txt.

    Kaputte Zeilen werden gemeldet und uebersprungen, nicht als Abbruch
    weitergereicht -- dasselbe Verhalten wie parse_settings() und wie die
    Java-seitigen Store-Klassen: eine einzelne unerwartete Zeile soll das UI
    nicht lahmlegen.
    """
    entries: List[Dict[str, Any]] = []
    warnings: List[str] = []
    by_name: Dict[str, int] = {}
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = raw.rstrip("\r\n").split("\t")
        if len(fields) < 4:
            warnings.append("Zeile %d uebersprungen (%d Felder statt 4)"
                            % (lineno, len(fields)))
            continue
        name = fields[0].strip()
        if not name:
            warnings.append("Zeile %d uebersprungen (leerer Name)" % lineno)
            continue
        try:
            values = [float(fields[i + 1]) for i in range(3)]
        except ValueError:
            warnings.append("Zeile %d uebersprungen (unlesbare Zahl)" % lineno)
            continue
        if any(v != v for v in values):
            warnings.append("Zeile %d uebersprungen (keine Zahl)" % lineno)
            continue
        entry = {"name": name[:PALETTE_NAME_MAX_LENGTH]}
        for key, value in zip(PALETTE_COMPONENTS, values):
            entry[key] = _clamp01(value)
        if entry["name"] in by_name:
            warnings.append("Name %r in Zeile %d ersetzt den frueheren Eintrag"
                            % (entry["name"], lineno))
            entries[by_name[entry["name"]]] = entry
        else:
            by_name[entry["name"]] = len(entries)
            entries.append(entry)
    return entries, warnings


def format_palette(entries: List[Dict[str, Any]]) -> str:
    """Schreibt die Palette im Dateiformat, mit Kopfkommentar."""
    lines = [PALETTE_HEADER]
    for entry in entries:
        # "%.4f" schreibt in Python immer mit Punkt, unabhaengig von der
        # Systemsprache -- dieselbe Anforderung wie Locale.US auf der
        # Java-Seite, hier ohne eigenes Zutun erfuellt. Der Test haelt es fest.
        lines.append("%s\t%.4f\t%.4f\t%.4f\n"
                     % (entry["name"], entry["hue"], entry["sat"],
                        entry["bright"]))
    return "".join(lines)


def load_palette(path: str) -> Tuple[List[Dict[str, Any]], List[str]]:
    """Liest die Palette. Fehlende Datei = leere Palette, kein Fehler."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except FileNotFoundError:
        return [], []
    except OSError as exc:
        return [], ["Palette nicht lesbar: %s" % exc]
    return parse_palette(text)


def save_palette(path: str, entries: List[Dict[str, Any]]) -> None:
    """Schreibt die Palette atomar (Temp-Datei + Rename).

    Dasselbe Muster wie NodeCrossingStore/LedAnchorStore auf der Java-Seite:
    ein abgebrochener Schreibvorgang darf keine halbe Datei hinterlassen.
    """
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(format_palette(entries))
    os.replace(temp, path)


def validate_palette(raw: Any) -> Tuple[Optional[List[Dict[str, Any]]],
                                        Optional[str]]:
    """Prueft und normalisiert eine vom Browser geschickte Palette.

    Rueckgabe: (normalisierte Liste, None) oder (None, Fehlermeldung). Eine
    leere Liste ist gueltig -- die letzte Farbe zu entfernen muss moeglich
    sein.
    """
    if not isinstance(raw, list):
        return None, "Palette ist keine Liste"
    if len(raw) > PALETTE_MAX_ENTRIES:
        return None, ("Hoechstens %d Farben in der Palette"
                      % PALETTE_MAX_ENTRIES)
    entries: List[Dict[str, Any]] = []
    seen: Set[str] = set()
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            return None, "Eintrag %d ist kein Objekt" % index
        name = item.get("name")
        name = name.strip() if isinstance(name, str) else ""
        if not name:
            return None, "Eintrag %d hat keinen Namen" % index
        if len(name) > PALETTE_NAME_MAX_LENGTH:
            return None, ("Name in Eintrag %d ist laenger als %d Zeichen"
                          % (index, PALETTE_NAME_MAX_LENGTH))
        if "\t" in name or "\n" in name or "\r" in name or name.startswith("#"):
            return None, ("Name in Eintrag %d enthaelt ein Zeichen, das die "
                          "Datei zerlegen wuerde (Tab, Zeilenumbruch, "
                          "fuehrendes #)" % index)
        if name in seen:
            return None, "Name %r kommt zweimal vor" % name
        seen.add(name)
        entry: Dict[str, Any] = {"name": name}
        for key in PALETTE_COMPONENTS:
            try:
                value = float(item.get(key))
            except (TypeError, ValueError):
                return None, "%s in Eintrag %d ist keine Zahl" % (key, index)
            if value != value or value in (float("inf"), float("-inf")):
                return None, "%s in Eintrag %d ist keine Zahl" % (key, index)
            entry[key] = _clamp01(value)
        entries.append(entry)
    return entries, None


def default_palette_path(settings_path: str) -> str:
    """Palette neben der Parameterdatei: <dir von settings>/colorPalettes.txt."""
    return os.path.join(os.path.dirname(os.path.abspath(settings_path)),
                        PALETTE_FILENAME)
```

`Set` steht bereits im `typing`-Import oben in der Datei (wird von
`sequencer_addresses` benutzt) — nichts zu ergaenzen.

- [ ] **Step 4: Startdatei anlegen**

`data/colorPalettes.txt` mit folgendem Inhalt schreiben (fuenf Startfarben,
damit die Leiste nicht leer ist und der Repo-Gegenprobe-Test etwas zu
pruefen hat):

```
# Farbpalette fuer das Web-UI (webui/server.py, Sektion Farben).
#
# Format: name\thue\tsat\tbright   -- die drei Werte in 0..1.
# '#' leitet einen Kommentar ein. Bei doppeltem Namen gewinnt die
# LETZTE Zeile (angehaengte Handkorrektur schlaegt den alten Eintrag).
#
# Wird vom Web-UI geschrieben, ist aber von Hand editierbar.
warm	0.0800	0.9000	1.0000
bernstein	0.1100	1.0000	0.8000
gruen	0.3300	0.8000	0.9000
tuerkis	0.5000	0.7000	0.9000
violett	0.7800	0.6000	0.9000
```

Wichtig: die Trenner zwischen den Spalten muessen echte Tabulatoren sein.
Zum Anlegen deshalb:

```bash
python3 - <<'EOF'
import os, sys
sys.path.insert(0, "webui")
import server
server.save_palette(os.path.join("data", server.PALETTE_FILENAME), [
    {"name": "warm",      "hue": 0.08, "sat": 0.90, "bright": 1.00},
    {"name": "bernstein", "hue": 0.11, "sat": 1.00, "bright": 0.80},
    {"name": "gruen",     "hue": 0.33, "sat": 0.80, "bright": 0.90},
    {"name": "tuerkis",   "hue": 0.50, "sat": 0.70, "bright": 0.90},
    {"name": "violett",   "hue": 0.78, "sat": 0.60, "bright": 0.90},
])
EOF
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `python3 webui/test_webui.py PaletteFileTest -v`
Expected: PASS (18 Tests)

Dann die ganze Suite: `python3 webui/test_webui.py`
Expected: OK

- [ ] **Step 6: Commit**

```bash
git add webui/server.py webui/test_webui.py data/colorPalettes.txt
git commit -m "webui: Farbpalette in data/colorPalettes.txt lesen und schreiben"
```

---

### Task 5: Palette-Endpoints

**Files:**
- Modify: `webui/server.py:1218-1404` (`create_app`), `webui/server.py:1407-1443` (`main`, neues `--palette`)
- Test: `webui/test_webui.py` (neue Klasse `PaletteEndpointTest`)

**Interfaces:**
- Consumes: `parse_palette`/`save_palette`/`validate_palette`/`default_palette_path` aus Task 4.
- Produces: `create_app(settings_path, osc_host, osc_port, presets_path, palette_path)` — **fuenfter Parameter**, mit Vorgabe `None` (dann `default_palette_path(settings_path)`), damit vorhandene Aufrufe in Tests nicht brechen. Endpoints `GET /api/palette` und `POST /api/palette`; Bootstrap-Schluessel `"palette"`.

- [ ] **Step 1: Write the failing tests**

Neue Klasse ans Ende von `webui/test_webui.py`, vor dem
`if __name__ == "__main__":`-Block. Sie braucht Flask; wo die vorhandenen
Endpoint-Tests einen Skip-Mechanismus benutzen, denselben verwenden —
nachsehen mit `grep -n "test_client\|skipTest(\"Flask" webui/test_webui.py`
und dem dortigen Muster folgen. Falls es noch keinen gibt, dieses:

```python
class PaletteEndpointTest(unittest.TestCase):
    """GET/POST /api/palette.

    Voll-Liste-Semantik: der Browser schickt die komplette Palette, der
    Server ersetzt die Datei. Kein Hinzufuegen/Entfernen einzelner Eintraege
    - zwei Endpoints, die beide dieselbe Datei anfassen, waeren zwei Wege,
    die auseinanderlaufen koennen, und die Reihenfolge muesste dann trotzdem
    von irgendwo kommen.
    """

    def setUp(self):
        if server.Flask is None:
            self.skipTest("Flask fehlt")
        self.directory = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.directory)
        self.settings = os.path.join(self.directory, "remoteSettings.txt")
        with open(self.settings, "w", encoding="utf-8") as handle:
            handle.write("float\t/net/impulse/color/r\tx\t1\t0\t1\n")
        self.palette = os.path.join(self.directory, server.PALETTE_FILENAME)
        app = server.create_app(self.settings, "127.0.0.1", 9999,
                                os.path.join(self.directory, "presets"),
                                self.palette)
        app.config["TESTING"] = True
        self.client = app.test_client()

    def test_get_on_a_missing_file_is_an_empty_palette(self):
        payload = self.client.get("/api/palette").get_json()
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["entries"], [])

    def test_post_writes_the_file_and_get_reads_it_back(self):
        body = {"entries": [{"name": "warm", "hue": 0.08, "sat": 0.9,
                             "bright": 1.0}]}
        response = self.client.post("/api/palette", json=body)
        self.assertEqual(response.status_code, 200)
        self.assertTrue(os.path.exists(self.palette))
        entries = self.client.get("/api/palette").get_json()["entries"]
        self.assertEqual(entries, body["entries"])

    def test_post_replaces_instead_of_appending(self):
        self.client.post("/api/palette", json={"entries": [
            {"name": "a", "hue": 0.1, "sat": 1, "bright": 1},
            {"name": "b", "hue": 0.2, "sat": 1, "bright": 1}]})
        self.client.post("/api/palette", json={"entries": [
            {"name": "b", "hue": 0.2, "sat": 1, "bright": 1}]})
        entries = self.client.get("/api/palette").get_json()["entries"]
        self.assertEqual([e["name"] for e in entries], ["b"])

    def test_post_with_an_invalid_entry_is_rejected_and_changes_nothing(self):
        self.client.post("/api/palette", json={"entries": [
            {"name": "a", "hue": 0.1, "sat": 1, "bright": 1}]})
        response = self.client.post("/api/palette", json={"entries": [
            {"name": "", "hue": 0.1, "sat": 1, "bright": 1}]})
        self.assertEqual(response.status_code, 400)
        self.assertFalse(response.get_json()["ok"])
        entries = self.client.get("/api/palette").get_json()["entries"]
        self.assertEqual([e["name"] for e in entries], ["a"])

    def test_post_without_a_list_is_rejected(self):
        response = self.client.post("/api/palette", json={"entries": "warm"})
        self.assertEqual(response.status_code, 400)

    def test_index_carries_the_palette_in_the_bootstrap(self):
        self.client.post("/api/palette", json={"entries": [
            {"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0}]})
        html = self.client.get("/").get_data(as_text=True)
        self.assertIn("\\\"palette\\\"", html.replace('&#34;', '\\"')
                      ) if False else None
        self.assertIn("warm", html)
```

Die letzte Zeile im letzten Test ist unnoetig verschachtelt — sie durch
diese zwei ersetzen (der Bootstrap ist JSON im HTML, ein Namensfund
genuegt als Nachweis, dass die Palette mitgeht):

```python
    def test_index_carries_the_palette_in_the_bootstrap(self):
        self.client.post("/api/palette", json={"entries": [
            {"name": "warm", "hue": 0.08, "sat": 0.9, "bright": 1.0}]})
        html = self.client.get("/").get_data(as_text=True)
        self.assertIn("warm", html)
        self.assertIn("palette", html)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 webui/test_webui.py PaletteEndpointTest -v`
Expected: FAIL — `TypeError: create_app() takes 4 positional arguments but 5 were given`

- [ ] **Step 3: Implementieren**

Signatur von `create_app` erweitern:

```python
def create_app(settings_path: str, osc_host: str, osc_port: int,
               presets_path: str, palette_path: Optional[str] = None):
```

Direkt hinter `app.config["IMPULSE_SC_SENDER"] = sc_sender` ergaenzen:

```python
    # Vorgabe erst hier aufloesen, nicht in der Signatur: der Default haengt
    # am settings_path und ein Default-Argument wuerde einmal beim Import
    # ausgewertet.
    if palette_path is None:
        palette_path = default_palette_path(settings_path)
    app.config["IMPULSE_PALETTE_PATH"] = palette_path

    def palette_payload() -> Dict[str, Any]:
        entries, warnings = load_palette(palette_path)
        return {"ok": True, "entries": entries, "path": palette_path,
                "warnings": warnings}
```

Die zwei Endpoints hinter `api_preset_save()` einfuegen (vor
`return app`):

```python
    @app.route("/api/palette")
    def api_palette():
        return jsonify(palette_payload())

    @app.route("/api/palette", methods=["POST"])
    def api_palette_save():
        """Die komplette Palette ersetzen.

        Voll-Liste statt Hinzufuegen/Entfernen: der Browser haelt die
        Reihenfolge ohnehin, und zwei Endpoints auf derselben Datei waeren
        zwei Wege, die auseinanderlaufen koennen. Zwei gleichzeitig offene
        Browser ueberschreiben sich dabei gegenseitig -- bei einer
        Ein-Operator-Installation ist das der richtige Tausch, steht aber im
        README.
        """
        body = request.get_json(silent=True) or {}
        entries, problem = validate_palette(body.get("entries"))
        if problem is not None:
            return jsonify({"ok": False, "error": problem}), 400
        try:
            save_palette(palette_path, entries)
        except OSError as exc:
            return jsonify({"ok": False,
                            "error": "Palette nicht schreibbar: %s" % exc}), 500
        return jsonify(palette_payload())
```

Und in `index()` den Bootstrap um einen Schluessel erweitern — hinter
`"presets": preset_list_payload(),`:

```python
                "palette": palette_payload(),
```

In `main()` ein Argument und die Weitergabe ergaenzen. Hinter dem
`--presets`-Argument:

```python
    parser.add_argument("--palette",
                        default=os.environ.get("IMPULSE_PALETTE"),
                        help="Datei mit der Farbpalette "
                             "(Vorgabe: colorPalettes.txt neben --settings)")
```

und weiter unten:

```python
    palette_path = (os.path.abspath(args.palette) if args.palette
                    else default_palette_path(settings_path))
    app = create_app(settings_path, args.osc_host, args.osc_port,
                     presets_path, palette_path)

    print("[webui] remoteSettings: %s" % settings_path)
    print("[webui] Presets:        %s" % presets_path)
    print("[webui] Palette:        %s" % palette_path)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 webui/test_webui.py PaletteEndpointTest -v`
Expected: PASS

Dann die ganze Suite: `python3 webui/test_webui.py`
Expected: OK

- [ ] **Step 5: Commit**

```bash
git add webui/server.py webui/test_webui.py
git commit -m "webui: Endpoints fuer die Farbpalette (GET/POST /api/palette)"
```

---

### Task 6: Palette im Browser — Leiste und Swatches je Farbkarte

**Files:**
- Modify: `webui/static/app.js` (neuer Abschnitt vor `buildTabs`, Aenderung in `buildColorCard` und `buildTabs`)
- Modify: `webui/static/style.css`

**Interfaces:**
- Consumes: `bootstrap.palette` (`{ok, entries, path, warnings}`) aus Task 5; `queueSendMany(key, updates)` und `controls` aus dem vorhandenen Code.
- Produces: `paletteEntries` (Array), `renderPaletteRows()`, `buildPaletteSection(host)`, `paletteRowFor(control)`.

- [ ] **Step 1: Palette-Zustand und Swatch-Reihen**

In `webui/static/app.js` hinter `buildColorCard()` (nach Zeile 487)
einfuegen:

```js
// ---------------------------------------------------------------------------
// Farbpalette
//
// Eine Sammlung wiederverwendbarer Farben, die unter JEDER Farbwaehler-Karte
// als Reihe steht: ein Klick setzt Hue/Sat/Bright dieser einen Karte. Der
// Versand laeuft ueber denselben queueSendMany-Weg wie der Farbwaehler
// darueber - kein Sonderpfad, damit Entprellung und Fehlerbehandlung
// dieselben bleiben.
//
// Gehalten wird sie SERVER-seitig (data/colorPalettes.txt), nicht im
// localStorage: sie soll einen Neustart ueberleben und auf jedem Geraet
// dieselbe sein.
// ---------------------------------------------------------------------------

let paletteEntries = (bootstrap.palette && bootstrap.palette.entries) || [];
const paletteRows = [];        // { element, control }  eine je Farbkarte
let paletteBarEl = null;       // die Leiste im Farben-Tab
let activeColorCard = null;    // zuletzt angefasste Karte, Quelle fuer "+"

function paletteSwatchColor(entry) {
  return hsbToHex(entry.hue, entry.sat, entry.bright);
}

/* Setzt eine Karte auf einen Paletteneintrag. Gerundet wird auf das Raster
 * der Regler, genau wie im Farbwaehler - sonst laufen angezeigter und
 * gesendeter Wert auseinander. */
function applyPaletteEntry(control, entry) {
  const updates = [
    { address: control.components.hue.address,
      value: roundToStep(entry.hue, control.components.hue) },
    { address: control.components.sat.address,
      value: roundToStep(entry.sat, control.components.sat) },
    { address: control.components.bright.address,
      value: roundToStep(entry.bright, control.components.bright) },
  ];
  updates.forEach((u) => controls.get(u.address).set(u.value, true));
  const card = colorCards.find((c) => c.base === control.base);
  if (card) { syncColorCard(card); }
  queueSendMany('color:' + control.base, updates);
  setStatus('Palette "' + entry.name + '" auf ' + control.base
    + ' angewendet', 'ok');
}

/* Baut die Swatch-Reihe einer Karte neu. Wird bei jeder Palette-Aenderung
 * fuer ALLE Karten gerufen - eine neue Farbe soll ueberall sofort da sein,
 * nicht erst nach einem Neuladen. */
function fillPaletteRow(row) {
  row.element.innerHTML = '';
  if (!paletteEntries.length) {
    const hint = document.createElement('span');
    hint.className = 'palette-empty';
    hint.textContent = 'Palette leer';
    row.element.appendChild(hint);
    return;
  }
  paletteEntries.forEach((entry) => {
    const swatch = document.createElement('button');
    swatch.type = 'button';
    swatch.className = 'swatch';
    swatch.style.background = paletteSwatchColor(entry);
    swatch.title = entry.name + ' auf ' + row.control.base + ' anwenden';
    swatch.setAttribute('aria-label', entry.name);
    swatch.addEventListener('click', () => applyPaletteEntry(row.control, entry));
    row.element.appendChild(swatch);
  });
}

function renderPaletteRows() {
  paletteRows.forEach(fillPaletteRow);
  if (paletteBarEl) { fillPaletteBar(); }
}

/* Die komplette Palette an den Server schicken. Voll-Liste-Semantik: der
 * Server ersetzt die Datei durch genau das, was hier steht. */
async function savePalette(next, what) {
  const previous = paletteEntries;
  paletteEntries = next;
  renderPaletteRows();
  try {
    const response = await fetch('/api/palette', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ entries: next }),
    });
    const payload = await response.json();
    if (!response.ok || !payload.ok) {
      throw new Error(payload.error || ('HTTP ' + response.status));
    }
    paletteEntries = payload.entries || [];
    renderPaletteRows();
    setStatus(what, 'ok');
  } catch (err) {
    // Zurueckrollen: sonst zeigt das UI eine Farbe, die in der Datei nicht
    // steht, und der naechste Neustart schluckt sie kommentarlos.
    paletteEntries = previous;
    renderPaletteRows();
    setStatus('Palette nicht gespeichert: ' + err.message, 'err');
  }
}

function paletteRowFor(control) {
  const element = document.createElement('div');
  element.className = 'palette-row';
  const row = { element: element, control: control };
  paletteRows.push(row);
  fillPaletteRow(row);
  return element;
}
```

- [ ] **Step 2: `buildColorCard()` um die Swatch-Reihe erweitern**

In `buildColorCard()` (Zeile 425 ff.) zwei Aenderungen:

a) hinter `wrap.appendChild(components);` (Zeile 464) einfuegen:

```js
  wrap.appendChild(paletteRowFor(control));

  // Merkt sich die zuletzt angefasste Karte -- Quelle fuer "Aktuelle Farbe
  // zur Palette hinzufuegen". Ohne das muesste der Knopf raten, welche der
  // sieben Karten gemeint ist.
  wrap.addEventListener('pointerdown', () => { activeColorCard = control; });
  wrap.addEventListener('focusin', () => { activeColorCard = control; });
```

b) nichts weiter — `colorCards.push(card)` und `syncColorCard(card)`
bleiben, wo sie sind.

Ausserdem in `render()` (Zeile 493) die Registrierung zuruecksetzen, sonst
wachsen `paletteRows` bei jedem „Neu laden" an und zeigen auf Karten, die
nicht mehr im Dokument stehen:

```js
function render(data) {
  controls.clear();
  colorCards.length = 0;
  paletteRows.length = 0;
  paletteBarEl = null;
  activeColorCard = null;
```

- [ ] **Step 3: Die Palette-Leiste im Farben-Tab**

Hinter `fillPaletteRow`/`renderPaletteRows` (also im selben Abschnitt aus
Step 1) ergaenzen:

```js
/* Die Leiste im Farben-Tab: alle Farben mit Namen, je ein Loesch-Kreuz,
 * dazu Namensfeld und Hinzufuegen-Knopf. */
function fillPaletteBar() {
  paletteBarEl.innerHTML = '';
  if (!paletteEntries.length) {
    const hint = document.createElement('p');
    hint.className = 'palette-empty';
    hint.textContent = 'Noch keine Farbe in der Palette. Eine Farbkarte '
      + 'anfassen und unten "Farbe uebernehmen" druecken.';
    paletteBarEl.appendChild(hint);
    return;
  }
  paletteEntries.forEach((entry) => {
    const chip = document.createElement('span');
    chip.className = 'palette-chip';

    const dot = document.createElement('span');
    dot.className = 'palette-dot';
    dot.style.background = paletteSwatchColor(entry);

    const label = document.createElement('span');
    label.textContent = entry.name;

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'palette-remove';
    remove.textContent = '×';
    remove.title = entry.name + ' aus der Palette entfernen';
    remove.addEventListener('click', () => {
      savePalette(paletteEntries.filter((e) => e.name !== entry.name),
        'Farbe "' + entry.name + '" entfernt');
    });

    chip.appendChild(dot);
    chip.appendChild(label);
    chip.appendChild(remove);
    paletteBarEl.appendChild(chip);
  });
}

function buildPaletteSection(host) {
  const section = document.createElement('section');
  section.className = 'palette';

  const title = document.createElement('h2');
  title.textContent = 'Palette';
  section.appendChild(title);

  const note = document.createElement('p');
  note.className = 'palette-note';
  note.textContent = 'Wiederverwendbare Farben. Sie liegen in '
    + 'data/colorPalettes.txt auf dem imPulse-Rechner, gelten also fuer '
    + 'jeden Browser und ueberleben einen Neustart. Unter jeder Farbkarte '
    + 'steht dieselbe Reihe - ein Klick setzt die Karte auf diese Farbe.';
  section.appendChild(note);

  paletteBarEl = document.createElement('div');
  paletteBarEl.className = 'palette-bar';
  section.appendChild(paletteBarEl);
  fillPaletteBar();

  const row = document.createElement('div');
  row.className = 'palette-add';

  const nameInput = document.createElement('input');
  nameInput.type = 'text';
  nameInput.placeholder = 'Name der Farbe';
  nameInput.maxLength = 32;
  nameInput.autocomplete = 'off';
  nameInput.setAttribute('aria-label', 'Name der neuen Palette-Farbe');

  const add = document.createElement('button');
  add.type = 'button';
  add.textContent = 'Farbe uebernehmen';
  add.title = 'Nimmt die Farbe der zuletzt angefassten Farbkarte';

  function addCurrent() {
    if (!activeColorCard) {
      setStatus('Erst eine Farbkarte anfassen - die Palette weiss sonst '
        + 'nicht, welche Farbe gemeint ist', 'warn');
      return;
    }
    const name = nameInput.value.trim();
    if (!name) {
      setStatus('Bitte einen Namen fuer die Farbe eingeben', 'warn');
      nameInput.focus();
      return;
    }
    const entry = {
      name: name,
      hue: controls.get(activeColorCard.components.hue.address).get(),
      sat: controls.get(activeColorCard.components.sat.address).get(),
      bright: controls.get(activeColorCard.components.bright.address).get(),
    };
    // Gleicher Name ersetzt - dieselbe Regel wie in der Datei, wo die letzte
    // Zeile gewinnt.
    const next = paletteEntries.filter((e) => e.name !== name).concat([entry]);
    nameInput.value = '';
    savePalette(next, 'Farbe "' + name + '" aus ' + activeColorCard.base
      + ' uebernommen');
  }

  add.addEventListener('click', addCurrent);
  nameInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') { addCurrent(); }
  });

  row.appendChild(nameInput);
  row.appendChild(add);
  section.appendChild(row);

  host.appendChild(section);
}
```

- [ ] **Step 4: Die Leiste in den Farben-Tab haengen**

In `buildTabs()` im Abschnitt „1. Spezial-Sektionen" (Zeile 1324 ff.) den
`forEach` um einen Zweig erweitern:

```js
    (tab.sections || []).forEach((name) => {
      if (name === 'sequencer') { buildSequencer(data, panel); }
      if (name === 'speedClasses') { buildSpeedClasses(data, panel); }
      if (name === 'palette') { buildPaletteSection(panel); }
    });
```

Und in `webui/server.py`, `build_tabs()`, hinter den zwei vorhandenen
Sektions-Zuweisungen:

```python
    # Die Palette-Leiste steht im Farben-Tab, ueber den Farbkarten. Sie
    # braucht keine Parameter aus remoteSettings.txt und ist deshalb
    # bedingungslos da -- anders als Sequencer und Speed-Klassen, die ohne
    # ihre Adressen nicht gebaut werden koennen.
    by_tab[TAB_COLORS]["sections"].append("palette")
```

**Reihenfolge in `buildTabs`:** die Sektionen werden vor den Gruppen
gerendert, die Leiste steht also oben im Tab — genau richtig, weil die
Karten darunter dieselben Farben noch einmal zeigen.

- [ ] **Step 5: CSS ergaenzen**

An `webui/static/style.css` anhaengen:

```css
/* --- Farbpalette --------------------------------------------------------- */

.palette { margin: 1rem 1rem 0; }
.palette-note {
  margin: 0 0 0.6rem;
  color: var(--muted);
  font-size: 0.75rem;
  line-height: 1.4;
}
.palette-empty { color: var(--muted); font-size: 0.75rem; }

.palette-bar { display: flex; flex-wrap: wrap; gap: 0.4rem; }
.palette-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.2rem 0.35rem 0.2rem 0.25rem;
  border: 1px solid var(--line);
  border-radius: 0.4rem;
  font-size: 0.75rem;
}
.palette-dot {
  width: 1rem;
  height: 1rem;
  border-radius: 50%;
  border: 1px solid rgba(0, 0, 0, 0.4);
}
.palette-remove {
  border: none;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  font-size: 0.9rem;
  line-height: 1;
  padding: 0 0.1rem;
}
.palette-remove:hover { color: var(--err); }

.palette-add { display: flex; gap: 0.5rem; margin-top: 0.6rem; }
.palette-add input[type=text] {
  flex: 1 1 auto;
  min-width: 0;
  padding: 0.3rem 0.45rem;
  border: 1px solid var(--line);
  border-radius: 0.35rem;
  background: transparent;
  color: inherit;
  font: inherit;
}
.palette-add button { flex: 0 0 auto; }

/* Die Reihe unter jeder Farbkarte. Kleiner als die Chips oben: hier zaehlt
   nur die Farbe, der Name steht im Tooltip. */
.palette-row { display: flex; flex-wrap: wrap; gap: 0.25rem; margin-top: 0.45rem; }
.palette-row .swatch {
  width: 1.15rem;
  height: 1.15rem;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 0.25rem;
  cursor: pointer;
}
.palette-row .swatch:hover { border-color: var(--accent); }
```

- [ ] **Step 6: Server-Suite und Klammerpruefung**

Run: `python3 webui/test_webui.py`
Expected: OK

Run: `python3 -c "s=open('webui/static/app.js').read(); print('Klammern:', s.count('{')-s.count('}'), s.count('(')-s.count(')'))"`
Expected: `Klammern: 0 0`

- [ ] **Step 7: Sichtprobe im Browser**

Run:
```bash
cd webui && python3 server.py --settings ../data/remoteSettings.txt &
```
Falls `data/remoteSettings.txt` fehlt (sie liegt nicht im Repo, sondern
entsteht bei jedem imPulse-Start), stattdessen eine Testdatei bauen:

```bash
python3 - <<'EOF'
lines = [
    "float\t/master/level\tspace\t0.1\t0\t1",
    "float\t/net/impulse/color/r\tspace\t1\t0\t1",
    "float\t/net/impulse/color/g\tspace\t1\t0\t1",
    "float\t/net/impulse/color/b\tspace\t1\t0\t1",
    "float\t/nodes/colors/central/fired/Hue\tspace\t0\t0\t1",
    "float\t/nodes/colors/central/fired/Sat\tspace\t1\t0\t1",
    "float\t/nodes/colors/central/fired/Bright\tspace\t1\t0\t1",
]
open("/tmp/remoteSettings.txt", "w").write("\n".join(lines) + "\n")
EOF
python3 webui/server.py --settings /tmp/remoteSettings.txt --palette /tmp/colorPalettes.txt --port 8099
```

Pruefen: sechs Tabs, „Farben" enthaelt die Palette-Leiste und die
Farbkarten **ohne** Aufklappen, unter jeder Karte eine Swatch-Reihe, ein
Klick faerbt die Karte um. Danach den Server beenden.

- [ ] **Step 8: Commit**

```bash
git add webui/static/app.js webui/static/style.css webui/server.py
git commit -m "webui: Farbpalette im Browser - Leiste und Swatches je Farbkarte"
```

---

### Task 7: Dokumentation

**Files:**
- Modify: `webui/README.md`
- Modify: `CLAUDE.md` (Abschnitt „Web-UI (webui/)")
- Modify: `webui/templates/index.html` (der Kommentar sagt „Fuenf Themen-Tabs")

- [ ] **Step 1: `index.html`-Kommentar korrigieren**

Aus `<!-- Fuenf Themen-Tabs. ... -->` wird `<!-- Sechs Themen-Tabs. ... -->`.

- [ ] **Step 2: `webui/README.md` ergaenzen**

Im Abschnitt ueber die Tabs die Zahl fuenf auf sechs ziehen und den neuen
Tab nennen. Zusaetzlich einen Abschnitt „Farbpalette" mit diesen Punkten:

- Datei `data/colorPalettes.txt`, Tab-Format `name hue sat bright` (0..1),
  `#` als Kommentar, von Hand editierbar, bei doppeltem Namen gewinnt die
  letzte Zeile.
- Endpoints `GET /api/palette` und `POST /api/palette` (Voll-Liste: der
  Server ersetzt die Datei durch genau das, was der Browser schickt).
- **Zwei gleichzeitig offene Browser ueberschreiben sich gegenseitig.**
  Bewusster Tausch: die Alternative waeren Teil-Updates plus eine
  Reihenfolge-Verwaltung auf dem Server, fuer eine Installation mit einem
  Operator.
- Hoechstens `PALETTE_MAX_ENTRIES` (24) Farben — die Swatch-Reihe steht
  unter jeder Farbkarte.
- Pfad ueber `--palette` bzw. `IMPULSE_PALETTE` verschiebbar.

- [ ] **Step 3: `CLAUDE.md` nachziehen**

Im Abschnitt „Web-UI (webui/)":
- „**Fünf Themen-Tabs**" → „**Sechs Themen-Tabs**", den Farben-Tab in der
  Aufzaehlung ergaenzen.
- Bei der Reihenfolge-Warnung („`/net/impulse/speedQuantize/` muss **vor**
  `/net/impulse/` stehen") die zwei Farb-Praefixe mit demselben Grund
  ergaenzen.
- Einen Absatz zur Palette: Datei, Voll-Liste-Semantik, warum server-seitig
  statt localStorage, und dass der Baum-Filter jetzt ein Auswahlbalken ist
  mit einem Konflikt-Hinweis, der nur bei gesetztem `originStripeOverride`
  erscheint.

- [ ] **Step 4: Gesamte Suite ein letztes Mal**

Run: `python3 webui/test_webui.py`
Expected: OK

- [ ] **Step 5: Commit**

```bash
git add webui/README.md CLAUDE.md webui/templates/index.html
git commit -m "docs: Farben-Tab, Farbpalette und Baum-Auswahlbalken beschrieben"
```

---

## Commit-Segmentierung

Der Brief verlangt Feature 9 und 10 getrennt:

- **Feature 9** — Tasks 1 und 2 (zwei Commits).
- **Feature 10** — Tasks 3 bis 6 (vier Commits).
- **Task 7** — Doku fuer beides, ein Commit am Ende.

## Was ausdruecklich NICHT gebaut wird

- **Kein Toggle „Baum-Filter aktiv" plus Dropdown.** Zwei Bedienelemente fuer
  einen Parameter mit fuenf Zustaenden, dazu ein verborgener „zuletzt
  gewaehlter Baum"-Zustand, der nach einem Preset-Laden gegenueber dem
  Sketch falsch stehen kann. Der Auswahlbalken zeigt alle fuenf Zustaende
  gleichzeitig und hat keinen verborgenen Zustand.
- **Keine Aenderung an `originTreeFilter` auf der Java-Seite.** Der Brief
  sagt es selbst: Praesentation, kein Parameter-Redesign.
- **Kein Umsortieren der fuenf bestehenden Tabs.** „Farben" haengt hinten an.
- **Keine Palette im localStorage.** Sie soll geteilt sein; localStorage
  waere pro Browser und ginge beim naechsten Geraet verloren.
- **Keine Farb-Presets.** Die Palette haelt nur Farbwerte, nicht welche Karte
  welche Farbe hat — das ist genau das, was ein Preset schon kann.
