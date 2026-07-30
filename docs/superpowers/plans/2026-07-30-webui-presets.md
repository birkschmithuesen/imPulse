# Preset-System im Web-UI — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Presets im Browser laden und speichern — Dropdown mit Laden-Knopf, Textfeld mit Speichern-Knopf.

**Architecture:** `webui/server.py` (Flask) bekommt drei neue Routen. Die Preset-Liste liest der Server direkt vom Dateisystem (er laeuft auf derselben Maschine wie imPulse), die Kommandos gehen als OSC-String an Port 8001. Nach dem Laden liest der Server dieselbe Preset-Datei mit dem vorhandenen `parse_settings()` und schickt die Werte in der HTTP-Antwort zurueck, damit die Regler mitziehen. An Java aendert sich keine Zeile.

**Tech Stack:** Python 3.8+ Standardbibliothek + Flask, Vanilla JS, kein Build-Schritt.

**Spec:** `docs/superpowers/specs/2026-07-30-webui-presets-design.md`

## Global Constraints

- Die laufende Installation wird **nicht** angefasst: kein SSH zum Windows-Laptop, kein Deploy, kein Neustart eines Scheduled Task. Nur Code in diesem Checkout.
- Kein Framework-Wechsel, kein Node, kein npm, kein Build-Schritt.
- `webui/test_webui.py` laeuft mit der Standardbibliothek allein — kein Flask, kein python-osc, keine laufende Installation noetig. Tests, die Flask brauchen, gehoeren nicht hinein.
- Namensregel wortgleich zu `PresetStore.isValidName()`: Laenge 1..64, nur `a-z`, `0-9`, `_`, `-`. Java bleibt die Autoritaet.
- Der Server schreibt **nie** in `data/presets/` — das Schreiben bleibt bei imPulse.
- `test/run.sh` (Java-Suite) muss gruen bleiben.
- Kommentare und UI-Texte auf Deutsch, ohne Umlaute im Python-Quelltext (bestehender Stil in `server.py`).
- Commit am Ende auf `integration/webui-presets`, **kein** Push.

---

### Task 1: OSC-String-Argument

`build_osc_message()` kann bisher nur `int` und `float`. `/preset/load <name>` braucht einen String.

**Files:**
- Modify: `webui/server.py` (`build_osc_message`, ~Zeile 171)
- Test: `webui/test_webui.py` (`OscEncodingTest`, ~Zeile 338)

**Interfaces:**
- Produces: `build_osc_message(address: str, value: int|float|str) -> bytes`

- [ ] **Step 1: Write the failing tests** — in `class OscEncodingTest`:

```python
    def test_string_message_layout(self):
        packet = build_osc_message("/preset/load", "standby")
        self.assertEqual(len(packet) % 4, 0)
        self.assertTrue(packet.startswith(b"/preset/load\x00\x00\x00\x00"))
        self.assertIn(b",s\x00\x00", packet)
        self.assertTrue(packet.endswith(b"standby\x00"))

    def test_string_padding_multiple_of_four(self):
        # "acht" -> 4 Zeichen, also vier Nullbytes, nicht eines
        packet = build_osc_message("/preset/load", "acht")
        self.assertTrue(packet.endswith(b"acht\x00\x00\x00\x00"))
```

Und in `test_matches_python_osc_when_available` die Wertetabelle erweitern:

```python
        for address, value in (("/a/b", 0.25), ("/a/b", 7), ("/laengere/adresse", 1.5),
                               ("/preset/load", "standby"), ("/preset/save", "acht")):
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 webui/test_webui.py OscEncodingTest -v`
Expected: FAIL mit `TypeError: nicht unterstuetzter OSC-Argumenttyp: <class 'str'>`

- [ ] **Step 3: Implement** — in `build_osc_message`, vor dem `raise`:

```python
    if isinstance(value, str):
        return _osc_string(address) + _osc_string(",s") + _osc_string(value)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 webui/test_webui.py OscEncodingTest -v`
Expected: OK

- [ ] **Step 5: Commit**

```bash
git add webui/server.py webui/test_webui.py
git commit -m "webui: OSC-Encoder kann String-Argumente"
```

---

### Task 2: Namensvalidierung und Preset-Liste

