/* imPulse Web-UI - Vanilla JS, kein Framework, kein Build-Schritt.
 *
 * Aufbau: die Parameterliste kommt fertig gruppiert vom Server (gelesen aus
 * data/remoteSettings.txt). Jede Aenderung geht sofort an POST /api/set,
 * waehrend eines Reglerzugs auf ~150 ms gedrosselt; der Server normalisiert,
 * sendet OSC und meldet in "applied" zurueck, welche Adressen dabei
 * tatsaechlich gesetzt wurden -- daran haengt die Anzeige der von der
 * Speed-Kopplung mitgezogenen Werte.
 */
'use strict';

const DEBOUNCE_MS = 150;
const COUPLING_STORAGE_KEY = 'imPulse.speedCoupling';

const controls = new Map();   // address -> Steuerelement-Handle
const colorCards = [];        // { base, input, components }
const pending = new Map();    // Sende-Schluessel -> { last, timer, updates }

const groupsEl = document.getElementById('groups');
const statusEl = document.getElementById('status');
const metaEl = document.getElementById('meta');
const couplingEl = document.getElementById('coupling');
const reloadEl = document.getElementById('reload');
const presetSelectEl = document.getElementById('presetSelect');
const presetLoadEl = document.getElementById('presetLoad');
const presetNameEl = document.getElementById('presetName');
const presetSaveEl = document.getElementById('presetSave');

let bootstrap = {};
try {
  bootstrap = JSON.parse(document.getElementById('bootstrap').textContent);
} catch (err) {
  bootstrap = {};
}

// ---------------------------------------------------------------------------
// Hilfen
// ---------------------------------------------------------------------------

function decimalsFor(value) {
  if (!isFinite(value)) { return 0; }
  const text = String(value);
  const dot = text.indexOf('.');
  return dot < 0 ? 0 : Math.min(6, text.length - dot - 1);
}

/* Nachkommastellen so waehlen, dass auch die Bereichsgrenzen noch lesbar sind:
 * /net/impulse/lifetime reicht von 0.0001 bis 1.0, mit den zwei Stellen der
 * Schrittweite waere die Untergrenze schlicht "0.000". */
function decimalsForParam(param) {
  return Math.max(decimalsFor(param.step), decimalsFor(param.min),
                  decimalsFor(param.max));
}

function formatValue(value, param) {
  return Number(value).toFixed(decimalsForParam(param));
}

function roundToStep(value, param) {
  const step = Number(param.step);
  if (!step) { return value; }
  return Number((Math.round(value / step) * step).toFixed(decimalsForParam(param)));
}

function shortRange(param) {
  const d = decimalsForParam(param);
  return `${Number(param.min).toFixed(d)} … ${Number(param.max).toFixed(d)}`;
}

function splitAddress(address) {
  const cut = address.lastIndexOf('/');
  if (cut < 0) { return { prefix: '', leaf: address }; }
  return { prefix: address.slice(0, cut + 1), leaf: address.slice(cut + 1) };
}

function setStatus(text, level) {
  statusEl.textContent = text || ' ';
  statusEl.className = 'status' + (level ? ' ' + level : '');
}

// java.awt.Color.getHSBColor erwartet h/s/b jeweils in 0..1 - dieselbe
// Konvention wie LedColor.setFromHSB, also hier ebenso rechnen.
function hsbToRgb(h, s, v) {
  const i = Math.floor(h * 6);
  const f = h * 6 - i;
  const p = v * (1 - s);
  const q = v * (1 - f * s);
  const t = v * (1 - (1 - f) * s);
  switch (i % 6) {
    case 0: return [v, t, p];
    case 1: return [q, v, p];
    case 2: return [p, v, t];
    case 3: return [p, q, v];
    case 4: return [t, p, v];
    default: return [v, p, q];
  }
}

function rgbToHsb(r, g, b) {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d !== 0) {
    if (max === r) { h = ((g - b) / d) % 6; }
    else if (max === g) { h = (b - r) / d + 2; }
    else { h = (r - g) / d + 4; }
    h /= 6;
    if (h < 0) { h += 1; }
  }
  return [h, max === 0 ? 0 : d / max, max];
}

function hsbToHex(h, s, v) {
  const rgb = hsbToRgb(h, s, v).map((c) => {
    const byte = Math.round(Math.min(1, Math.max(0, c)) * 255);
    return byte.toString(16).padStart(2, '0');
  });
  return '#' + rgb.join('');
}