**Files:**
- Modify: `webui/server.py` (neuer Block nach `parse_settings`)
- Test: `webui/test_webui.py` (neue Klassen)

**Interfaces:**
- Produces:
  - `valid_preset_name(name) -> Optional[str]` — Fehlermeldung oder `None`
  - `list_presets(directory: str) -> Tuple[List[str], Optional[str]]` — Namen ohne `.txt` (alphabetisch) und Fehlermeldung oder `None`
  - Konstanten `PRESET_NAME_MAX_LENGTH = 64`, `PRESET_IGNORED_ADDRESSES`

- [ ] **Step 1: Write the failing tests** — neue Klassen in `test_webui.py`, vor `class RealSettingsFileTest`:

```python
class PresetNameTest(unittest.TestCase):
    """Spiegel von PresetStore.isValidName() -- siehe PresetStore.java."""

    def test_accepts_plain_name(self):
        self.assertIsNone(valid_preset_name("standby"))
        self.assertIsNone(valid_preset_name("hang_drum_slow"))
        self.assertIsNone(valid_preset_name("abend-2"))
        self.assertIsNone(valid_preset_name("a"))
        self.assertIsNone(valid_preset_name("x" * 64))

    def test_rejects_empty(self):
        self.assertIsNotNone(valid_preset_name(""))
        self.assertIsNotNone(valid_preset_name(None))

    def test_rejects_too_long(self):
        self.assertIsNotNone(valid_preset_name("x" * 65))

    def test_rejects_uppercase(self):
        # Windows unterscheidet Standby und standby nicht -- waeren dieselbe Datei
        self.assertIsNotNone(valid_preset_name("Standby"))

    def test_rejects_path_traversal(self):
        for name in ("..", "../etc/passwd", "a/b", "a\\b", "a.txt", " a", "a b", "aä"):
            self.assertIsNotNone(valid_preset_name(name), name)


class PresetListTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)

    def write(self, filename):
        with open(os.path.join(self.dir, filename), "w", encoding="utf-8") as handle:
            handle.write("float\t/master/level\tx\t0.1\t0.0\t1.0\n")

    def test_sorted_without_extension(self):
        self.write("standby.txt")
        self.write("abend.txt")
        names, error = list_presets(self.dir)
        self.assertEqual(names, ["abend", "standby"])
        self.assertIsNone(error)

    def test_ignores_other_files_and_directories(self):
        self.write("standby.txt")
        self.write("notizen.md")
        os.mkdir(os.path.join(self.dir, "unterordner.txt"))
        names, _ = list_presets(self.dir)
        self.assertEqual(names, ["standby"])

    def test_skips_invalid_names(self):
        # koennte man ohnehin nicht laden -- PresetStore.list() uebergeht sie auch
        self.write("standby.txt")
        self.write("Gross.txt")
        names, _ = list_presets(self.dir)
        self.assertEqual(names, ["standby"])

    def test_missing_directory_reports_error(self):
        names, error = list_presets(os.path.join(self.dir, "gibtsnicht"))
        self.assertEqual(names, [])
        self.assertIsNotNone(error)
```

Dazu oben in `test_webui.py` die Importe ergaenzen:

```python
import shutil
import tempfile
```

und die `from server import (...)`-Liste um `list_presets, valid_preset_name` erweitern.

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 webui/test_webui.py PresetNameTest PresetListTest -v`
Expected: FAIL mit `ImportError: cannot import name 'valid_preset_name'`

- [ ] **Step 3: Implement** — in `server.py` nach `parse_settings()`:

```python
# ---------------------------------------------------------------------------
# Presets
# ---------------------------------------------------------------------------

PRESET_NAME_MAX_LENGTH = 64
PRESET_NAME_ALLOWED = set("abcdefghijklmnopqrstuvwxyz0123456789_-")

# Adressen, die beim Anwenden eines Presets uebergangen werden -- Spiegel von
# PresetStore.SILENTLY_IGNORED (Kommandos, kein Zustand) und
# PresetStore.EXCLUDED (Scheduler ist Transport, nicht Inhalt). Java ignoriert
# sie beim Laden; das UI darf danach also keine Regler bewegen, die der
# Sketch gar nicht gesetzt hat.
PRESET_IGNORED_ADDRESSES = set(TRIGGER_ADDRESSES) | {
    "/preset/scheduler/enabled",
    "/preset/scheduler/interval",
}