function hexToHsb(hex) {
  const value = parseInt(hex.slice(1), 16);
  return rgbToHsb(((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255,
                  (value & 255) / 255);
}

// ---------------------------------------------------------------------------
// Senden
// ---------------------------------------------------------------------------

async function postUpdates(updates) {
  const body = {
    updates: updates,
    coupleSpeed: couplingEl.checked,
  };
  let response;
  try {
    response = await fetch('/api/set', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch (err) {
    setStatus('Netzwerkfehler: ' + err, 'err');
    return;
  }
  let data;
  try {
    data = await response.json();
  } catch (err) {
    setStatus('Unerwartete Antwort vom Server (HTTP ' + response.status + ')', 'err');
    return;
  }
  if (!response.ok || !data.ok) {
    setStatus('Fehler: ' + (data.error || 'HTTP ' + response.status), 'err');
    return;
  }

  const origin = new Set(updates.map((u) => u.address));
  const echoed = [];
  data.applied.forEach((entry) => {
    if (!origin.has(entry.address)) {
      const control = controls.get(entry.address);
      if (control) {
        control.set(entry.value, true);
        control.flash();
      }
      echoed.push(entry.address);
    }
  });
  colorCards.forEach(syncColorCard);

  const sentText = data.applied
    .filter((entry) => origin.has(entry.address))
    .map((entry) => `${entry.address} = ${entry.value} (OSC ${entry.sent})`)
    .join(', ');
  let text = sentText;
  if (echoed.length) {
    text += ' → mitskaliert: ' + echoed.join(', ');
  }
  if (data.skipped && data.skipped.length) {
    text += ' | uebersprungen: ' + data.skipped
      .map((s) => `${s.address} (${s.reason})`).join(', ');
    setStatus(text, 'warn');
  } else {
    setStatus(text, 'ok');
  }
}

/* Kein reiner Trailing-Debounce: waehrend eines langen Reglerzugs soll das
 * Netz mitlaufen und nicht erst beim Loslassen etwas passieren. Also erste
 * Aenderung sofort, danach hoechstens alle DEBOUNCE_MS eine Nachricht, und
 * der letzte Wert geht in jedem Fall raus. */
function queueSendMany(key, updates) {
  const slot = pending.get(key) || { last: 0, timer: null };
  pending.set(key, slot);
  slot.updates = updates;

  const now = Date.now();
  if (slot.timer === null && now - slot.last >= DEBOUNCE_MS) {
    slot.last = now;
    postUpdates(slot.updates);
    return;
  }
  if (slot.timer !== null) { return; }
  slot.timer = setTimeout(() => {
    slot.timer = null;
    slot.last = Date.now();
    postUpdates(slot.updates);
  }, DEBOUNCE_MS - (now - slot.last));
}

function queueSend(address, value) {
  queueSendMany(address, [{ address: address, value: value }]);
}

// ---------------------------------------------------------------------------
// Steuerelemente
// ---------------------------------------------------------------------------

function makeHead(param) {
  const head = document.createElement('div');
  head.className = 'param-head';

  const name = document.createElement('span');
  name.className = 'param-name';
  name.title = param.address + (param.description &&
    param.description !== 'space for descripiton' ? ' – ' + param.description : '');
  const parts = splitAddress(param.address);
  const prefix = document.createElement('span');
  prefix.className = 'prefix';
  prefix.textContent = parts.prefix;
  name.appendChild(prefix);
  name.appendChild(document.createTextNode(parts.leaf));

  const range = document.createElement('span');
  range.className = 'param-range';
  range.textContent = param.type === 'int'
    ? `int ${param.min} … ${param.max}` : shortRange(param);

  head.appendChild(name);
  head.appendChild(range);
  return head;
}

function buildSlider(param, initial, onChange) {
  const wrap = document.createElement('div');
  wrap.className = 'param';
  wrap.appendChild(makeHead(param));

  const body = document.createElement('div');
  body.className = 'param-body';

  const range = document.createElement('input');
  range.type = 'range';
  range.min = param.min;
  range.max = param.max;
  range.step = param.step;
  range.value = initial;

  const number = document.createElement('input');
  number.type = 'number';
  number.min = param.min;
  number.max = param.max;
  // "any" statt param.step: die Bereichsgrenzen liegen nicht immer auf dem
  // Raster der Schrittweite (z.B. 0.0001 … 0.5 bei Schritt 0.001), sonst
  // meckert der Browser bei einem exakt eingetippten Grenzwert.
  number.step = 'any';
  number.value = param.type === 'int' ? String(Math.round(initial))
                                      : formatValue(initial, param);

  body.appendChild(range);
  body.appendChild(number);
  wrap.appendChild(body);

  let current = Number(initial);

  function apply(value, silent) {
    let next = Number(value);
    if (!isFinite(next)) { return; }
    next = Math.min(param.max, Math.max(param.min, next));
    if (param.type === 'int') { next = Math.round(next); }
    current = next;
    range.value = next;
    // waehrend des Tippens das Zahlenfeld nicht unter den Fingern umschreiben
    if (silent || document.activeElement !== number) {
      number.value = param.type === 'int' ? String(next) : formatValue(next, param);
    }
    if (!silent) { onChange(next); }
  }

  range.addEventListener('input', () => apply(range.value, false));
  number.addEventListener('input', () => {
    if (number.value === '' || number.value === '-') { return; }
    apply(number.value, false);
  });
  number.addEventListener('blur', () => apply(current, true));

  return {
    element: wrap,
    set: (value, silent) => apply(value, silent !== false),
    get: () => current,
    flash: () => {
      wrap.classList.remove('coupled');
      void wrap.offsetWidth;
      wrap.classList.add('coupled');
    },
  };
}

function buildToggle(param, initial, onChange) {
  const wrap = document.createElement('div');
  wrap.className = 'param';
  wrap.appendChild(makeHead(param));

  const body = document.createElement('label');
  body.className = 'toggle-body';
  const box = document.createElement('input');
  box.type = 'checkbox';
  box.checked = Number(initial) >= 1;
  const label = document.createElement('span');
  label.textContent = box.checked ? 'an (1)' : 'aus (0)';
  body.appendChild(box);
  body.appendChild(label);
  wrap.appendChild(body);

  function apply(value, silent) {
    const on = Number(value) >= 1;
    box.checked = on;
    label.textContent = on ? 'an (1)' : 'aus (0)';
    if (!silent) { onChange(on ? 1 : 0); }
  }

  box.addEventListener('change', () => apply(box.checked ? 1 : 0, false));

  return {
    element: wrap,
    set: (value, silent) => apply(value, silent !== false),
    get: () => (box.checked ? 1 : 0),
    flash: () => {
      wrap.classList.remove('coupled');
      void wrap.offsetWidth;
      wrap.classList.add('coupled');
    },
  };
}

function buildParam(param, value) {
  const handle = param.widget === 'toggle'
    ? buildToggle(param, value, (v) => queueSend(param.address, v))
    : buildSlider(param, value, (v) => queueSend(param.address, v));
  // Kurzerklaerung, wo die Adresse allein nicht verraet, was der Regler tut
  // (DESCRIPTIONS in server.py). Sichtbar statt nur als Tooltip: im Dunkeln
  // neben der Installation findet niemand einen Hover-Text.
  if (param.help) {
    const help = document.createElement('p');
    help.className = 'help';
    help.textContent = param.help;
    handle.element.appendChild(help);
  }
  controls.set(param.address, handle);
  return handle.element;
}

/* Trigger: kein Regler, keine Zustandsanzeige (der Server haelt hier ohnehin
 * keinen sinnvollen "aktuellen Wert"), ein Button pro moeglichem Zielwert
 * waere bei 30+ Stripes zu viel -- stattdessen ein Zahlenfeld + "Ausloesen"-
 * Knopf, der genau einmal sendet statt bei jeder Reglerbewegung. */
function buildTrigger(param) {
  const wrap = document.createElement('div');
  wrap.className = 'param trigger';

  const head = document.createElement('div');
  head.className = 'param-head';
  const name = document.createElement('span');
  name.className = 'param-name';
  name.title = param.address + (param.description &&
    param.description !== 'space for descripiton' ? ' – ' + param.description : '');
  const parts = splitAddress(param.address);
  const prefix = document.createElement('span');
  prefix.className = 'prefix';
  prefix.textContent = parts.prefix;
  name.appendChild(prefix);
  name.appendChild(document.createTextNode(parts.leaf));
  const range = document.createElement('span');
  range.className = 'param-range';
  range.textContent = `Trigger, int ${param.min} … ${param.max}`;
  head.appendChild(name);
  head.appendChild(range);
  wrap.appendChild(head);

  const body = document.createElement('div');
  body.className = 'param-body';
  const number = document.createElement('input');
  number.type = 'number';
  number.min = param.min;
  number.max = param.max;
  number.step = '1';
  number.value = param.min;
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = 'Ausloesen';
  button.addEventListener('click', () => {
    let value = Math.round(Number(number.value));
    if (!isFinite(value)) { value = param.min; }
    value = Math.min(param.max, Math.max(param.min, value));
    number.value = value;
    postUpdates([{ address: param.address, value: value }]);
  });
  body.appendChild(number);
  body.appendChild(button);
  wrap.appendChild(body);

  return wrap;
}

function syncColorCard(card) {
  const h = controls.get(card.components.hue.address).get();
  const s = controls.get(card.components.sat.address).get();
  const b = controls.get(card.components.bright.address).get();
  card.input.value = hsbToHex(h, s, b);
}

function buildColorCard(control, values) {
  const wrap = document.createElement('div');
  wrap.className = 'param color';

  const head = document.createElement('div');
  head.className = 'param-head';
  const name = document.createElement('span');
  name.className = 'param-name';
  name.title = control.base + '/{Hue,Sat,Bright}';
  const parts = splitAddress(control.base);
  const prefix = document.createElement('span');
  prefix.className = 'prefix';
  prefix.textContent = parts.prefix;
  name.appendChild(prefix);
  name.appendChild(document.createTextNode(parts.leaf));
  const kind = document.createElement('span');
  kind.className = 'param-range';
  kind.textContent = 'Farbe (HSB)';
  head.appendChild(name);
  head.appendChild(kind);
  wrap.appendChild(head);

  const row = document.createElement('div');
  row.className = 'swatch-row';
  const picker = document.createElement('input');
  picker.type = 'color';
  row.appendChild(picker);
  const hint = document.createElement('span');
  hint.className = 'param-range';
  hint.textContent = 'Farbwaehler setzt Hue/Sat/Bright gemeinsam';
  row.appendChild(hint);
  wrap.appendChild(row);

  const components = document.createElement('div');
  components.className = 'components';
  ['hue', 'sat', 'bright'].forEach((key) => {
    const param = control.components[key];
    components.appendChild(buildParam(param, values[param.address]));
  });
  wrap.appendChild(components);
  wrap.appendChild(paletteRowFor(control));

  // Merkt sich die zuletzt angefasste Karte -- die Quelle fuer "Farbe
  // uebernehmen". Ohne das muesste der Knopf raten, welche der sieben Karten
  // gemeint ist.
  wrap.addEventListener('pointerdown', () => { activeColorCard = control; });
  wrap.addEventListener('focusin', () => { activeColorCard = control; });

  const card = { base: control.base, input: picker, components: control.components };
  colorCards.push(card);
  syncColorCard(card);

  picker.addEventListener('input', () => {
    const hsb = hexToHsb(picker.value);
    // auf das Raster der Regler runden, damit angezeigter und gesendeter
    // Wert nicht auseinanderlaufen
    const updates = [
      { address: control.components.hue.address,
        value: roundToStep(hsb[0], control.components.hue) },
      { address: control.components.sat.address,
        value: roundToStep(hsb[1], control.components.sat) },
      { address: control.components.bright.address,
        value: roundToStep(hsb[2], control.components.bright) },
    ];
    updates.forEach((u) => controls.get(u.address).set(u.value, true));
    queueSendMany('color:' + control.base, updates);
  });

  return wrap;
}

// ---------------------------------------------------------------------------
// Farbpalette
//
// Eine Sammlung wiederverwendbarer Farben, die unter JEDER Farbwaehler-Karte
// als Reihe steht: ein Klick setzt Hue/Sat/Bright genau dieser Karte. Der
// Versand laeuft ueber denselben queueSendMany-Weg wie der Farbwaehler
// darueber -- kein Sonderpfad, damit Entprellung und Fehlerbehandlung
// dieselben bleiben.
//
// Gehalten wird sie SERVER-seitig (data/colorPalettes.txt), nicht im
// localStorage: sie soll einen Neustart ueberleben und auf jedem Geraet
// dieselbe sein.
// ---------------------------------------------------------------------------

let paletteEntries = (bootstrap.palette && bootstrap.palette.entries) || [];
const paletteRows = [];        // { element, control } -- eine je Farbkarte
let paletteBarEl = null;       // die Leiste im Farben-Tab
let activeColorCard = null;    // zuletzt angefasste Karte, Quelle fuer "+"

function paletteSwatchColor(entry) {
  return hsbToHex(entry.hue, entry.sat, entry.bright);
}

/* Setzt eine Karte auf einen Paletteneintrag. Gerundet wird auf das Raster
 * der Regler, genau wie im Farbwaehler -- sonst laufen angezeigter und
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
  // Das Farbfeld der Karte zieht nicht von selbst nach: es haengt am
  // input-Ereignis seines eigenen Waehlers, und set(..., true) loest keins
  // aus (dieselbe Regel wie beim Preset-Laden).
  const card = colorCards.find((c) => c.base === control.base);
  if (card) { syncColorCard(card); }
  queueSendMany('color:' + control.base, updates);
  setStatus('Palette „' + entry.name + '“ auf ' + control.base
    + ' angewendet', 'ok');
}

/* Baut die Swatch-Reihe einer Karte neu. Wird bei jeder Palette-Aenderung
 * fuer ALLE Karten gerufen -- eine neue Farbe soll ueberall sofort da sein,
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

/* Die komplette Palette an den Server schicken (Voll-Liste: er ersetzt die
 * Datei durch genau das, was hier steht). */
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

/* Die Leiste im Farben-Tab: alle Farben mit Namen, je ein Loesch-Kreuz,
 * darunter Namensfeld und Uebernehmen-Knopf. */
function fillPaletteBar() {
  paletteBarEl.innerHTML = '';
  if (!paletteEntries.length) {
    const hint = document.createElement('p');
    hint.className = 'palette-empty';
    hint.textContent = 'Noch keine Farbe in der Palette. Eine Farbkarte '
      + 'weiter unten anfassen, Namen eintragen, „Farbe uebernehmen“.';
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
        'Farbe „' + entry.name + '“ entfernt');
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
    + 'steht dieselbe Reihe – ein Klick setzt die Karte auf diese Farbe. '
    + 'Welche Karte welche Farbe traegt, halten weiterhin die Presets fest.';
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
      setStatus('Erst eine Farbkarte anfassen – die Palette weiss sonst '
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
    // Gleicher Name ersetzt -- dieselbe Regel wie in der Datei, wo die
    // letzte Zeile gewinnt. Der Server lehnt einen doppelten Namen ab, ein
    // blindes concat() liefe also in einen Fehler.
    const next = paletteEntries.filter((e) => e.name !== name).concat([entry]);
    const base = activeColorCard.base;
    nameInput.value = '';
    savePalette(next, 'Farbe „' + name + '“ aus ' + base + ' uebernommen');
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

// ---------------------------------------------------------------------------
// Aufbau der Seite
// ---------------------------------------------------------------------------

function render(data) {
  controls.clear();
  colorCards.length = 0;
  // Die Palette-Registrierungen zeigen nach einem Neuaufbau auf Elemente,
  // die nicht mehr im Dokument stehen -- ohne das Zuruecksetzen wuechsen
  // paletteRows bei jedem "Neu laden" an.
  paletteRows.length = 0;
  paletteBarEl = null;
  activeColorCard = null;

  // Alle Tabs vollstaendig bauen (siehe buildTabs: die controls-Map muss
  // komplett sein, auch fuer inaktive Tabs).
  buildTabs(data);

  const settings = data.settings || {};
  const osc = data.osc || {};
  const stamp = settings.mtime
    ? new Date(settings.mtime * 1000).toLocaleString('de-DE') : 'unbekannt';
  metaEl.textContent = `${settings.count || 0} Parameter aus ${settings.path} `
    + `(Stand ${stamp}) – OSC an ${osc.host}:${osc.port}`;
  if (settings.error) {
    setStatus(settings.error, 'err');
  } else if (!settings.count) {
    setStatus('Keine Parameter gefunden – wurde imPulse schon einmal gestartet?', 'warn');
  }
}

async function reload() {
  try {
    const response = await fetch('/api/parameters?force=1');
    const data = await response.json();
    render(data);
    setStatus('remoteSettings.txt neu eingelesen: ' + (data.settings.count || 0)
      + ' Parameter', 'ok');
  } catch (err) {
    setStatus('Neu laden fehlgeschlagen: ' + err, 'err');
  }
}

// ---------------------------------------------------------------------------
// Presets
//
// Die Liste kommt vom Dateisystem des Servers (er laeuft auf derselben
// Maschine wie imPulse), die Kommandos gehen als OSC-String raus. Nach dem
// Laden schickt der Server die Preset-Werte zurueck, damit die Regler
// mitziehen -- still gesetzt, also ohne dabei ein zweites OSC auszuloesen.
// ---------------------------------------------------------------------------

/* Wortgleicher Spiegel von valid_preset_name() in server.py und
 * PresetStore.isValidName() in Java. Java bleibt die Autoritaet (dort geht es
 * um Pfad-Traversal); hier nur darum, eine ungueltige Eingabe gar nicht erst
 * rauszuschicken. */
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

async function refreshPresets() {
  try {
    const response = await fetch('/api/presets');
    fillPresets(await response.json());
  } catch (err) {
    setStatus('Preset-Liste nicht abrufbar: ' + err, 'err');
  }
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
      // 404 heisst: die Datei ist weg, jemand hat sie von Hand geloescht
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

// ---------------------------------------------------------------------------
// Spezial-Sektionen: Sequencer, Speed-Klassen, SC-Sound-Parameter
//
// Der Server liefert dafuer STRUKTUR statt einer flachen Reglerliste (siehe
// build_sequencer/build_speed_classes/sc_param_groups in server.py) und nimmt
// deren Adressen aus dem generischen Rendering heraus. Hier steht, wie das
// aussieht.
//
// Die Regler dieser Sektionen laufen ueber dasselbe queueSend() wie alle
// anderen und tragen sich in dieselbe controls-Map ein -- dadurch ziehen sie
// beim Laden eines Presets automatisch mit, ohne eigenen Sonderweg.
// ---------------------------------------------------------------------------

const tabBarEl = document.getElementById('tabBar');
const tabPanelsEl = document.getElementById('tabPanels');

// Halten den Zustand fuer die Rasteranzeige. Die Uhr laeuft immer, gezeichnet
// wird nur, wenn der Sequencer eingeschaltet ist.
const pulseTracks = [];        // { dot, noteValue, enabled }
let pulseBpm = 60;
let pulseRunning = false;

function trackColor(index) {
  return 'var(--track-' + (index % 6) + ')';
}

/* Kompakter Regler fuer die Track-Karten: Beschriftung, Schieber, Zahl.
 * Bewusst nicht buildSlider(): dort gehoert eine Adresszeile mit Range dazu,
 * das waere in einer Karte mit fuenf Reglern nur Rauschen. */
function miniSlider(labelText, param, initial, format, onChange) {
  const row = document.createElement('label');
  row.className = 'mini-row';

  const caption = document.createElement('span');
  caption.textContent = labelText;

  const range = document.createElement('input');
  range.type = 'range';
  range.min = param.min;
  range.max = param.max;
  range.step = param.step;
  range.value = initial;
  range.title = param.address + (param.help ? ' – ' + param.help : '');

  const out = document.createElement('output');

  const fmt = format || ((v) => (param.type === 'int'
    ? String(Math.round(v)) : formatValue(v, param)));

  let current = Number(initial);

  function apply(value, silent) {
    let next = Number(value);
    if (!isFinite(next)) { return; }
    next = Math.min(param.max, Math.max(param.min, next));
    if (param.type === 'int') { next = Math.round(next); }
    current = next;
    range.value = next;
    out.textContent = fmt(next);
    if (!silent) { queueSend(param.address, next); }
    if (onChange) { onChange(next); }
  }

  range.addEventListener('input', () => apply(range.value, false));
  apply(initial, true);

  row.appendChild(caption);
  row.appendChild(range);
  row.appendChild(out);

  const handle = {
    element: row,
    set: (value, silent) => apply(value, silent !== false),
    get: () => current,
    flash: () => {},
  };
  controls.set(param.address, handle);
  return handle;
}

/* Notenwert-Leiste: Symbol UND Kuerzel nebeneinander. Nicht jede
 * Windows-Schrift hat U+1D15D..U+1D161 -- ein Symbol allein waere dort ein
 * leeres Kaestchen und der Track unbeschriftet. */
function noteBar(param, initial, noteValues, onPick) {
  const bar = document.createElement('div');
  bar.className = 'notes';
  const buttons = [];

  function mark(value) {
    buttons.forEach((entry) => {
      entry.button.setAttribute('aria-pressed',
        entry.value === value ? 'true' : 'false');
    });
  }

  // Auf den naechstniedrigeren erlaubten Wert rasten - dieselbe Regel wie
  // OriginSequencer.quantizeNoteValue() auf der Java-Seite. Ein Preset mit
  // einem krummen Wert markiert damit denselben Knopf, den der Sequencer
  // tatsaechlich faehrt.
  function quantize(raw) {
    let best = noteValues[0].value;
    noteValues.forEach((n) => { if (n.value <= raw) { best = n.value; } });
    return best;
  }

  noteValues.forEach((note) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.title = note.name + ' (' + note.value + ')';
    const sym = document.createElement('span');
    sym.className = 'sym';
    sym.textContent = note.symbol;
    const lbl = document.createElement('span');
    lbl.className = 'lbl';
    lbl.textContent = note.value === 1 ? '1/1' : '1/' + note.value;
    button.appendChild(sym);
    button.appendChild(lbl);
    button.addEventListener('click', () => {
      mark(note.value);
      queueSend(param.address, note.value);
      if (onPick) { onPick(note.value); }
    });
    buttons.push({ value: note.value, button: button });
    bar.appendChild(button);
  });

  mark(quantize(Number(initial)));

  const handle = {
    element: bar,
    set: (value, silent) => {
      const q = quantize(Number(value));
      mark(q);
      if (!silent) { queueSend(param.address, q); }
      if (onPick) { onPick(q); }
    },
    get: () => {
      const active = buttons.find((entry) =>
        entry.button.getAttribute('aria-pressed') === 'true');
      return active ? active.value : noteValues[0].value;
    },
    flash: () => {},
  };
  controls.set(param.address, handle);
  return handle;
}

/* Baum-Filter als Auswahlbalken.
 *
 * Bewusst kein Schieber und auch kein Schalter-plus-Dropdown: der Parameter
 * hat fuenf gleichrangige Zustaende, von denen "alle" (=0) einer ist. Ein
 * Balken zeigt alle fuenf gleichzeitig, jeder ist einen Klick entfernt, und
 * es gibt keinen verborgenen "zuletzt gewaehlter Baum"-Zustand, der nach
 * einem Preset-Laden gegenueber dem Sketch falsch stehen koennte. Gleiche
 * Bauform wie noteBar() eine Karte weiter oben.
 *
 * Der Wertebereich bleibt der rohe int 0..4 von RemoteControlledIntParameter
 * -- hier wird nur beschriftet. */
function treeBar(param, initial, labels, onPick) {
  const bar = document.createElement('div');
  bar.className = 'tree-bar';
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
      ? 'Kein Filter – der Track wuerfelt aus allen Stripes ('
        + param.address + ' = 0)'
      : 'Nur Stripes am Baum "' + label + '" (' + param.address
        + ' = ' + index + ')';
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

function buildSequencer(data, host) {
  pulseTracks.length = 0;
  pulseRunning = false;
  const seq = data.sequencer;
  if (!seq) {
    // Aelterer imPulse-Stand ohne Sequencer: nichts zeigen. Die Parameter
    // waeren dann ohnehin nicht da.
    return;
  }

  const section = document.createElement('section');
  section.className = 'seq';

  const title = document.createElement('h2');
  title.textContent = 'Sequencer';
  section.appendChild(title);

  const top = document.createElement('div');
  top.className = 'seq-top';

  // --- BPM gross ---
  const bpmParam = seq.bpm;
  const bpmWrap = document.createElement('div');
  bpmWrap.className = 'seq-bpm';
  const bpmValue = document.createElement('span');
  bpmValue.className = 'seq-bpm-value';
  const bpmUnit = document.createElement('span');
  bpmUnit.className = 'seq-bpm-unit';
  bpmUnit.textContent = 'bpm';
  bpmWrap.appendChild(bpmValue);
  bpmWrap.appendChild(bpmUnit);

  const bpmSlide = document.createElement('div');
  bpmSlide.className = 'seq-bpm-slider';
  const bpmRange = document.createElement('input');
  bpmRange.type = 'range';
  bpmRange.min = bpmParam.min;
  bpmRange.max = bpmParam.max;
  bpmRange.step = bpmParam.step;
  bpmRange.setAttribute('aria-label', 'Tempo in BPM');
  bpmRange.title = bpmParam.address + (bpmParam.help ? ' – ' + bpmParam.help : '');
  bpmSlide.appendChild(bpmRange);

  function applyBpm(value, silent) {
    let next = Number(value);
    if (!isFinite(next)) { return; }
    next = Math.min(bpmParam.max, Math.max(bpmParam.min, next));
    pulseBpm = next;
    bpmRange.value = next;
    bpmValue.textContent = next.toFixed(next % 1 === 0 ? 0 : 1);
    if (!silent) { queueSend(bpmParam.address, next); }
  }
  bpmRange.addEventListener('input', () => applyBpm(bpmRange.value, false));
  applyBpm(data.values[bpmParam.address], true);
  controls.set(bpmParam.address, {
    element: bpmSlide,
    set: (v, silent) => applyBpm(v, silent !== false),
    get: () => pulseBpm,
    flash: () => {},
  });

  // --- Not-Aus ---
  const enabledParam = seq.enabled;
  const power = document.createElement('label');
  power.className = 'seq-power';
  power.title = enabledParam.address
    + (enabledParam.help ? ' – ' + enabledParam.help : '');
  const powerBox = document.createElement('input');
  powerBox.type = 'checkbox';
  const powerDot = document.createElement('span');
  powerDot.className = 'dot';
  const powerText = document.createElement('span');
  power.appendChild(powerBox);
  power.appendChild(powerDot);
  power.appendChild(powerText);

  function applyPower(value, silent) {
    const on = Number(value) >= 1;
    powerBox.checked = on;
    power.classList.toggle('on', on);
    powerText.textContent = on ? 'laeuft' : 'aus';
    pulseRunning = on;
    if (!on) {
      pulseTracks.forEach((t) => t.dot.classList.remove('lit'));
    }
    if (!silent) { queueSend(enabledParam.address, on ? 1 : 0); }
  }
  powerBox.addEventListener('change', () => applyPower(powerBox.checked ? 1 : 0, false));
  applyPower(data.values[enabledParam.address], true);
  controls.set(enabledParam.address, {
    element: power,
    set: (v, silent) => applyPower(v, silent !== false),
    get: () => (powerBox.checked ? 1 : 0),
    flash: () => {},
  });

  top.appendChild(bpmWrap);
  top.appendChild(bpmSlide);
  top.appendChild(power);
  section.appendChild(top);

  // --- Die sechs Spuren ---
  const grid = document.createElement('div');
  grid.className = 'seq-tracks';

  seq.tracks.forEach((track) => {
    const card = document.createElement('div');
    card.className = 'track';
    card.style.setProperty('--tc', trackColor(track.index));

    const head = document.createElement('div');
    head.className = 'track-head';

    const caption = document.createElement('span');
    caption.className = 'track-title';
    caption.textContent = 'Track ' + track.index;

    const right = document.createElement('div');
    right.style.display = 'flex';
    right.style.alignItems = 'center';
    right.style.gap = '0.5rem';

    const dot = document.createElement('span');
    dot.className = 'track-pulse';
    // Ehrliche Beschriftung: das ist die Uhr des Browsers, nicht der Sketch.
    dot.title = 'Raster (berechnet aus BPM und Notenwert im Browser) – '
      + 'keine Rueckmeldung aus imPulse, die Phase kann abweichen';

    const sw = document.createElement('label');
    sw.className = 'track-switch';
    const swBox = document.createElement('input');
    swBox.type = 'checkbox';
    swBox.setAttribute('aria-label', 'Track ' + track.index + ' aktiv');
    sw.appendChild(swBox);

    right.appendChild(dot);
    right.appendChild(sw);
    head.appendChild(caption);
    head.appendChild(right);
    card.appendChild(head);

    const state = { dot: dot, noteValue: 4, enabled: false };
    pulseTracks.push(state);

    function applyEnabled(value, silent) {
      const on = Number(value) >= 1;
      swBox.checked = on;
      card.classList.toggle('off', !on);
      state.enabled = on;
      if (!on) { dot.classList.remove('lit'); }
      if (!silent) { queueSend(track.enabled.address, on ? 1 : 0); }
    }
    swBox.addEventListener('change', () => applyEnabled(swBox.checked ? 1 : 0, false));
    applyEnabled(data.values[track.enabled.address], true);
    controls.set(track.enabled.address, {
      element: card,
      set: (v, silent) => applyEnabled(v, silent !== false),
      get: () => (swBox.checked ? 1 : 0),
      flash: () => {},
    });

    const fields = track.fields || {};

    if (fields.noteValue) {
      const bar = noteBar(fields.noteValue, data.values[fields.noteValue.address],
        seq.noteValues, (v) => { state.noteValue = v; });
      state.noteValue = bar.get();
      card.appendChild(bar.element);
    }

    const mini = document.createElement('div');
    mini.className = 'mini';
    if (fields.repeatCount) {
      mini.appendChild(miniSlider('Wdh.', fields.repeatCount,
        data.values[fields.repeatCount.address],
        (v) => Math.round(v) + '×').element);
    }
    if (fields.energy) {
      mini.appendChild(miniSlider('Energie', fields.energy,
        data.values[fields.energy.address]).element);
    }
    if (fields.swingJitter) {
      mini.appendChild(miniSlider('Swing', fields.swingJitter,
        data.values[fields.swingJitter.address]).element);
    }
    // Baum-Filter und fester Ursprung haengen zusammen: ein gesetzter
    // Ursprung schlaegt den Filter (OriginSequencer.advanceOrigin). Diese
    // Zeile sagt es genau dann, wenn es zutrifft -- ein statischer Satz je
    // Track waere sechsmal dasselbe und stuende auch dann da, wenn kein
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
          + ' – der Baum-Filter wirkt nicht.';
      }
    }

    if (fields.originTreeFilter) {
      const labels = seq.treeLabels || ['alle'];
      const caption = document.createElement('span');
      caption.className = 'mini-caption';
      caption.textContent = 'Baum';
      mini.appendChild(caption);
      // Klartext statt Zahl: "3" verraet niemandem, welcher Baum gemeint
      // ist. Die Reihenfolge spiegelt StripeTreeStore.TREE_NAMES.
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

    grid.appendChild(card);
  });

  section.appendChild(grid);

  // Erklaerung zum Baum-Filter: einmal unter der Spurenreihe statt sechsmal
  // in den Karten. Der Text kommt vom Server (TREE_HELP in server.py), weil
  // er eine Aussage ueber die Java-Seite trifft und dort pruefbar ist.
  if (seq.treeHelp) {
    const help = document.createElement('p');
    help.className = 'seq-help';
    help.textContent = seq.treeHelp;
    section.appendChild(help);
  }

  host.appendChild(section);
}

/* Speed-Klassen. Der Verteilungsbalken macht aus fuenf Gewichten ein Bild -
 * die Zahlen allein verraten nicht, wie selten ein 8x-Ausreisser wirklich
 * ist, weil sie nicht auf 100 normiert sind. */
function buildSpeedClasses(data, host) {
  const speed = data.speedClasses;
  if (!speed) { return; }

  const section = document.createElement('section');
  section.className = 'seq';

  const title = document.createElement('h2');
  title.textContent = 'Speed-Klassen';
  section.appendChild(title);

  const body = document.createElement('div');
  body.style.padding = '0.7rem 0.8rem';
  body.style.display = 'grid';
  body.style.gap = '0.6rem';

  // Ein/Aus
  const power = document.createElement('label');
  power.className = 'seq-power';
  power.style.justifySelf = 'start';
  power.title = speed.enabled.address
    + (speed.enabled.help ? ' – ' + speed.enabled.help : '');
  const powerBox = document.createElement('input');
  powerBox.type = 'checkbox';
  const powerDot = document.createElement('span');
  powerDot.className = 'dot';
  const powerText = document.createElement('span');
  power.appendChild(powerBox);
  power.appendChild(powerDot);
  power.appendChild(powerText);

  function applyPower(value, silent) {
    const on = Number(value) >= 1;
    powerBox.checked = on;
    power.classList.toggle('on', on);
    powerText.textContent = on ? 'quantisiert' : 'alle 1×';
    if (!silent) { queueSend(speed.enabled.address, on ? 1 : 0); }
  }
  powerBox.addEventListener('change', () => applyPower(powerBox.checked ? 1 : 0, false));
  applyPower(data.values[speed.enabled.address], true);
  controls.set(speed.enabled.address, {
    element: power,
    set: (v, silent) => applyPower(v, silent !== false),
    get: () => (powerBox.checked ? 1 : 0),
    flash: () => {},
  });
  body.appendChild(power);

  const bar = document.createElement('div');
  bar.className = 'dist';
  const segments = [];
  body.appendChild(bar);

  const help = document.createElement('p');
  help.className = 'help';
  help.textContent = 'Anteil der Impulse je Klasse. Vielfaches von '
    + '/net/impulse/speed – 1× ist der Normalfall, hohe Klassen sind die '
    + 'seltenen Ausreisser. Die Gewichte werden normalisiert, sie muessen '
    + 'sich nicht zu 100 summieren.';
  body.appendChild(help);

  const weightHandles = [];

  function redraw() {
    const values = weightHandles.map((h) => Math.max(0, h.get()));
    const total = values.reduce((a, b) => a + b, 0);
    bar.innerHTML = '';
    segments.length = 0;
    if (total <= 0) {
      const empty = document.createElement('span');
      empty.className = 'dist-empty';
      // Genau das macht SpeedQuantizer.pick() bei lauter Nullen.
      empty.textContent = 'alle Gewichte 0 – es gilt 1×';
      bar.appendChild(empty);
      return;
    }
    speed.weights.forEach((weight, i) => {
      if (values[i] <= 0) { return; }
      const share = values[i]/total;
      const seg = document.createElement('span');
      seg.className = 'dist-seg';
      seg.style.flexGrow = String(share);
      seg.style.flexBasis = '0';
      seg.style.background = trackColor(i);
      seg.title = weight.label + ': ' + (share*100).toFixed(1) + ' %';
      seg.textContent = share >= 0.08
        ? weight.label + ' ' + Math.round(share*100) + '%' : '';
      bar.appendChild(seg);
      segments.push(seg);
    });
  }

  const weights = document.createElement('div');
  weights.className = 'mini';
  speed.weights.forEach((weight, i) => {
    const handle = miniSlider(weight.label, weight, data.values[weight.address],
      (v) => v.toFixed(0));
    handle.element.style.setProperty('--tc', trackColor(i));

    // Der Balken haengt an allen fuenf Reglern, also hier statt in
    // miniSlider. Umgehaengt wird die set()-METHODE, nicht nur das
    // input-Event: das Laden eines Presets ruft control.set(wert, true) und
    // loest dabei bewusst kein input aus - an einem reinen Event-Listener
    // bliebe der Balken danach auf der alten Verteilung stehen.
    const innerSet = handle.set;
    handle.set = (value, silent) => {
      innerSet(value, silent);
      redraw();
    };
    controls.set(weight.address, handle);
    handle.element.querySelector('input[type=range]')
      .addEventListener('input', redraw);

    weightHandles.push(handle);
    weights.appendChild(handle.element);
  });
  body.appendChild(weights);

  if (speed.jitter) {
    const jitter = miniSlider('Swing', speed.jitter,
      data.values[speed.jitter.address]);
    body.appendChild(jitter.element);
    const note = document.createElement('p');
    note.className = 'help';
    note.textContent = speed.jitter.help || '';
    body.appendChild(note);
  }

  redraw();
  section.appendChild(body);
  host.appendChild(section);
}

/* Split-Verhalten: wieviele Zweige eine Kreuzung nimmt, und wie weit sie
 * zeitlich auseinander starten.
 *
 * Gebaut wie die Speed-Klassen daneben, und aus demselben Grund: drei
 * Gewichte sind eine Verteilung, kein Trio unabhaengiger Zahlen -- ohne den
 * Balken rechnet der Operator im Kopf, was 40/25/10 eigentlich bedeutet.
 *
 * Der Notenwert nimmt die Leiste des Sequencers (noteBar), nicht einen
 * 1..16-Schieber: der Sketch rastet den Wert beim Lesen auf 1/2/4/8/16, ein
 * Schieber zeigte also Stellungen an, die es nicht gibt. */
function buildSplit(data, host) {
  const split = data.split;
  if (!split) { return; }

  const section = document.createElement('section');
  section.className = 'seq';

  const title = document.createElement('h2');
  title.textContent = 'Split-Verhalten';
  section.appendChild(title);

  const body = document.createElement('div');
  body.style.padding = '0.7rem 0.8rem';
  body.style.display = 'grid';
  body.style.gap = '0.6rem';

  const bar = document.createElement('div');
  bar.className = 'dist';
  body.appendChild(bar);

  const help = document.createElement('p');
  help.className = 'help';
  help.textContent = 'Wieviele der moeglichen Zweige eine Kreuzung nimmt. '
    + 'Welche wegfallen, wird je Aufspaltung gewuerfelt. Die Gewichte werden '
    + 'normalisiert, sie muessen sich nicht zu 100 summieren.';
  body.appendChild(help);

  const weightHandles = [];

  function redraw() {
    const values = weightHandles.map((h) => Math.max(0, h.get()));
    const total = values.reduce((a, b) => a + b, 0);
    bar.innerHTML = '';
    if (total <= 0) {
      const empty = document.createElement('span');
      empty.className = 'dist-empty';
      // Genau das macht SplitFanout.branchCount() bei lauter Nullen: der
      // Ausfallmodus ist das bisherige Verhalten, nicht Stille.
      empty.textContent = 'alle Gewichte 0 – es gilt "alle Zweige"';
      bar.appendChild(empty);
      return;
    }
    split.weights.forEach((weight, i) => {
      if (values[i] <= 0) { return; }
      const share = values[i]/total;
      const seg = document.createElement('span');
      seg.className = 'dist-seg';
      seg.style.flexGrow = String(share);
      seg.style.flexBasis = '0';
      seg.style.background = trackColor(i);
      seg.title = weight.label + ': ' + (share*100).toFixed(1) + ' %';
      seg.textContent = share >= 0.08
        ? weight.label + ' ' + Math.round(share*100) + '%' : '';
      bar.appendChild(seg);
    });
  }

  const weights = document.createElement('div');
  weights.className = 'mini';
  split.weights.forEach((weight, i) => {
    const handle = miniSlider(weight.label, weight, data.values[weight.address],
      (v) => v.toFixed(0));
    handle.element.style.setProperty('--tc', trackColor(i));
    // Umgehaengt wird die set()-METHODE, nicht nur das input-Event: das Laden
    // eines Presets ruft control.set(wert, true) und loest bewusst kein input
    // aus - an einem reinen Event-Listener bliebe der Balken danach auf der
    // alten Verteilung stehen.
    const innerSet = handle.set;
    handle.set = (value, silent) => {
      innerSet(value, silent);
      redraw();
    };
    controls.set(weight.address, handle);
    handle.element.querySelector('input[type=range]')
      .addEventListener('input', redraw);
    weightHandles.push(handle);
    weights.appendChild(handle.element);
  });
  body.appendChild(weights);

  // Zeitlicher Versatz
  if (split.staggerEnabled) {
    const power = document.createElement('label');
    power.className = 'seq-power';
    power.style.justifySelf = 'start';
    power.title = split.staggerEnabled.address
      + (split.staggerEnabled.help ? ' – ' + split.staggerEnabled.help : '');
    const powerBox = document.createElement('input');
    powerBox.type = 'checkbox';
    const powerDot = document.createElement('span');
    powerDot.className = 'dot';
    const powerText = document.createElement('span');
    power.appendChild(powerBox);
    power.appendChild(powerDot);
    power.appendChild(powerText);

    function applyPower(value, silent) {
      const on = Number(value) >= 1;
      powerBox.checked = on;
      power.classList.toggle('on', on);
      powerText.textContent = on ? 'Zweige versetzt' : 'Zweige gleichzeitig';
      if (!silent) { queueSend(split.staggerEnabled.address, on ? 1 : 0); }
    }
    powerBox.addEventListener('change',
      () => applyPower(powerBox.checked ? 1 : 0, false));
    applyPower(data.values[split.staggerEnabled.address], true);
    controls.set(split.staggerEnabled.address, {
      element: power,
      set: (v, silent) => applyPower(v, silent !== false),
      get: () => (powerBox.checked ? 1 : 0),
      flash: () => {},
    });
    body.appendChild(power);
  }

  if (split.staggerNoteValue) {
    const caption = document.createElement('p');
    caption.className = 'help';
    // Der Takt kommt aus /net/sequencer/bpm, auch wenn der Sequencer aus ist:
    // die Uhr laeuft dort unabhaengig weiter (siehe tickSequencer). Das ist
    // nicht zu erraten, deshalb steht es hier.
    caption.textContent = 'Abstand zwischen dem 1., 2. und 3. Zweig einer '
      + 'Aufspaltung. Das Raster kommt von /net/sequencer/bpm – auch dann, '
      + 'wenn der Sequencer selbst aus ist.';
    body.appendChild(caption);
    const bar2 = noteBar(split.staggerNoteValue,
      data.values[split.staggerNoteValue.address], split.noteValues);
    body.appendChild(bar2.element);
  }

  redraw();
  section.appendChild(body);
  host.appendChild(section);
}

/* SC-Sound-Parameter. Eigener Port (8002), eigene Tabelle, kein Rueckkanal -
 * die Sektion sagt das selbst, sonst haelt man die Anzeige fuer den
 * Live-Zustand von SuperCollider. */
/* Eine Liste von SC-Parametern in einen Container rendern.
 *
 * Wird zweimal gerufen: einmal fuer die kuratierten oben im Tab, einmal fuer
 * den Rest im Erweitert-Bereich. Der Warnhinweis steht nur beim ersten Block
 * je Tab. */
function buildScParams(params, host, port, withNote) {
  if (!params || !params.length) { return; }

  const section = document.createElement('section');
  section.className = 'sc';

  const title = document.createElement('h2');
  title.textContent = 'Sound (SuperCollider)';
  section.appendChild(title);

  if (withNote) {
    const note = document.createElement('p');
    note.className = 'sc-note';
    note.textContent = 'Geht direkt an sclang auf Port ' + port
      + ', nicht an imPulse. Es gibt keinen Rueckkanal: die Werte hier sind die '
      + 'Defaults aus klangnetz_bells.scd, nicht der Live-Zustand – und laeuft '
      + 'sclang nicht, bleibt eine Aenderung wirkungslos ohne Fehlermeldung.';
    section.appendChild(note);
  }

  {
    {
      params.forEach((param) => {
      const wrap = document.createElement('div');
      wrap.className = 'param';

      const head = document.createElement('div');
      head.className = 'param-head';
      const name = document.createElement('span');
      name.className = 'param-name';
      const prefix = document.createElement('span');
      prefix.className = 'prefix';
      prefix.textContent = '/klangnetz/param/';
      name.appendChild(prefix);
      name.appendChild(document.createTextNode(param.name));
      const range = document.createElement('span');
      range.className = 'param-range';
      range.textContent = param.min + ' … ' + param.max;
      head.appendChild(name);
      head.appendChild(range);
      wrap.appendChild(head);

      const body = document.createElement('div');
      body.className = 'param-body';
      const slider = document.createElement('input');
      slider.type = 'range';
      slider.min = param.min;
      slider.max = param.max;
      const span = param.max - param.min;
      slider.step = span > 40 ? 1 : (span > 4 ? 0.05 : 0.01);
      slider.value = param.default;
      const out = document.createElement('input');
      out.type = 'number';
      out.min = param.min;
      out.max = param.max;
      out.step = 'any';
      out.value = param.default;
      body.appendChild(slider);
      body.appendChild(out);
      wrap.appendChild(body);

      if (param.description) {
        const help = document.createElement('p');
        help.className = 'help';
        help.textContent = param.description;
        wrap.appendChild(help);
      }

      let timer = null;
      function send(value) {
        if (timer) { clearTimeout(timer); }
        timer = setTimeout(async () => {
          timer = null;
          try {
            const response = await fetch('/api/sc', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ name: param.name, value: value }),
            });
            const payload = await response.json();
            if (!payload.ok) {
              setStatus('SC: ' + (payload.error || 'unbekannter Fehler'), 'err');
            }
          } catch (err) {
            setStatus('SC-Parameter nicht gesendet: ' + err, 'err');
          }
        }, DEBOUNCE_MS);
      }

      function apply(value, fromNumber) {
        let next = Number(value);
        if (!isFinite(next)) { return; }
        next = Math.min(param.max, Math.max(param.min, next));
        slider.value = next;
        if (!fromNumber) { out.value = next; }
        send(next);
      }
      slider.addEventListener('input', () => apply(slider.value, false));
      out.addEventListener('input', () => {
        if (out.value === '' || out.value === '-') { return; }
        apply(out.value, true);
      });

      section.appendChild(wrap);
      });
    }
  }

  host.appendChild(section);
}

/* ---------------------------------------------------------------------------
 * Tabs
 *
 * Fuenf Themen-Tabs statt einer langen Liste. Welcher Parameter in welchen
 * Tab gehoert, entscheidet der Server (TABS/TAB_RULES in server.py) -- das
 * ist eine inhaltliche Zuordnung und dort pruefbar.
 *
 * WICHTIG: ALLE Panels werden hier vollstaendig gebaut, der Tab-Wechsel setzt
 * nur `hidden`. Nicht "erst bauen, wenn der Tab geoeffnet wird" -- die
 * Regler tragen sich beim Bauen in die flache controls-Map ein, und ueber
 * genau diese Map laufen das Preset-Laden und der applied/echoed-Ruecklauf.
 * Ein Regler auf einem nie geoeffneten Tab stuende sonst nicht in der Map und
 * wuerde von einem Preset still nicht angezeigt -- ein Fehler ohne Symptom.
 * ------------------------------------------------------------------------- */

const TAB_STORAGE_KEY = 'imPulse.activeTab';

function buildTabs(data) {
  tabBarEl.innerHTML = '';
  tabPanelsEl.innerHTML = '';
  const tabs = data.tabs || [];
  if (!tabs.length) {
    // Aelterer Server ohne Tab-Daten: alles in einen Block, damit die
    // Oberflaeche nicht leer bleibt.
    tabPanelsEl.appendChild(groupsEl);
    return;
  }

  const buttons = new Map();
  const panels = new Map();

  function activate(id) {
    panels.forEach((panel, key) => { panel.hidden = (key !== id); });
    buttons.forEach((button, key) => {
      button.setAttribute('aria-selected', key === id ? 'true' : 'false');
    });
    try { localStorage.setItem(TAB_STORAGE_KEY, id); } catch (err) { /* egal */ }
  }

  tabs.forEach((tab) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'tab-button';
    button.textContent = tab.title;
    button.setAttribute('role', 'tab');
    button.addEventListener('click', () => activate(tab.id));
    tabBarEl.appendChild(button);
    buttons.set(tab.id, button);

    const panel = document.createElement('div');
    panel.className = 'tab-panel';
    panel.setAttribute('role', 'tabpanel');

    // 1. Spezial-Sektionen (Sequencer-Panel, Speed-Klassen, Split-Verhalten)
    (tab.sections || []).forEach((name) => {
      if (name === 'sequencer') { buildSequencer(data, panel); }
      if (name === 'speedClasses') { buildSpeedClasses(data, panel); }
      if (name === 'palette') { buildPaletteSection(panel); }
      if (name === 'split') { buildSplit(data, panel); }
    });

    // 2. Kuratierte Regler direkt sichtbar
    const scPrimary = (tab.scParams || []).filter((p) => p.primary);
    const scRest = (tab.scParams || []).filter((p) => !p.primary);
    if ((tab.primary || []).length) {
      const card = document.createElement('section');
      const title = document.createElement('h2');
      title.textContent = 'Wichtigste Regler';
      card.appendChild(title);
      tab.primary.forEach((control) => {
        card.appendChild(control.kind === 'trigger'
          ? buildTrigger(control)
          : buildParam(control, data.values[control.address]));
      });
      panel.appendChild(card);
    }
    buildScParams(scPrimary, panel, (data.scParams || {}).port, true);

    // 3. Alles Uebrige. Normalerweise eingeklappt -- dasselbe
    //    <details>-Muster wie die bisherige Advanced-Gruppe. Ausnahme: der
    //    Server markiert einen Tab als "expanded" (Farben-Tab). Dort gibt es
    //    keine kuratierte Auswahl, weil Farbkarten keine eigene Adresse
    //    tragen -- der ganze Tab bestuende sonst aus einem zugeklappten
    //    <details>.
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

    tabPanelsEl.appendChild(panel);
    panels.set(tab.id, panel);
  });

  let wanted = null;
  try { wanted = localStorage.getItem(TAB_STORAGE_KEY); } catch (err) { /* egal */ }
  activate(panels.has(wanted) ? wanted : tabs[0].id);
}

/* Eine generische Parametergruppe als Karte -- ausgelagert aus render(),
 * damit die Tabs sie wiederverwenden koennen. */
function buildGroupSection(group, data) {
  const section = document.createElement('section');
  const title = document.createElement('h2');
  title.textContent = group.title;
  section.appendChild(title);
  group.controls.forEach((control) => {
    if (control.kind === 'color') {
      section.appendChild(buildColorCard(control, data.values));
    } else if (control.kind === 'trigger') {
      section.appendChild(buildTrigger(control));
    } else {
      section.appendChild(buildParam(control, data.values[control.address]));
    }
  });
  return section;
}

/* Rasteranzeige.
 *
 * WICHTIG: das ist die Uhr des BROWSERS, nicht die des Sketches. Es gibt
 * keinen OSC-Rueckkanal hierher - imPulse sendet nur an Port 8002, und dort
 * hoert SuperCollider. Die Anzeige zeigt also, in welchem ABSTAND ein Track
 * feuert (Notenwert mal BPM), nicht WANN genau; ihre Phase kann gegenueber
 * dem Sketch beliebig verschoben sein. Deshalb heisst sie im UI "Raster" und
 * behauptet nirgends "feuert jetzt".
 *
 * Der Nutzen ist trotzdem echt: beim Einrichten sieht man auf einen Blick,
 * welcher Track dicht und welcher duenn laeuft.
 */
function startPulseClock() {
  const LIT_MS = 90;
  function frame(now) {
    if (pulseRunning && pulseBpm > 0) {
      const beatMs = 60000/pulseBpm;
      pulseTracks.forEach((track) => {
        if (!track.enabled) { return; }
        // Intervall in Beats wie MusicalClock.beatsPerNote(): 4/noteValue.
        const periodMs = beatMs*(4/(track.noteValue || 4));
        const phase = now % periodMs;
        track.dot.classList.toggle('lit', phase < LIT_MS);
      });
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
}

presetLoadEl.addEventListener('click', loadPreset);
presetSaveEl.addEventListener('click', savePreset);
presetNameEl.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') { savePreset(); }
});

const coupling = bootstrap.coupling || { speedAddress: '/net/impulse/speed', targets: [] };

couplingEl.checked = localStorage.getItem(COUPLING_STORAGE_KEY) === '1';
couplingEl.addEventListener('change', () => {
  localStorage.setItem(COUPLING_STORAGE_KEY, couplingEl.checked ? '1' : '0');
  setStatus(couplingEl.checked
    ? 'Speed-Kopplung aktiv – Aenderung an ' + coupling.speedAddress + ' zieht '
      + coupling.targets.map((t) => t.address).join(', ') + ' mit'
    : 'Speed-Kopplung aus – ' + coupling.speedAddress + ' wirkt jetzt isoliert');
});

reloadEl.addEventListener('click', reload);

fillPresets(bootstrap.presets);
render(bootstrap);
startPulseClock();