def valid_preset_name(name: Any) -> Optional[str]:
    """Wortgleicher Spiegel von PresetStore.isValidName() in Java.

    Rueckgabe: Fehlermeldung oder None. Java bleibt die Autoritaet -- dort
    geht es um Pfad-Traversal ("/preset/load ../../../etc/passwd"), hier nur
    darum, ungueltige Eingaben gar nicht erst rauszuschicken.
    """
    if not isinstance(name, str) or not name:
        return "Preset-Name ist leer"
    if len(name) > PRESET_NAME_MAX_LENGTH:
        return "Preset-Name laenger als %d Zeichen" % PRESET_NAME_MAX_LENGTH
    for char in name:
        if char not in PRESET_NAME_ALLOWED:
            return ("Preset-Name enthaelt unzulaessiges Zeichen %r -- erlaubt "
                    "sind a-z, 0-9, Unterstrich und Bindestrich" % char)
    return None


def list_presets(directory: str) -> Tuple[List[str], Optional[str]]:
    """Namen aller Preset-Dateien, alphabetisch, ohne die Endung .txt.

    Spiegel von PresetStore.list(): Dateien mit unzulaessigem Namen werden
    uebergangen -- sie liessen sich ohnehin nicht laden.
    """
    try:
        entries = os.listdir(directory)
    except OSError as exc:
        return [], "Preset-Ordner nicht lesbar: %s" % exc
    names = []
    for entry in entries:
        if not entry.endswith(".txt"):
            continue
        if not os.path.isfile(os.path.join(directory, entry)):
            continue
        bare = entry[:-len(".txt")]
        if valid_preset_name(bare) is None:
            names.append(bare)
    names.sort()
    return names, None
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 webui/test_webui.py PresetNameTest PresetListTest -v`
Expected: OK

- [ ] **Step 5: Commit**

```bash
git add webui/server.py webui/test_webui.py
git commit -m "webui: Preset-Namensvalidierung und Ordner-Auflistung"
```

---

### Task 3: Preset-Werte in den ParameterStore uebernehmen

Die Logik, die aus einer geparsten Preset-Datei die Werte fuer die Regler macht — bewusst als freie Funktion, damit sie ohne Flask testbar ist.

**Files:**
- Modify: `webui/server.py` (nach `list_presets`)
- Test: `webui/test_webui.py` (neue Klasse)

**Interfaces:**
- Consumes: `valid_preset_name`, `PRESET_IGNORED_ADDRESSES` (Task 2); `ParameterStore`, `Parameter.coerce`, `Parameter.ui_range`, `parse_settings` (bestehend)
- Produces: `apply_preset_entries(store: ParameterStore, entries: List[Parameter]) -> Dict[str, Any]` mit den Schluesseln `values`, `unknown`, `outOfRange`

- [ ] **Step 1: Write the failing test** — neue Klasse in `test_webui.py`:

```python
class PresetApplyTest(unittest.TestCase):
    def setUp(self):
        self.store = ParameterStore(path="egal")
        self.store.parameters = parse_settings(SAMPLE)
        self.store.by_address = {p.address: p for p in self.store.parameters}
        self.store.values = {p.address: p.coerce(p.value)
                             for p in self.store.parameters}

    def apply(self, text):
        return server.apply_preset_entries(self.store, parse_settings(text))

    def test_sets_known_addresses(self):
        result = self.apply("float\t/nodes/times/recover\tx\t7.5\t0.0\t10.0\n")
        self.assertAlmostEqual(result["values"]["/nodes/times/recover"], 7.5)
        self.assertAlmostEqual(self.store.values["/nodes/times/recover"], 7.5)

    def test_int_stays_int(self):
        result = self.apply("int\t/net/impulse/speed\tx\t42\t1\t1500\n")
        self.assertEqual(result["values"]["/net/impulse/speed"], 42)
        self.assertIsInstance(result["values"]["/net/impulse/speed"], int)

    def test_unknown_address_is_reported_not_applied(self):
        result = self.apply("float\t/gibt/es/nicht\tx\t1.0\t0.0\t2.0\n")
        self.assertEqual(result["unknown"], ["/gibt/es/nicht"])
        self.assertEqual(result["values"], {})

    def test_value_outside_ui_range_is_reported(self):
        # /net/impulse/speed: Java-Range 1..1500, UI-Range 1..100
        result = self.apply("int\t/net/impulse/speed\tx\t160\t1\t1500\n")
        self.assertEqual(result["values"]["/net/impulse/speed"], 160)
        self.assertEqual(result["outOfRange"],
                         [{"address": "/net/impulse/speed", "value": 160, "shown": 100}])

    def test_value_inside_ui_range_is_not_reported(self):
        result = self.apply("int\t/net/impulse/speed\tx\t16\t1\t1500\n")
        self.assertEqual(result["outOfRange"], [])

    def test_clamps_to_java_range_not_to_file_range(self):
        # Die min/max-Spalten der Datei werden ignoriert -- es gelten die
        # Grenzen aus remoteSettings.txt, genau wie in PresetStore.applyPreset
        result = self.apply("float\t/nodes/times/recover\tx\t99.0\t0.0\t1000.0\n")
        self.assertAlmostEqual(result["values"]["/nodes/times/recover"], 10.0)

    def test_ignored_addresses_are_skipped_silently(self):
        text = ("int\t/net/activateNode\tx\t5\t0\t100\n"
                "int\t/preset/scheduler/enabled\tx\t0\t0\t1\n")
        result = self.apply(text)
        self.assertEqual(result["values"], {})
        self.assertEqual(result["unknown"], [])
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 webui/test_webui.py PresetApplyTest -v`
Expected: FAIL mit `AttributeError: module 'server' has no attribute 'apply_preset_entries'`

- [ ] **Step 3: Implement** — in `server.py` nach `list_presets`:

```python
def apply_preset_entries(store: "ParameterStore",
                         entries: List[Parameter]) -> Dict[str, Any]:
    """Uebernimmt die Werte einer geparsten Preset-Datei in den Store.

    Geklemmt wird auf die Range aus remoteSettings.txt, nicht auf die aus der
    Preset-Datei -- dieselbe Regel wie in PresetStore.applyPreset() auf der
    Java-Seite, damit aeltere Presets nach einer Bereichsaenderung korrekt
    bleiben.

    Die Werte gehen bewusst NICHT als OSC raus: das Anwenden macht imPulse
    selbst nach /preset/load, hier wird nur die Anzeige nachgezogen.
    """
    values: Dict[str, Any] = {}
    unknown: List[str] = []
    out_of_range: List[Dict[str, Any]] = []
    for entry in entries:
        if entry.address in PRESET_IGNORED_ADDRESSES:
            continue
        param = store.get(entry.address)
        if param is None:
            unknown.append(entry.address)
            continue
        value = param.coerce(entry.value)
        ui_min, ui_max = param.ui_range()
        low, high = min(ui_min, ui_max), max(ui_min, ui_max)
        shown = max(low, min(high, value))
        if shown != value:
            out_of_range.append({"address": entry.address, "value": value,
                                 "shown": shown})
        values[entry.address] = value
        store.store(entry.address, value)
    return {"values": values, "unknown": unknown, "outOfRange": out_of_range}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 webui/test_webui.py PresetApplyTest -v`
Expected: OK

- [ ] **Step 5: Commit**

```bash
git add webui/server.py webui/test_webui.py
git commit -m "webui: Preset-Werte in den ParameterStore uebernehmen"
```

---

### Task 4: Endpoints und Konfiguration

**Files:**
- Modify: `webui/server.py` (`create_app`, `main`, Konstantenblock oben)

**Interfaces:**
- Consumes: alles aus Task 1–3
- Produces:
  - `wait_for_preset_file(path, previous_mtime, timeout=1.0, step=0.05) -> bool`
  - `create_app(settings_path, osc_host, osc_port, presets_path)` — vierter Parameter neu
  - Routen `GET /api/presets`, `POST /api/preset/load`, `POST /api/preset/save`

- [ ] **Step 1: Konstante und Wartefunktion**

Oben zu den Vorgaben (`DEFAULT_SETTINGS_PATH` etc.):

```python
DEFAULT_PRESETS_DIRNAME = "presets"
PRESET_SAVE_TIMEOUT_S = 1.0
PRESET_SAVE_POLL_S = 0.05
```

`import time` zu den Importen. Nach `apply_preset_entries`:

```python
def default_presets_path(settings_path: str) -> str:
    """data/presets liegt neben data/remoteSettings.txt."""
    return os.path.join(os.path.dirname(os.path.abspath(settings_path)),
                        DEFAULT_PRESETS_DIRNAME)


def wait_for_preset_file(path: str, previous_mtime: Optional[float],
                         timeout: float = PRESET_SAVE_TIMEOUT_S,
                         step: float = PRESET_SAVE_POLL_S) -> bool:
    """Wartet darauf, dass imPulse die Preset-Datei geschrieben hat.

    /preset/save ist asynchron: der Sketch schreibt die Datei erst im
    naechsten draw()-Durchlauf. Verglichen wird die mtime, nicht nur die
    Existenz -- sonst waere das Ueberschreiben eines vorhandenen Presets
    nicht von "nichts passiert" zu unterscheiden.
    """
    deadline = time.monotonic() + timeout
    while True:
        try:
            current = os.path.getmtime(path)
            if previous_mtime is None or current != previous_mtime:
                return True
        except OSError:
            pass
        if time.monotonic() >= deadline:
            return False
        time.sleep(step)
```

- [ ] **Step 2: Routen in `create_app`**

Signatur aendern zu `def create_app(settings_path, osc_host, osc_port, presets_path):`, und nach `api_set` einfuegen:

```python
    def preset_file(name: str) -> str:
        return os.path.join(presets_path, name + ".txt")

    def preset_list_payload() -> Dict[str, Any]:
        names, error = list_presets(presets_path)
        return {"ok": True, "presets": names, "dir": presets_path,
                "error": error}

    @app.route("/api/presets")
    def api_presets():
        return jsonify(preset_list_payload())

    @app.route("/api/preset/load", methods=["POST"])
    def api_preset_load():
        body = request.get_json(silent=True) or {}
        name = body.get("name")
        problem = valid_preset_name(name)
        if problem is not None:
            return jsonify({"ok": False, "error": problem}), 400
        path = preset_file(name)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                text = handle.read()
        except OSError as exc:
            return jsonify({"ok": False,
                            "error": "Preset nicht lesbar: %s" % exc}), 404
        entries = parse_settings(text)
        if not entries:
            return jsonify({"ok": False,
                            "error": "Preset %s enthaelt keine gueltige Zeile"
                                     % name}), 400
        # Erst senden, dann die Anzeige nachziehen: imPulse wendet das Preset
        # selbst an, hier werden nur die Regler nachgefuehrt.
        sender.send("/preset/load", name)
        result = apply_preset_entries(store, entries)
        result.update({"ok": True, "name": name})
        return jsonify(result)

    @app.route("/api/preset/save", methods=["POST"])
    def api_preset_save():
        body = request.get_json(silent=True) or {}
        name = body.get("name")
        problem = valid_preset_name(name)
        if problem is not None:
            return jsonify({"ok": False, "error": problem}), 400
        path = preset_file(name)
        try:
            previous = os.path.getmtime(path)
            existed = True
        except OSError:
            previous = None
            existed = False
        sender.send("/preset/save", name)
        if not wait_for_preset_file(path, previous):
            return jsonify({
                "ok": False,
                "error": "imPulse hat %s.txt nicht geschrieben -- laeuft der "
                         "Sketch?" % name,
            }), 504
        payload = preset_list_payload()
        payload.update({"name": name, "overwritten": existed})
        return jsonify(payload)
```

Im `index()`-Handler das Bootstrap-JSON um die Liste erweitern, damit das Dropdown ohne zweiten Request gefuellt ist:

```python
                "presets": preset_list_payload(),
```

- [ ] **Step 3: Kommandozeile in `main`**

```python
    parser.add_argument("--presets",
                        default=os.environ.get("IMPULSE_PRESETS"),
                        help="Ordner mit den Preset-Dateien "
                             "(Vorgabe: presets/ neben --settings)")
```

und darunter:

```python
    settings_path = os.path.abspath(args.settings)
    presets_path = os.path.abspath(args.presets) if args.presets \
        else default_presets_path(settings_path)
    app = create_app(settings_path, args.osc_host, args.osc_port, presets_path)

    print("[webui] remoteSettings: %s" % settings_path)
    print("[webui] Presets:        %s" % presets_path)
```

- [ ] **Step 4: Test fuer die Wartefunktion und den Vorgabepfad** — in `test_webui.py`:

```python
class PresetSaveWaitTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        self.path = os.path.join(self.dir, "neu.txt")

    def test_returns_false_when_nothing_appears(self):
        self.assertFalse(server.wait_for_preset_file(self.path, None,
                                                     timeout=0.1, step=0.01))

    def test_returns_true_when_file_appears(self):
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write("x")
        self.assertTrue(server.wait_for_preset_file(self.path, None,
                                                    timeout=0.1, step=0.01))

    def test_unchanged_mtime_counts_as_not_written(self):
        with open(self.path, "w", encoding="utf-8") as handle:
            handle.write("x")
        mtime = os.path.getmtime(self.path)
        self.assertFalse(server.wait_for_preset_file(self.path, mtime,
                                                     timeout=0.1, step=0.01))

    def test_default_directory_sits_next_to_settings(self):
        self.assertEqual(
            server.default_presets_path(os.path.join("a", "data", "remoteSettings.txt")),
            os.path.join(os.path.abspath(os.path.join("a", "data")), "presets"))
```

- [ ] **Step 5: Run tests**

Run: `python3 webui/test_webui.py -v`
Expected: OK, alle Suiten

- [ ] **Step 6: Commit**

```bash
git add webui/server.py webui/test_webui.py
git commit -m "webui: Endpoints fuer Preset-Liste, Laden und Speichern"
```

---

### Task 5: Preset-Sektion im UI

**Files:**
- Modify: `webui/templates/index.html`, `webui/static/app.js`, `webui/static/style.css`

**Interfaces:**
- Consumes: `GET /api/presets`, `POST /api/preset/load`, `POST /api/preset/save` (Task 4); `bootstrap.presets`
- Produces: nichts fuer spaetere Tasks

- [ ] **Step 1: Markup** — in `index.html` zwischen `<p class="status">` und `<main id="groups">`:

```html
  <section class="presets" id="presets">
    <h2>Presets</h2>
    <div class="preset-row">
      <select id="presetSelect" aria-label="Preset auswaehlen"></select>
      <button type="button" id="presetLoad" title="Gewaehltes Preset laden (/preset/load)">Laden</button>
    </div>
    <div class="preset-row">
      <input type="text" id="presetName" placeholder="neuer-preset-name" maxlength="64"
             aria-label="Name fuer das neue Preset">
      <button type="button" id="presetSave" title="Aktuelle Werte unter diesem Namen speichern (/preset/save)">Speichern</button>
    </div>
  </section>
```

- [ ] **Step 2: JS** — in `app.js` zu den Element-Handles oben:

```javascript
const presetSelectEl = document.getElementById('presetSelect');
const presetLoadEl = document.getElementById('presetLoad');
const presetNameEl = document.getElementById('presetName');
const presetSaveEl = document.getElementById('presetSave');
```

und einen eigenen Abschnitt vor `render(bootstrap)`:

```javascript
// ---------------------------------------------------------------------------
// Presets
//
// Die Liste kommt vom Dateisystem des Servers (er laeuft auf derselben
// Maschine wie imPulse), die Kommandos gehen als OSC-String raus. Nach dem
// Laden schickt der Server die Preset-Werte zurueck, damit die Regler
// mitziehen -- still gesetzt, also ohne ein zweites OSC auszuloesen.
// ---------------------------------------------------------------------------

/* Wortgleicher Spiegel von valid_preset_name() in server.py und
 * PresetStore.isValidName() in Java. Java bleibt die Autoritaet; hier geht es
 * nur darum, eine ungueltige Eingabe gar nicht erst rauszuschicken. */
function presetNameProblem(name) {
  if (!name) { return 'Bitte einen Namen eingeben'; }
  if (name.length > 64) { return 'Hoechstens 64 Zeichen'; }
  if (!/^[a-z0-9_-]+$/.test(name)) {
    return 'Erlaubt sind nur a-z, 0-9, Unterstrich und Bindestrich';
  }
  return null;
}

function fillPresets(payload) {
  const names = (payload && payload.presets) || [];
  const previous = presetSelectEl.value;
  presetSelectEl.innerHTML = '';
  names.forEach((name) => {
    const option = document.createElement('option');
    option.value = name;
    option.textContent = name;
    presetSelectEl.appendChild(option);
  });
  if (!names.length) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = 'keine Presets';
    presetSelectEl.appendChild(option);
  }
  presetSelectEl.disabled = !names.length;
  presetLoadEl.disabled = !names.length;
  if (names.indexOf(previous) >= 0) { presetSelectEl.value = previous; }
  if (payload && payload.error) { setStatus(payload.error, 'warn'); }
}

async function loadPreset() {
  const name = presetSelectEl.value;
  if (!name) { return; }
  presetLoadEl.disabled = true;
  try {
    const response = await fetch('/api/preset/load', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name }),
    });
    const data = await response.json();
    if (!response.ok || !data.ok) {
      setStatus('Laden fehlgeschlagen: ' + (data.error || 'HTTP ' + response.status), 'err');
      if (response.status === 404) { refreshPresets(); }
      return;
    }
    let touched = 0;
    Object.keys(data.values || {}).forEach((address) => {
      const control = controls.get(address);
      if (!control) { return; }
      control.set(data.values[address], true);
      control.flash();
      touched += 1;
    });
    colorCards.forEach(syncColorCard);

    let text = `Preset "${name}" geladen – ${touched} Regler nachgezogen`;
    let level = 'ok';
    if (data.outOfRange && data.outOfRange.length) {
      text += ' | ausserhalb der UI-Range: ' + data.outOfRange
        .map((e) => `${e.address} = ${e.value} (Regler zeigt ${e.shown})`).join(', ');
      level = 'warn';
    }
    if (data.unknown && data.unknown.length) {
      text += ' | nicht in remoteSettings.txt: ' + data.unknown.join(', ');
      level = 'warn';
    }
    setStatus(text, level);
  } catch (err) {
    setStatus('Laden fehlgeschlagen: ' + err, 'err');
  } finally {
    presetLoadEl.disabled = presetSelectEl.disabled;
  }
}

async function savePreset() {
  const name = presetNameEl.value.trim();
  const problem = presetNameProblem(name);
  if (problem) {
    setStatus(problem, 'err');
    presetNameEl.focus();
    return;
  }
  presetSaveEl.disabled = true;
  setStatus(`Speichere "${name}" …`);
  try {
    const response = await fetch('/api/preset/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name }),
    });
    const data = await response.json();
    if (!response.ok || !data.ok) {
      setStatus('Speichern fehlgeschlagen: ' + (data.error || 'HTTP ' + response.status), 'err');
      return;
    }
    fillPresets(data);
    presetSelectEl.value = name;
    presetNameEl.value = '';
    setStatus(`Preset "${name}" ${data.overwritten ? 'ueberschrieben' : 'gespeichert'}`, 'ok');
  } catch (err) {
    setStatus('Speichern fehlgeschlagen: ' + err, 'err');
  } finally {
    presetSaveEl.disabled = false;
  }
}

async function refreshPresets() {
  try {
    const response = await fetch('/api/presets');
    fillPresets(await response.json());
  } catch (err) {
    setStatus('Preset-Liste nicht abrufbar: ' + err, 'err');
  }
}

presetLoadEl.addEventListener('click', loadPreset);
presetSaveEl.addEventListener('click', savePreset);
presetNameEl.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') { savePreset(); }
});

fillPresets(bootstrap.presets);
```

- [ ] **Step 3: CSS** — an `style.css` anhaengen, im Stil der vorhandenen Regeln:

```css
.presets {
  background: var(--card, #1b1e24);
  border-radius: 8px;
  padding: 0.8rem 1rem;
  margin: 0 0 1rem 0;
}

.presets h2 { margin: 0 0 0.6rem 0; }

.preset-row {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  margin-bottom: 0.4rem;
}

.preset-row select,
.preset-row input[type="text"] { flex: 1; min-width: 0; }
```

Vor dem Schreiben `style.css` lesen und die tatsaechlich vorhandenen
Variablen-/Klassennamen uebernehmen, statt neue zu erfinden.

- [ ] **Step 4: Pruefen, ohne die Installation anzufassen**

Da hier kein imPulse laeuft, wird der Server gegen die Repo-Dateien gestartet und
per `curl` gepruefte Antwort geholt. OSC geht dabei ins Leere (UDP an
127.0.0.1:8001, niemand hoert zu) — genau richtig, es soll ja nichts passieren.

```bash
cd webui && python3 -m venv .venv && .venv/bin/pip -q install -r requirements.txt
.venv/bin/python server.py --port 8099 >/tmp/webui.log 2>&1 &
sleep 2
curl -s localhost:8099/api/presets
curl -s -X POST localhost:8099/api/preset/load -H 'Content-Type: application/json' -d '{"name":"standby"}'
curl -s -X POST localhost:8099/api/preset/load -H 'Content-Type: application/json' -d '{"name":"../etc/passwd"}'
curl -s -X POST localhost:8099/api/preset/save -H 'Content-Type: application/json' -d '{"name":"testpreset"}'
curl -s localhost:8099/ | grep -c presetSelect
kill %1
```

Erwartet: Liste mit `hang_drum_slow` und `standby`; Laden `ok:true` mit `values`;
Traversal `400`; Speichern `504` mit der „laeuft der Sketch?"-Meldung (imPulse
laeuft hier nicht — das ist der Beweis, dass die Wartelogik greift, und es
entsteht **keine** neue Datei); die Seite enthaelt das Dropdown.

- [ ] **Step 5: Commit**

```bash
git add webui/templates/index.html webui/static/app.js webui/static/style.css
git commit -m "webui: Preset-Sektion mit Dropdown, Laden und Speichern"
```

---

### Task 6: Dokumentation

**Files:**
- Modify: `webui/README.md`, `CLAUDE.md`

- [ ] **Step 1: `webui/README.md`** — neuer Abschnitt „Presets" nach „Was das UI anzeigt", mit: was die Sektion tut, dass die Liste vom Dateisystem kommt und nicht per OSC, die Namensregel, dass Speichern bis zu 1 s auf imPulse wartet und sonst einen klaren Fehler zeigt, dass ein vorhandenes Preset ohne Rueckfrage ueberschrieben wird, und dass es kein Loeschen gibt (Datei von Hand loeschen). In der Optionstabelle `--presets` / `IMPULSE_PRESETS` ergaenzen. Im Abschnitt „Tests" die neuen Suiten nennen.

- [ ] **Step 2: `CLAUDE.md`** — im Abschnitt „Web-UI (webui/)" einen Absatz ergaenzen: die Preset-Sektion spricht `/preset/load` und `/preset/save` per OSC, liest die Liste aber direkt aus `data/presets/`; die Namensvalidierung ist ein Spiegel von `PresetStore.isValidName()`, Java bleibt die Autoritaet; nach dem Laden zieht der Server die Regleranzeige aus derselben Datei nach, weil Preset- und `remoteSettings.txt`-Format identisch sind.

- [ ] **Step 3: Gesamte Testlaeufe**

```bash
python3 webui/test_webui.py
test/run.sh
```

Expected: beide gruen. `test/run.sh` ist unveraendert — an Java wurde keine Zeile geaendert.

- [ ] **Step 4: Commit**

```bash
git add webui/README.md CLAUDE.md
git commit -m "docs: Preset-Sektion im Web-UI dokumentieren"
```

---

## Self-Review

- **Spec-Abdeckung:** Endpoints (Task 4), Namensvalidierung dreistufig (Task 2 Python, Task 5 JS, Java unveraendert), Laden mit `unknown`/`outOfRange` (Task 3+5), Speichern mit `mtime`-Warten und `overwritten` (Task 4+5), OSC-String (Task 1), UI-Sektion (Task 5), `--presets` (Task 4), Tests (Task 1–4), Doku (Task 6). Die offene Frage „Loeschen" steht in der Spec und wird in Task 6 als Nicht-Umfang im README erwaehnt.
- **Typen:** `apply_preset_entries` gibt ueberall `{"values", "unknown", "outOfRange"}` zurueck; `list_presets` ueberall `(names, error)`; `valid_preset_name` ueberall `Optional[str]` mit `None` = gueltig.
