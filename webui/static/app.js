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
const headlineEl = document.getElementById('headline');
const statusEl = document.getElementById('status');
const metaEl = document.getElementById('meta');
const couplingEl = document.getElementById('coupling');
const reloadEl = document.getElementById('reload');
const presetsEl = document.getElementById('presets');
const presetSelectEl = document.getElementById('presetSelect');
const presetLoadEl = document.getElementById('presetLoad');
const presetNameEl = document.getElementById('presetName');
const presetSaveEl = document.getElementById('presetSave');
const autocommitEl = document.getElementById('autocommit');
const melodyEl = document.getElementById('melody');
const parkedEl = document.getElementById('parked');
const melodyFieldsEl = document.getElementById('melodyFields');
const melodyConfirmEl = document.getElementById('melodyConfirm');
const melodyRecomputeEl = document.getElementById('melodyRecompute');

// key -> <input>/<select> der Melodie-Sektion. Bewusst NICHT in der
// controls-Map: die Melodie-Regler senden beim Verstellen nichts, ein
// Preset-Ruecklauf hat an ihnen also nichts zu setzen, und sie stehen
// ohnehin in PresetStore.EXCLUDED.
const melodyInputs = new Map();

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
  rgbCards.forEach(syncRgbCard);

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

/* Der sichtbare Titel eines Reglers.
 *
 * Frueher war das schlicht das letzte Segment der OSC-Adresse
 * ("nodeDeadTime"). Der sprechende Titel kommt jetzt vom Server
 * (ADDRESS_LABELS in server.py), weil er eine inhaltliche Aussage ueber den
 * Sketch ist und dort pruefbar bleibt. Faellt er aus -- neuer Parameter, fuer
 * den dort niemand eine Zeile ergaenzt hat --, bleibt es beim Adresssegment:
 * unbeschriftet waere schlimmer als technisch beschriftet. */
function titleFor(param, address) {
  if (param && param.label) { return param.label; }
  return splitAddress(address || (param && param.address) || '').leaf;
}

/* Erklaerung und Adresszeile unter einen Regler haengen.
 *
 * Reihenfolge und Groessenstaffelung sind der Punkt der ganzen Uebung: Titel
 * gross, Erklaerung klein darunter, Adresse am kleinsten und gedimmt. Die
 * Adresse bleibt sichtbar (nicht nur im Tooltip), weil sie beim Debuggen mit
 * OSC von Hand gebraucht wird -- sie ist nur nicht mehr der Haupttext.
 *
 * Eine Funktion fuer alle vier Bauformen (Schieber, Schalter, Trigger,
 * Farbkarte), sonst haette jede ihre eigene Reihenfolge. */
function appendMeta(wrap, param, address) {
  if (param && param.help) {
    const help = document.createElement('p');
    help.className = 'help';
    help.textContent = param.help;
    wrap.appendChild(help);
  }
  const shown = address || (param && param.address);
  if (shown) {
    const addr = document.createElement('code');
    addr.className = 'param-addr';
    addr.textContent = shown;
    wrap.appendChild(addr);
  }
  return wrap;
}

function makeHead(param) {
  const address = param.address;
  const head = document.createElement('div');
  head.className = 'param-head';

  const name = document.createElement('span');
  name.className = 'param-name';
  // Der Tooltip traegt weiter die Adresse: an einem Regler, der jetzt
  // "Totzeit pro Knoten" heisst, ist der Bezug zur OSC-Adresse sonst nur noch
  // ueber die Zeile darunter zu haben.
  name.title = address + (param.description &&
    param.description !== 'space for descripiton' ? ' – ' + param.description : '');
  name.textContent = titleFor(param, address);

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
  // Erklaerung und Adresszeile. Sichtbar statt nur als Tooltip: im Dunkeln
  // neben der Installation findet niemand einen Hover-Text.
  appendMeta(handle.element, param);
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
  name.textContent = titleFor(param);
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
  appendMeta(wrap, param);

  return wrap;
}

/* Ein Regler-Handle OHNE eigenes Bedienelement.
 *
 * Gebraucht, seit Farben nur noch ueber den Farbwaehler eingestellt werden
 * (Birk, 2026-08-01): die einzelnen Kanalregler sind aus dem UI verschwunden,
 * ihre Adressen muessen aber in der controls-Map bleiben. Ueber genau diese
 * Map laufen das Preset-Laden und der applied/echoed-Ruecklauf aus /api/set --
 * eine Adresse, die dort fehlt, wird von einem Preset still nicht angezeigt,
 * und die Karte zeigte danach eine andere Farbe als die Installation.
 *
 * Das Handle haelt also den Wert und meldet Aenderungen an die Karte, die ihn
 * anzeigt; ein DOM-Element hat es nicht. */
function headlessControl(param, initial, onChange) {
  let current = Number(initial);
  if (!isFinite(current)) { current = Number(param.min) || 0; }

  function apply(value, silent) {
    let next = Number(value);
    if (!isFinite(next)) { return; }
    next = Math.min(param.max, Math.max(param.min, next));
    if (param.type === 'int') { next = Math.round(next); }
    current = next;
    if (!silent) { queueSend(param.address, next); }
    if (onChange) { onChange(next); }
  }

  const handle = {
    element: null,
    set: (value, silent) => apply(value, silent !== false),
    get: () => current,
    flash: () => {},
  };
  controls.set(param.address, handle);
  return handle;
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
  // Der Titel kommt vom Server (label_for auf der BASIS, nicht auf einer
  // Adresse -- eine Farbkarte hat keine eigene). Ohne ihn stuende hier nur
  // "fired", was nicht verraet, wessen Zustand gemeint ist.
  name.textContent = control.label || splitAddress(control.base).leaf;
  const kind = document.createElement('span');
  kind.className = 'param-range';
  kind.textContent = 'Farbe (HSB)';
  head.appendChild(name);
  head.appendChild(kind);
  wrap.appendChild(head);
  if (control.help) {
    const help = document.createElement('p');
    help.className = 'help';
    help.textContent = control.help;
    wrap.appendChild(help);
  }

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

  // Die drei Kanalregler sind seit 2026-08-01 weg -- eine Farbe stellt man am
  // Waehler ein, nicht an drei Zahlen. Ihre Adressen bleiben trotzdem in der
  // controls-Map (headlessControl), sonst zoege ein Preset die Karte nicht
  // mehr nach.
  ['hue', 'sat', 'bright'].forEach((key) => {
    const param = control.components[key];
    headlessControl(param, values[param.address]);
  });
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

/* ---------------------------------------------------------------------------
 * RGB-Farbkarten (Impulsfarbe, die acht Stripe-Slots)
 *
 * Dieselbe Bauform wie die HSB-Karten oben, nur ein anderer Farbraum: die
 * Java-Seite haelt diese Farben als /net/impulse/color/{r,g,b} bzw.
 * /net/impulse/stripeColor/<n>/{r,g,b}, also als drei
 * RemoteControlledFloatParameter in 0..1. Umgerechnet wird hier, gesendet
 * werden weiter die Einzelkanaele -- an dem, was bei imPulse ankommt, aendert
 * sich nichts.
 * ------------------------------------------------------------------------- */

const rgbCards = [];           // { base, input, components }

function syncRgbCard(card) {
  const r = controls.get(card.components.r.address).get();
  const g = controls.get(card.components.g.address).get();
  const b = controls.get(card.components.b.address).get();
  card.input.value = rgbToHex(r, g, b);
}

function rgbToHex(r, g, b) {
  return '#' + [r, g, b].map((c) => {
    const byte = Math.round(Math.min(1, Math.max(0, Number(c) || 0))*255);
    return byte.toString(16).padStart(2, '0');
  }).join('');
}

function hexToRgb(hex) {
  const value = parseInt(hex.slice(1), 16);
  return [((value >> 16) & 255)/255, ((value >> 8) & 255)/255, (value & 255)/255];
}

function buildRgbCard(control, values, extraClass) {
  const wrap = document.createElement('div');
  wrap.className = 'param color rgb' + (extraClass ? ' ' + extraClass : '');

  const head = document.createElement('div');
  head.className = 'param-head';
  const name = document.createElement('span');
  name.className = 'param-name';
  name.title = control.base + '/{r,g,b}';
  name.textContent = control.label || splitAddress(control.base).leaf;
  const kind = document.createElement('span');
  kind.className = 'param-range';
  kind.textContent = 'Farbe (RGB)';
  head.appendChild(name);
  head.appendChild(kind);
  wrap.appendChild(head);
  if (control.help) {
    const help = document.createElement('p');
    help.className = 'help';
    help.textContent = control.help;
    wrap.appendChild(help);
  }

  const row = document.createElement('div');
  row.className = 'swatch-row';
  const picker = document.createElement('input');
  picker.type = 'color';
  picker.setAttribute('aria-label', control.label || control.base);
  row.appendChild(picker);
  wrap.appendChild(row);

  // Kein einziger sichtbarer Kanalregler, aber alle drei Adressen in der
  // controls-Map -- siehe headlessControl().
  const card = { base: control.base, input: picker,
                 components: control.components, kind: 'rgb' };
  ['r', 'g', 'b'].forEach((key) => {
    headlessControl(control.components[key], values[control.components[key].address]);
  });
  rgbCards.push(card);
  syncRgbCard(card);

  wrap.appendChild(paletteRowFor(control));
  wrap.addEventListener('pointerdown', () => { activeColorCard = control; });
  wrap.addEventListener('focusin', () => { activeColorCard = control; });

  picker.addEventListener('input', () => {
    const rgb = hexToRgb(picker.value);
    // auf das Raster der Parameter runden, damit angezeigter und gesendeter
    // Wert nicht auseinanderlaufen -- dieselbe Regel wie bei den HSB-Karten
    const updates = ['r', 'g', 'b'].map((key, i) => ({
      address: control.components[key].address,
      value: roundToStep(rgb[i], control.components[key]),
    }));
    updates.forEach((u) => controls.get(u.address).set(u.value, true));
    queueSendMany('rgb:' + control.base, updates);
  });

  return wrap;
}

/* Impuls-Farbe: der Moduswahlschalter und die zwei Farbquellen, zwischen denen
 * er umschaltet.
 *
 * Beide Quellen bleiben SICHTBAR, die gerade unwirksame wird nur gedimmt. Sie
 * auszublenden waere kuerzer, liesse den Operator aber im Zweifel, ob das
 * Feature fehlt oder nur gerade nicht dran ist -- und wer die acht Slots
 * einstellen will, bevor er umschaltet, kaeme gar nicht an sie heran. */
function buildImpulseColor(data, host) {
  const colors = data.colors;
  if (!colors || (!colors.impulse && !(colors.stripes || []).length)) { return; }

  const section = document.createElement('section');
  section.className = 'seq';
  const title = document.createElement('h2');
  title.textContent = 'Impuls-Farbe';
  section.appendChild(title);

  const body = document.createElement('div');
  body.className = 'color-body';

  const specific = document.createElement('div');
  const stripes = document.createElement('div');

  function applyMode(value, silent) {
    const on = Number(value) >= 1;
    specific.classList.toggle('inactive', !on);
    stripes.classList.toggle('inactive', on);
    if (bar) { bar.mark(on ? 1 : 0); }
    if (!silent && colors.mode) { queueSend(colors.mode.address, on ? 1 : 0); }
  }

  // Zwei gleichrangige Zustaende, also ein Auswahlbalken und kein
  // an/aus-Haekchen: "aus" waere fuer "Stripe-Farben" die falsche Beschreibung
  // -- es ist ein eigener Modus, nicht die Abwesenheit eines anderen.
  let bar = null;
  if (colors.mode) {
    bar = modeBar([colors.modeLabels['0'], colors.modeLabels['1']],
                  (index) => applyMode(index, false));
    body.appendChild(bar.element);
    controls.set(colors.mode.address, {
      element: bar.element,
      set: (v, silent) => applyMode(v, silent !== false),
      get: () => (specific.classList.contains('inactive') ? 0 : 1),
      flash: () => {},
    });
  }

  if (colors.impulse) {
    specific.className = 'color-cards';
    specific.appendChild(buildRgbCard(colors.impulse, data.values));
    body.appendChild(specific);
  }

  if ((colors.stripes || []).length) {
    const sub = document.createElement('h3');
    sub.className = 'song-sub';
    sub.textContent = 'Stripe-Farben';
    stripes.appendChild(sub);
    const note = document.createElement('p');
    note.className = 'help';
    // Die Modulo-Regel ist von aussen nicht zu erraten und erklaert, warum
    // acht Farben fuer 30 Stripes reichen.
    note.textContent = 'Acht Slots fuer 30 Stripes: Stripe 0 nimmt Slot 0, '
      + 'Stripe 8 wieder Slot 0. Wirkt nur im Modus „Stripe-Farben“.';
    stripes.appendChild(note);
    const grid = document.createElement('div');
    grid.className = 'stripe-colors';
    colors.stripes.forEach((card) => {
      grid.appendChild(buildRgbCard(card, data.values, 'slot'));
    });
    stripes.appendChild(grid);
    body.appendChild(stripes);
  }

  applyMode(colors.mode ? data.values[colors.mode.address] : 1, true);
  section.appendChild(body);
  host.appendChild(section);
}

/* Auswahlbalken aus zwei oder mehr gleichrangigen Zustaenden. Gleiche Bauform
 * wie treeBar() und die Notenwert-Leiste, aber ohne eigene OSC-Anbindung --
 * der Aufrufer entscheidet, was ein Klick bedeutet. */
function modeBar(labels, onPick) {
  const bar = document.createElement('div');
  bar.className = 'tree-bar mode-bar';
  const buttons = [];
  labels.forEach((label, index) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = label;
    button.addEventListener('click', () => onPick(index));
    buttons.push(button);
    bar.appendChild(button);
  });
  return {
    element: bar,
    mark: (index) => buttons.forEach((b, i) => {
      b.setAttribute('aria-pressed', i === index ? 'true' : 'false');
    }),
  };
}

/* Nachleuchten: Zielfarbe und Tempo statt drei Zerfallsraten.
 *
 * /net/impulse/fadeOut/{r,g,b} sind KEINE Farbe -- der Effekt multipliziert
 * den ganzen LED-Puffer je Frame damit. Ein Waehler direkt darauf waere
 * irrefuehrend: "heller im Waehler" hiesse dort "zerfaellt langsamer".
 *
 * Gerechnet wird deshalb auf dem Server (POST /api/fadeout). Hier steht
 * bewusst KEINE Umrechnung: eine zweite Kopie der Formel waere eine zweite
 * Wahrheit, und geprueft ist nur die auf der Server-Seite. */
function buildFade(data, host) {
  const fade = data.fade;
  if (!fade) { return; }

  const section = document.createElement('section');
  section.className = 'seq';
  const title = document.createElement('h2');
  // Nicht nur "Nachleuchten": Master/trace im Mixer-Tab heisst auch so und
  // ist etwas anderes (das ganze Bild statt nur der Impuls-Spur).
  title.textContent = 'Nachleuchten der Impulse';
  section.appendChild(title);

  const body = document.createElement('div');
  body.className = 'color-body';

  const intro = document.createElement('p');
  intro.className = 'help';
  intro.textContent = 'Jeder Impuls zieht eine Spur, die langsam verlischt. '
    + 'Der Waehler bestimmt, in welche Farbe sie dabei hinein verblasst, der '
    + 'Regler darunter, wie schnell das geht.';
  body.appendChild(intro);

  const row = document.createElement('div');
  row.className = 'swatch-row';
  const picker = document.createElement('input');
  picker.type = 'color';
  picker.setAttribute('aria-label', 'Zielfarbe des Nachleuchtens');
  row.appendChild(picker);
  const hint = document.createElement('span');
  hint.className = 'param-range';
  hint.textContent = 'Farbe am Ende der Spur';
  row.appendChild(hint);
  body.appendChild(row);

  const speedRow = document.createElement('label');
  speedRow.className = 'mini-row';
  const caption = document.createElement('span');
  caption.textContent = 'Tempo';
  const range = document.createElement('input');
  range.type = 'range';
  range.min = 0;
  range.max = 1;
  range.step = 0.001;
  const out = document.createElement('output');
  speedRow.appendChild(caption);
  speedRow.appendChild(range);
  speedRow.appendChild(out);
  body.appendChild(speedRow);

  const note = document.createElement('p');
  note.className = 'help';
  note.textContent = 'Ganz rechts steht die Spur fuer immer, ganz links ist '
    + 'sie im naechsten Bild weg. Die drei Zerfallsraten, die imPulse '
    + 'tatsaechlich bekommt, werden daraus gerechnet.';
  body.appendChild(note);

  const addr = document.createElement('code');
  addr.className = 'param-addr';
  addr.textContent = fade.addresses.join('  ');
  body.appendChild(addr);

  let target = { r: fade.target.r, g: fade.target.g, b: fade.target.b };
  let decay = fade.decay;
  let timer = null;

  function redraw() {
    picker.value = rgbToHex(target.r, target.g, target.b);
    range.value = decay;
    // Zwei Nachkommastellen reichen fuer die Anzeige; gesendet wird der volle
    // Wert. Bei 40 Bildern je Sekunde ist der Unterschied zwischen 0,970 und
    // 0,971 gut sichtbar, deshalb drei Stellen.
    out.textContent = Number(decay).toFixed(3);
  }

  function send() {
    if (timer) { clearTimeout(timer); }
    timer = setTimeout(async () => {
      timer = null;
      try {
        const response = await fetch('/api/fadeout', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ r: target.r, g: target.g, b: target.b,
                                 decay: decay }),
        });
        const payload = await response.json();
        if (!payload.ok) {
          setStatus('Nachleuchten: ' + (payload.error || 'unbekannter Fehler'), 'err');
          return;
        }
        // Die drei Handles still nachziehen, damit ein spaeteres Preset-Laden
        // und der applied-Ruecklauf auf demselben Stand aufsetzen.
        payload.applied.forEach((entry) => {
          const control = controls.get(entry.address);
          if (control) { control.set(entry.value, true); }
        });
        setStatus(payload.applied
          .map((e) => `${e.address} = ${Number(e.value).toFixed(4)}`)
          .join(', '), 'ok');
      } catch (err) {
        setStatus('Nachleuchten nicht gesendet: ' + err, 'err');
      }
    }, DEBOUNCE_MS);
  }

  picker.addEventListener('input', () => {
    const rgb = hexToRgb(picker.value);
    target = { r: rgb[0], g: rgb[1], b: rgb[2] };
    send();
  });
  range.addEventListener('input', () => {
    decay = Number(range.value);
    out.textContent = decay.toFixed(3);
    send();
  });

  // Die drei rohen Adressen bleiben in der controls-Map: ohne sie liefe der
  // applied/echoed-Ruecklauf aus /api/set ins Leere, und das Preset-Laden
  // meldete drei Regler weniger als es nachgezogen hat.
  fade.addresses.forEach((address) => {
    const param = { address: address, type: 'float', min: 0, max: 1 };
    headlessControl(param, fade.raw[address]);
  });

  // Nach einem Preset-Wechsel kommt die neue Zielfarbe fertig gerechnet vom
  // Server -- hier wird nur angezeigt. Siehe api_preset_load().
  fadeSections.push((next) => {
    if (!next) { return; }
    target = { r: next.target.r, g: next.target.g, b: next.target.b };
    decay = next.decay;
    redraw();
  });

  redraw();
  section.appendChild(body);
  host.appendChild(section);
}

// Rueckruf je Nachleucht-Sektion, aufgerufen nach einem Preset-Wechsel.
const fadeSections = [];

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
  // Die Palette haelt Hue/Sat/Bright. Eine RGB-Karte (Impulsfarbe, die acht
  // Stripe-Slots) braucht dieselbe Farbe in ihrem eigenen Farbraum -- sonst
  // waere die "Palette, die von allen gewaehlt werden kann" genau an den
  // Karten nicht verfuegbar, die es seit 2026-08-01 dazugibt.
  let updates;
  if (control.kind === 'rgb') {
    const rgb = hsbToRgb(entry.hue, entry.sat, entry.bright);
    updates = ['r', 'g', 'b'].map((key, i) => ({
      address: control.components[key].address,
      value: roundToStep(rgb[i], control.components[key]),
    }));
  } else {
    updates = [
      { address: control.components.hue.address,
        value: roundToStep(entry.hue, control.components.hue) },
      { address: control.components.sat.address,
        value: roundToStep(entry.sat, control.components.sat) },
      { address: control.components.bright.address,
        value: roundToStep(entry.bright, control.components.bright) },
    ];
  }
  updates.forEach((u) => controls.get(u.address).set(u.value, true));
  // Das Farbfeld der Karte zieht nicht von selbst nach: es haengt am
  // input-Ereignis seines eigenen Waehlers, und set(..., true) loest keins
  // aus (dieselbe Regel wie beim Preset-Laden).
  const card = colorCards.find((c) => c.base === control.base);
  if (card) { syncColorCard(card); }
  const rgbCard = rgbCards.find((c) => c.base === control.base);
  if (rgbCard) { syncRgbCard(rgbCard); }
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
    // Die Palette speichert Hue/Sat/Bright. Kommt die Farbe von einer
    // RGB-Karte, wird sie hier umgerechnet -- die Palette soll denselben
    // Eintrag liefern, egal von welcher Karte er stammt.
    let hsb;
    if (activeColorCard.kind === 'rgb') {
      hsb = rgbToHsb(controls.get(activeColorCard.components.r.address).get(),
                     controls.get(activeColorCard.components.g.address).get(),
                     controls.get(activeColorCard.components.b.address).get());
    } else {
      hsb = [controls.get(activeColorCard.components.hue.address).get(),
             controls.get(activeColorCard.components.sat.address).get(),
             controls.get(activeColorCard.components.bright.address).get()];
    }
    const entry = { name: name, hue: hsb[0], sat: hsb[1], bright: hsb[2] };
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
  rgbCards.length = 0;
  fadeSections.length = 0;
  // Die Palette-Registrierungen zeigen nach einem Neuaufbau auf Elemente,
  // die nicht mehr im Dokument stehen -- ohne das Zuruecksetzen wuechsen
  // paletteRows bei jedem "Neu laden" an.
  paletteRows.length = 0;
  paletteBarEl = null;
  activeColorCard = null;

  // Alle Tabs vollstaendig bauen (siehe buildTabs: die controls-Map muss
  // komplett sein, auch fuer inaktive Tabs).
  buildTabs(data);
  buildMelody(data);

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
// Melodie-Zuordnung
//
// Vier Werte plus ein Knopf. Der Unterschied zu allem anderen im UI: das
// Verstellen sendet NICHTS. Erst der Knopf setzt die vier Werte und loest
// danach /net/melody/recompute aus -- ein Vorgang, kein Regler.
//
// Deshalb die Bestaetigung davor: die Aktion verwirft einen aufgebauten
// Zustand vollstaendig, ueberschreibt auch von Hand korrigierte Zeilen in
// der Zuordnungsdatei und laesst sich nicht zuruecknehmen. Dieselbe
// Ueberlegung wie bei der Taste L in den zwei Kalibriermodi, die aus genau
// diesem Grund zweimal gedrueckt werden will.
// ---------------------------------------------------------------------------

function melodyFieldRow(field, modeNames, value) {
  const row = document.createElement('div');
  row.className = 'melody-field';

  const label = document.createElement('label');
  label.textContent = field.label;
  row.appendChild(label);

  let input;
  if (field.key === 'mode' && modeNames && modeNames.length) {
    // Der Modus ist eine Aufzaehlung, kein Zahlenbereich -- als Zahlenfeld
    // muesste der Operator die Reihenfolge auswendig koennen.
    input = document.createElement('select');
    modeNames.forEach((name, index) => {
      if (index < field.min || index > field.max) { return; }
      const option = document.createElement('option');
      option.value = String(index);
      option.textContent = `${index} \u2013 ${name}`;
      input.appendChild(option);
    });
  } else {
    input = document.createElement('input');
    input.type = 'number';
    input.min = String(field.min);
    input.max = String(field.max);
    input.step = '1';
  }
  input.id = `melody_${field.key}`;
  label.setAttribute('for', input.id);
  input.value = String(Math.round(Number(value)));
  row.appendChild(input);

  const hint = document.createElement('span');
  hint.className = 'melody-hint';
  // Die Range dazu: startNode haengt an der Kreuzungszahl und aendert sich
  // nach jeder Kalibriersitzung, sie gehoert also sichtbar ans Feld.
  hint.textContent = `${field.hint} (${field.min}\u2013${field.max})`;
  row.appendChild(hint);

  return { row, input };
}

function buildMelody(data) {
  melodyInputs.clear();
  melodyFieldsEl.innerHTML = '';
  const melody = data.melody;
  if (!melody || !melody.fields || !melody.fields.length) {
    // Aelterer imPulse-Stand ohne /net/melody/*: die Sektion bleibt weg,
    // statt leer und unbedienbar dazustehen.
    melodyEl.hidden = true;
    return;
  }
  melody.fields.forEach((field) => {
    const value = data.values[field.address];
    const { row, input } = melodyFieldRow(field, melody.modeNames,
      value === undefined ? field.value : value);
    melodyFieldsEl.appendChild(row);
    melodyInputs.set(field.key, input);
  });
  melodyEl.hidden = false;
  // Die Bestaetigung gilt fuer EINEN Druck: nach dem Bauen ist sie aus, und
  // recomputeMelody() setzt sie danach wieder zurueck.
  melodyConfirmEl.checked = false;
  melodyRecomputeEl.disabled = true;
}

async function recomputeMelody() {
  if (!melodyConfirmEl.checked) { return; }
  const values = {};
  let problem = null;
  melodyInputs.forEach((input, key) => {
    const number = Number(input.value);
    if (!Number.isFinite(number)) { problem = problem || key; return; }
    values[key] = Math.round(number);
  });
  if (problem) {
    setStatus(`Melodie: ${problem} ist keine Zahl`, 'err');
    return;
  }
  melodyRecomputeEl.disabled = true;
  setStatus('Melodie wird neu berechnet ...', 'warn');
  try {
    const response = await fetch('/api/melody/recompute', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ values }),
    });
    const data = await response.json();
    if (!response.ok || !data.ok) {
      setStatus('Neuberechnen fehlgeschlagen: ' + (data.error || response.status), 'err');
    } else {
      // Es gibt keinen Rueckkanal von imPulse -- die Meldung sagt deshalb,
      // was GESENDET wurde, und wo das Ergebnis nachzusehen ist. Ein "fertig"
      // waere hier eine Behauptung.
      setStatus(`${data.command} gesendet (${data.applied.length} Werte). `
        + 'Ergebnis steht in der Konsole von imPulse und in '
        + 'data/nodeMelody_<modus>.txt.', 'ok');
    }
  } catch (err) {
    setStatus('Neuberechnen fehlgeschlagen: ' + err, 'err');
  }
  // Bestaetigung immer zuruecksetzen, auch nach einem Fehler: ein zweiter
  // Druck soll eine zweite bewusste Entscheidung sein.
  melodyConfirmEl.checked = false;
}

melodyConfirmEl.addEventListener('change', () => {
  melodyRecomputeEl.disabled = !melodyConfirmEl.checked;
});
melodyRecomputeEl.addEventListener('click', recomputeMelody);

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
    rgbCards.forEach(syncRgbCard);
    // Die Nachleucht-Sektion zeigt Zielfarbe und Tempo, nicht die drei rohen
    // Raten -- sie kann sich aus data.values nicht nachziehen. Der Server
    // legt den fertig gerechneten Block der Antwort bei.
    fadeSections.forEach((update) => update(data.fade));

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

/* Ein Satz Gewichte als Verteilung: ein Balken mit den normierten Anteilen,
 * darunter ein Schieber je Klasse.
 *
 * Dreimal gebraucht (Speed-Klassen, Zweigzahl einer Aufspaltung, Notenwert
 * des Split-Versatzes) und deshalb hier statt dreimal abgeschrieben - die
 * Java-Seite teilt sich fuer dieselbe Rechnung ja auch WeightedChoice.
 *
 * Der Balken haengt an der set()-METHODE der Regler, nicht nur am
 * input-Event: das Laden eines Presets ruft control.set(wert, true) und loest
 * bewusst kein input aus - an einem reinen Event-Listener bliebe der Balken
 * danach auf der alten Verteilung stehen.
 *
 * emptyText ist der Rueckfall, wenn alle Gewichte auf 0 stehen. Er muss
 * nennen, was der Sketch dann WIRKLICH tut (WeightedChoice faellt auf den
 * neutralen Index zurueck) - "nichts" waere die naheliegende und falsche
 * Vermutung.
 */
function weightBank(weights, values, emptyText, options) {
  const opts = options || {};
  const colorOffset = opts.colorOffset || 0;
  const labelOf = opts.labelOf || ((w) => w.label);

  const bar = document.createElement('div');
  bar.className = 'dist';
  const group = document.createElement('div');
  group.className = 'mini';
  const handles = [];

  function redraw() {
    const amounts = handles.map((h) => Math.max(0, h.get()));
    const total = amounts.reduce((a, b) => a + b, 0);
    bar.innerHTML = '';
    if (total <= 0) {
      const empty = document.createElement('span');
      empty.className = 'dist-empty';
      empty.textContent = emptyText;
      bar.appendChild(empty);
      return;
    }
    weights.forEach((weight, i) => {
      if (amounts[i] <= 0) { return; }
      const share = amounts[i]/total;
      const seg = document.createElement('span');
      seg.className = 'dist-seg';
      seg.style.flexGrow = String(share);
      seg.style.flexBasis = '0';
      seg.style.background = trackColor(i + colorOffset);
      seg.title = labelOf(weight) + ': ' + (share*100).toFixed(1) + ' %';
      seg.textContent = share >= 0.08
        ? labelOf(weight) + ' ' + Math.round(share*100) + '%' : '';
      bar.appendChild(seg);
    });
  }

  weights.forEach((weight, i) => {
    const handle = miniSlider(labelOf(weight), weight, values[weight.address],
      (v) => v.toFixed(0));
    handle.element.style.setProperty('--tc', trackColor(i + colorOffset));
    const innerSet = handle.set;
    handle.set = (value, silent) => {
      innerSet(value, silent);
      redraw();
    };
    controls.set(weight.address, handle);
    handle.element.querySelector('input[type=range]')
      .addEventListener('input', redraw);
    handles.push(handle);
    group.appendChild(handle.element);
  });

  redraw();
  return { bar: bar, sliders: group, redraw: redraw };
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

  // Legende: was die kompakt beschrifteten Regler einer Karte bedeuten.
  // Einmal unter der Reihe statt sechsmal in den Karten -- 36 Absaetze
  // machten genau das Panel unbedienbar, das die flache Reglerliste ersetzt.
  if (seq.legend && seq.legend.length) {
    const legend = document.createElement('dl');
    legend.className = 'seq-legend';
    seq.legend.forEach((entry) => {
      const term = document.createElement('dt');
      term.textContent = entry.label;
      const desc = document.createElement('dd');
      desc.textContent = entry.text;
      legend.appendChild(term);
      legend.appendChild(desc);
    });
    section.appendChild(legend);
  }

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

/* Grundtempo und Speed-Klassen. Der Verteilungsbalken macht aus fuenf
 * Gewichten ein Bild - die Zahlen allein verraten nicht, wie selten ein
 * 8x-Ausreisser wirklich ist, weil sie nicht auf 100 normiert sind.
 *
 * Der Grundregler /net/impulse/speed steht seit 2026-08-02 GANZ OBEN in
 * dieser Sektion, nicht mehr im Tab Impuls-Verhalten: die Klassen darunter
 * tun nichts anderes, als ihn zu vervielfachen, und ein Tabwechsel zwischen
 * dem Wert und seinen Vielfachen war genau die Trennung, die den frueheren
 * zweiten Basis-Regler (speedQuantize/baseSpeed) plausibel gemacht hat. Er
 * steht ueber dem Ein/Aus-Schalter, weil er in BEIDEN Zustaenden gilt. */
function buildSpeedClasses(data, host) {
  const speed = data.speedClasses;
  if (!speed) { return; }

  const section = document.createElement('section');
  section.className = 'seq';

  const title = document.createElement('h2');
  title.textContent = 'Grundtempo und Speed-Klassen';
  section.appendChild(title);

  const body = document.createElement('div');
  body.style.padding = '0.7rem 0.8rem';
  body.style.display = 'grid';
  body.style.gap = '0.6rem';

  // Der Grundregler selbst. Laeuft ueber denselben queueSend-Weg wie jeder
  // andere Regler, die Speed-Kopplung im Server haengt also unveraendert an
  // ihm (lifetime, nodeDeadTime, randomSpawn/interval ziehen mit, sofern die
  // Kopplung in der Kopfzeile eingeschaltet ist).
  if (speed.base) {
    const base = miniSlider(speed.base.label || 'Grundgeschwindigkeit',
      speed.base, data.values[speed.base.address]);
    body.appendChild(base.element);
    if (speed.base.help) {
      const baseNote = document.createElement('p');
      baseNote.className = 'help';
      baseNote.textContent = speed.base.help;
      body.appendChild(baseNote);
    }
  }

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

  // Rueckfalltext: genau das macht SpeedQuantizer.pick() bei lauter Nullen.
  const bank = weightBank(speed.weights, data.values,
    'alle Gewichte 0 – es gilt 1×');
  body.appendChild(bank.bar);

  const help = document.createElement('p');
  help.className = 'help';
  help.textContent = 'Anteil der Impulse je Klasse. Vielfaches der '
    + 'Grundgeschwindigkeit oben – 1× ist der Normalfall, hohe Klassen sind '
    + 'die seltenen Ausreisser. Die Gewichte werden normalisiert, sie muessen '
    + 'sich nicht zu 100 summieren.';
  body.appendChild(help);
  body.appendChild(bank.sliders);

  if (speed.jitter) {
    const jitter = miniSlider('Swing', speed.jitter,
      data.values[speed.jitter.address]);
    body.appendChild(jitter.element);
    const note = document.createElement('p');
    note.className = 'help';
    note.textContent = speed.jitter.help || '';
    body.appendChild(note);
  }

  section.appendChild(body);
  host.appendChild(section);
}

/* Split-Verhalten: wieviele Zweige eine Kreuzung nimmt, und wie weit sie
 * zeitlich auseinander starten.
 *
 * ZWEI Verteilungen, beide ueber weightBank() wie die Speed-Klassen daneben:
 * wieviele Zweige eine Kreuzung nimmt, und mit welchem Notenwert sie
 * auseinander starten. Gewichte sind eine Verteilung, kein Satz unabhaengiger
 * Zahlen -- ohne den Balken rechnet der Operator im Kopf, was 40/25/10
 * eigentlich bedeutet.
 *
 * Der Notenwert hatte bis 2026-08-01 eine Notenwert-Leiste (noteBar), weil er
 * ein einzelner fester Wert war. Er wird jetzt je Aufspaltung gezogen -- eine
 * Leiste zeigte eine Auswahl, die es nicht mehr gibt. */
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

  if (split.weights && split.weights.length) {
    // Rueckfalltext: genau das macht SplitFanout.branchCount() bei lauter
    // Nullen - der Ausfallmodus ist das bisherige Verhalten, nicht Stille.
    const fanout = weightBank(split.weights, data.values,
      'alle Gewichte 0 – es gilt "alle Zweige"');
    body.appendChild(fanout.bar);

    const help = document.createElement('p');
    help.className = 'help';
    help.textContent = 'Wieviele der moeglichen Zweige eine Kreuzung nimmt. '
      + 'Welche wegfallen, wird je Aufspaltung gewuerfelt. Die Gewichte werden '
      + 'normalisiert, sie muessen sich nicht zu 100 summieren.';
    body.appendChild(help);
    body.appendChild(fanout.sliders);
  }

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

  if (split.staggerWeights && split.staggerWeights.length) {
    // Farbversatz, damit die zweite Verteilung nicht in denselben drei Farben
    // erscheint wie die erste darueber - zwei gleich eingefaerbte Balken
    // untereinander liest man als Fortsetzung, nicht als zweite Sache.
    //
    // Rueckfalltext: SplitStagger.pickNoteValue() faellt bei lauter Nullen auf
    // Sechzehntel zurueck, nicht auf "kein Versatz".
    const stagger = weightBank(split.staggerWeights, data.values,
      'alle Gewichte 0 – es gilt Sechzehntel',
      { colorOffset: split.weights ? split.weights.length : 0,
        labelOf: (w) => w.symbol + ' ' + w.label });
    body.appendChild(stagger.bar);

    const caption = document.createElement('p');
    caption.className = 'help';
    // Zwei Dinge, die man nicht erraten kann: der Takt kommt aus
    // /net/sequencer/bpm, auch wenn der Sequencer aus ist (die Uhr laeuft
    // dort unabhaengig weiter, siehe tickSequencer), und der Notenwert wird
    // je AUFSPALTUNG gezogen, nicht je Zweig - die Kinder eines Splits
    // stehen also immer auf demselben Raster.
    caption.textContent = 'Abstand zwischen dem 1., 2. und 3. Zweig einer '
      + 'Aufspaltung. Der Notenwert wird je Aufspaltung gezogen, nicht je '
      + 'Zweig – die Kinder eines Splits stehen immer auf demselben Raster. '
      + 'Das Raster kommt von /net/sequencer/bpm – auch dann, wenn der '
      + 'Sequencer selbst aus ist.';
    body.appendChild(caption);
    body.appendChild(stagger.sliders);
  }

  section.appendChild(body);
  host.appendChild(section);
}

/* Song-Struktur: die Dramaturgie ueber eine ganze Nacht.
 *
 * Vier Bloecke: Not-Aus, Live-Zustand, Uebergangsmatrix als 4x4-Gitter,
 * Verweildauern je Level. Dazu vier Knoepfe fuer den manuellen Sprung.
 *
 * Die Matrix ist bewusst ein GITTER und keine Liste aus sechzehn Reglern:
 * "Zeile = wo ich bin, Spalte = wo ich hinkoennte" ist in einem Gitter auf
 * einen Blick zu sehen und in einer Liste gar nicht.
 *
 * Der Prozentwert neben jedem Regler ist der NORMIERTE Anteil, der Regler
 * selbst zeigt das rohe Gewicht. Die zwei Zahlen unterscheiden sich, sobald
 * eine Zeile sich nicht zu 100 summiert - und genau das ist erlaubt, sonst
 * muesste man bei jeder Aenderung die anderen drei nachrechnen. */
function buildSongStructure(data, host) {
  const song = data.songStructure;
  if (!song) { return; }

  const section = document.createElement('section');
  section.className = 'seq';

  const title = document.createElement('h2');
  title.textContent = 'Song-Struktur';
  section.appendChild(title);

  const body = document.createElement('div');
  body.style.padding = '0.7rem 0.8rem';
  body.style.display = 'grid';
  body.style.gap = '0.8rem';

  // ---- Not-Aus -----------------------------------------------------------
  const power = document.createElement('label');
  power.className = 'seq-power';
  power.style.justifySelf = 'start';
  power.title = song.enabled.address;
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
    powerText.textContent = on
      ? 'Dramaturgie laeuft' : 'aus – der Preset-Scheduler entscheidet';
    if (!silent) { queueSend(song.enabled.address, on ? 1 : 0); }
  }
  powerBox.addEventListener('change', () => applyPower(powerBox.checked ? 1 : 0, false));
  applyPower(data.values[song.enabled.address], true);
  controls.set(song.enabled.address, {
    element: power,
    set: (v, silent) => applyPower(v, silent !== false),
    get: () => (powerBox.checked ? 1 : 0),
    flash: () => {},
  });
  body.appendChild(power);

  const intro = document.createElement('p');
  intro.className = 'help';
  intro.textContent = 'Waehlt bei jedem faelligen Wechsel zuerst das naechste '
    + 'Energie-Level (gewichtet nach der Zeile des aktuellen), dann ein Preset '
    + 'aus diesem Level. Wer welchem Level angehoert, steht in '
    + 'data/energyLevels.txt. Solange das hier an ist, hat es Vorrang vor dem '
    + 'alphabetischen Preset-Scheduler.';
  body.appendChild(intro);

  // ---- Live-Zustand ------------------------------------------------------
  // Kommt aus data/songStructureState.txt, die imPulse bei jedem Levelwechsel
  // schreibt - NICHT per OSC: es gibt keinen Rueckkanal hierher, imPulse
  // sendet nur an 8002 und dort hoert SuperCollider. Der Server liest die
  // Datei, weil er auf derselben Maschine laeuft; dasselbe Muster wie bei der
  // Preset-Liste.
  const state = document.createElement('div');
  state.className = 'song-state';
  body.appendChild(state);
  renderSongState(state, bootstrap.songState);
  startSongStatePoll(state);

  // ---- Manueller Sprung --------------------------------------------------
  const jump = document.createElement('div');
  jump.className = 'song-jump';
  const jumpLabel = document.createElement('span');
  jumpLabel.textContent = 'Jetzt wechseln zu:';
  jump.appendChild(jumpLabel);
  song.levels.forEach((level, i) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'song-goto';
    button.textContent = level;
    button.style.setProperty('--tc', trackColor(i));
    button.title = (song.hints && song.hints[i]) || level;
    button.addEventListener('click', () => gotoLevel(i + 1, state));
    jump.appendChild(button);
  });
  body.appendChild(jump);

  const jumpHelp = document.createElement('p');
  jumpHelp.className = 'help';
  jumpHelp.textContent = 'Wirkt sofort und einmalig – danach wuerfelt die '
    + 'Matrix wieder. Nur wirksam, wenn die Dramaturgie an ist.';
  body.appendChild(jumpHelp);

  // ---- Uebergangsmatrix --------------------------------------------------
  const matrixTitle = document.createElement('h3');
  matrixTitle.className = 'song-sub';
  matrixTitle.textContent = 'Uebergangs-Wahrscheinlichkeiten';
  body.appendChild(matrixTitle);

  const grid = document.createElement('div');
  grid.className = 'song-matrix';
  grid.style.gridTemplateColumns = 'auto repeat(' + song.levels.length + ', 1fr)';

  const corner = document.createElement('div');
  corner.className = 'song-corner';
  corner.textContent = 'von \\ nach';
  grid.appendChild(corner);
  song.levels.forEach((level, i) => {
    const head = document.createElement('div');
    head.className = 'song-head';
    head.textContent = level;
    head.style.setProperty('--tc', trackColor(i));
    grid.appendChild(head);
  });

  const rowHandles = [];
  song.matrix.forEach((row, from) => {
    const rowHead = document.createElement('div');
    rowHead.className = 'song-rowhead';
    rowHead.textContent = song.levels[from];
    rowHead.style.setProperty('--tc', trackColor(from));
    rowHead.title = (song.hints && song.hints[from]) || song.levels[from];
    grid.appendChild(rowHead);

    const handles = [];
    const shares = [];
    rowHandles.push({ handles: handles, shares: shares });

    row.forEach((cell, to) => {
      const box = document.createElement('div');
      box.className = 'song-cell';
      const handle = miniSlider('', cell, data.values[cell.address],
        (v) => v.toFixed(0));
      handle.element.style.setProperty('--tc', trackColor(to));
      const share = document.createElement('span');
      share.className = 'song-share';
      shares.push(share);

      // Umgehaengt wird die set()-METHODE, nicht nur das input-Event: das
      // Laden eines Presets ruft control.set(wert, true) und loest dabei
      // bewusst kein input aus - an einem reinen Event-Listener bliebe die
      // Prozentanzeige auf der alten Verteilung stehen. (Presets koennen die
      // Matrix zwar nicht mehr setzen, siehe PresetStore.EXCLUDED_PREFIXES,
      // aber der Rueckweg aus /api/set geht denselben Weg.)
      const innerSet = handle.set;
      handle.set = (value, silent) => {
        innerSet(value, silent);
        redrawRow(from);
      };
      controls.set(cell.address, handle);
      handle.element.querySelector('input[type=range]')
        .addEventListener('input', () => redrawRow(from));

      handles.push(handle);
      box.appendChild(handle.element);
      box.appendChild(share);
      grid.appendChild(box);
    });
  });

  function redrawRow(from) {
    const entry = rowHandles[from];
    const values = entry.handles.map((h) => Math.max(0, h.get()));
    const total = values.reduce((a, b) => a + b, 0);
    entry.shares.forEach((span, to) => {
      if (total <= 0) {
        // Genau das macht WeightedChoice.pick() bei lauter Nullen: Rueckfall
        // auf "mittel".
        span.textContent = to === 1 ? 'Rueckfall' : '–';
        span.classList.toggle('zero', to !== 1);
        return;
      }
      const share = values[to]/total;
      span.textContent = Math.round(share*100) + ' %';
      span.classList.toggle('zero', values[to] <= 0);
    });
  }
  rowHandles.forEach((_entry, from) => redrawRow(from));
  body.appendChild(grid);

  const matrixHelp = document.createElement('p');
  matrixHelp.className = 'help';
  matrixHelp.textContent = 'Zeile = aktuelles Level, Spalte = naechstes. Der '
    + 'Regler zeigt das rohe Gewicht, die Zahl darunter den daraus normierten '
    + 'Anteil – eine Zeile muss sich nicht zu 100 summieren. Ein Gewicht von 0 '
    + 'wird nie gezogen. Die Diagonale ist der Preset-Wechsel innerhalb '
    + 'desselben Levels.';
  body.appendChild(matrixHelp);

  // ---- Verweildauern -----------------------------------------------------
  const dwellTitle = document.createElement('h3');
  dwellTitle.className = 'song-sub';
  dwellTitle.textContent = 'Verweildauer je Level (Minuten)';
  body.appendChild(dwellTitle);

  const dwell = document.createElement('div');
  dwell.className = 'song-dwell';
  song.dwell.forEach((entry, i) => {
    const card = document.createElement('div');
    card.className = 'song-dwell-row';
    card.style.setProperty('--tc', trackColor(i));
    const name = document.createElement('span');
    name.className = 'song-dwell-name';
    name.textContent = entry.level;
    name.title = (song.hints && song.hints[i]) || entry.level;
    card.appendChild(name);
    card.appendChild(miniSlider('min', entry.min,
      data.values[entry.min.address], (v) => v.toFixed(1)).element);
    card.appendChild(miniSlider('max', entry.max,
      data.values[entry.max.address], (v) => v.toFixed(1)).element);
    dwell.appendChild(card);
  });
  body.appendChild(dwell);

  const dwellHelp = document.createElement('p');
  dwellHelp.className = 'help';
  dwellHelp.textContent = 'Gleichverteilt gezogen, sobald ein Level beginnt. '
    + 'Wird die Spanne waehrend eines laufenden Abschnitts verengt, wird die '
    + 'schon gezogene Dauer darauf geklemmt – ein Verkuerzen wirkt also sofort '
    + 'und man muss nicht die alte Dauer abwarten.';
  body.appendChild(dwellHelp);

  section.appendChild(body);
  host.appendChild(section);
}

/* Die Statuszeile der Song-Struktur. Getrennt von buildSongStructure, weil
 * sie auch aus dem Poll und nach einem manuellen Sprung neu gezeichnet wird. */
function renderSongState(host, state) {
  host.innerHTML = '';
  if (!state || !state.level) {
    const none = document.createElement('span');
    none.className = 'song-state-idle';
    // Vor dem ersten Levelwechsel gibt es die Datei nicht. Das ist der
    // Normalfall beim Start und kein Fehler.
    none.textContent = 'noch kein Levelwechsel seit dem Start von imPulse';
    host.appendChild(none);
    return;
  }
  const index = Number(state.levelIndex);
  const badge = document.createElement('span');
  badge.className = 'song-state-level';
  badge.textContent = state.level;
  if (isFinite(index)) { badge.style.setProperty('--tc', trackColor(index)); }
  host.appendChild(badge);

  const text = document.createElement('span');
  text.className = 'song-state-text';
  let line = state.preset ? 'Preset ' + state.preset : '';
  if (state.dwellSeconds) {
    const total = Number(state.dwellSeconds);
    const since = state.sinceMillis
      ? Math.max(0, Math.round((Date.now() - Number(state.sinceMillis))/1000))
      : null;
    line += ' – ' + Math.round(total/60*10)/10 + ' min gezogen';
    if (since !== null) {
      const left = Math.max(0, total - since);
      line += ', noch ca. ' + Math.floor(left/60) + ':'
        + String(left % 60).padStart(2, '0');
    }
  }
  text.textContent = line;
  host.appendChild(text);
}

/* Pollt den Zustand. Fuenf Sekunden reichen: die kuerzeste Verweildauer sind
 * 30 Sekunden. Ein Push-Kanal (SSE/WebSocket) waere hier eine zweite
 * Verbindungsart fuer eine Zeile Text. */
function startSongStatePoll(host) {
  if (startSongStatePoll.timer) { clearInterval(startSongStatePoll.timer); }
  startSongStatePoll.timer = setInterval(async () => {
    if (!document.body.contains(host)) {
      clearInterval(startSongStatePoll.timer);
      startSongStatePoll.timer = null;
      return;
    }
    try {
      const response = await fetch('/api/songstructure');
      const data = await response.json();
      if (data && data.ok) { renderSongState(host, data.state); }
    } catch (err) {
      // Still: eine Statuszeile darf keine Fehlermeldung im Sekundentakt
      // erzeugen, wenn der Server gerade neu startet.
    }
  }, 5000);
}

/* Manueller Levelwechsel. Eigene Route, weil /songStructure/goto ein KOMMANDO
 * ist und bewusst nicht in remoteSettings.txt steht - /api/set kennt nur
 * Adressen aus dem Dump. */
async function gotoLevel(level, stateHost) {
  try {
    const response = await fetch('/api/goto', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ level: level }),
    });
    const data = await response.json();
    if (!response.ok || !data.ok) {
      setStatus('Levelwechsel fehlgeschlagen: '
        + (data.error || 'HTTP ' + response.status), 'err');
      return;
    }
    setStatus('Levelwechsel nach "' + data.name + '" gesendet – wirkt nur, '
      + 'wenn die Dramaturgie eingeschaltet ist', 'ok');
    // Der Zustand aendert sich erst, wenn imPulse den Wechsel vollzogen und
    // die Datei geschrieben hat. Ein kurzer Nachschlag statt auf den
    // 5-Sekunden-Takt zu warten.
    setTimeout(async () => {
      try {
        const fresh = await fetch('/api/songstructure');
        const payload = await fresh.json();
        if (payload && payload.ok) { renderSongState(stateHost, payload.state); }
      } catch (err) { /* egal */ }
    }, 500);
  } catch (err) {
    setStatus('Netzwerkfehler: ' + err, 'err');
  }
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
      // label kommt aus SC_PARAMS; der Registry-Name ("travelOctavesPerStep")
      // ist die OSC-Kennung und steht als Adresszeile unter dem Regler.
      name.textContent = param.label || param.name;
      name.title = '/klangnetz/param/' + param.name
        + (param.description ? ' – ' + param.description : '');
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

      appendMeta(wrap, { help: param.description },
                 '/klangnetz/param/' + param.name);

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
 * Vierkanal-Mitschnitt
 *
 * Der einzige Knopf im ganzen UI mit einem echten RUECKKANAL: sclang meldet
 * seinen Aufnahmezustand an den Server (siehe RecordStatusListener in
 * server.py), der Knopf zeigt also, was WIRKLICH laeuft -- nicht, was zuletzt
 * geklickt wurde. Das ist hier kein Luxus: eine Aufnahme, die nicht laeuft,
 * faellt sonst erst nach dem Dreh auf.
 *
 * Drei Zustaende, in Farbe UND Form unterschieden (dieselbe Regel wie im
 * Sequencer-Panel, Bedienung im Dunkeln): laeuft (roter Punkt, pulsierend),
 * bereit (hohler Punkt), unbekannt (kein Kontakt zu sclang, Warnton).
 * ------------------------------------------------------------------------- */

const RECORD_POLL_MS = 2000;

// Die Verdrahtung (Port, Bind-Fehler) steht nur im Bootstrap, nicht in der
// Antwort von /api/parameters -- sie aendert sich waehrend einer Sitzung
// nicht. Deshalb hier gemerkt und nicht aus dem render()-Datensatz gelesen:
// nach einem "Neu laden" stuende sie sonst nicht mehr zur Verfuegung.
const recordConfig = bootstrap.record || {};
// Genau EIN Poll-Timer, auch nach mehrfachem "Neu laden". render() baut alle
// Panels neu; ohne das Abraeumen liefe der Timer der alten, laengst aus dem
// Dokument entfernten Sektion weiter -- ein Poll mehr pro Neuladen, fuer
// immer.
let recordTimer = null;

// Nimmt nur den Host, keinen Datensatz -- wie buildPaletteSection(): der
// Knopf haengt an keiner Adresse aus remoteSettings.txt.
function buildRecordSection(host) {
  const config = recordConfig;
  if (recordTimer !== null) { clearInterval(recordTimer); recordTimer = null; }

  const section = document.createElement('section');
  section.className = 'rec';

  const title = document.createElement('h2');
  title.textContent = 'Mitschnitt (4 Kanaele)';
  section.appendChild(title);

  const row = document.createElement('div');
  row.className = 'rec-row';

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'rec-button';
  const dot = document.createElement('span');
  dot.className = 'dot';
  const label = document.createElement('span');
  label.className = 'lbl';
  label.textContent = 'Aufnahme starten';
  button.appendChild(dot);
  button.appendChild(label);
  row.appendChild(button);

  const state = document.createElement('div');
  state.className = 'rec-state';
  const line = document.createElement('span');
  line.className = 'rec-line';
  line.textContent = 'Zustand wird abgefragt …';
  const file = document.createElement('span');
  file.className = 'rec-file';
  state.appendChild(line);
  state.appendChild(file);
  row.appendChild(state);

  section.appendChild(row);

  const help = document.createElement('p');
  help.className = 'help';
  help.textContent = 'Schneidet die vier fertigen Ausgangskanaele mit (nach '
    + 'Panning, Hall und Limiter) nach recordings/klangnetz_<zeitstempel>.wav, '
    + 'WAV/int24 in der Samplerate des Interfaces. Geschrieben wird die Datei '
    + 'von sclang auf Port ' + (config.port || 8002) + ', nicht von diesem UI.';
  section.appendChild(help);

  if (config.error) {
    const warn = document.createElement('p');
    warn.className = 'help warn';
    warn.textContent = config.error + ' – der Knopf sendet trotzdem, kann den '
      + 'Zustand aber nicht anzeigen.';
    section.appendChild(warn);
  }

  // Was der Knopf zuletzt vom Server gehoert hat. null = noch nichts/kein
  // Kontakt; genau dann geht der Klick als /toggle raus, weil ein start oder
  // stop ins Blaue geraten waere.
  let recording = null;
  let busy = false;

  function show(payload) {
    const known = !!(payload && payload.known && payload.answered !== false);
    recording = known ? !!payload.recording : null;
    const path = (payload && payload.path) || '';
    // Nur der Dateiname: der ganze Pfad ist auf einem Telefon-Display die
    // halbe Zeile, und der Ordner steht ohnehin im Hilfetext.
    const name = path.split(/[\\/]/).pop();

    button.classList.toggle('on', recording === true);
    button.classList.toggle('unknown', recording === null);
    if (recording === true) {
      label.textContent = 'Aufnahme stoppen';
      line.textContent = 'REC laeuft';
      line.className = 'rec-line rec-on';
      file.textContent = name || '';
    } else if (recording === false) {
      label.textContent = 'Aufnahme starten';
      line.textContent = 'bereit';
      line.className = 'rec-line';
      file.textContent = name ? 'zuletzt: ' + name : '';
    } else {
      label.textContent = 'Aufnahme umschalten';
      line.textContent = 'kein Kontakt zu sclang';
      line.className = 'rec-line rec-unknown';
      file.textContent = '';
    }
  }

  async function call(path, what) {
    if (busy) { return; }
    busy = true;
    button.disabled = true;
    try {
      const response = await fetch(path, { method: 'POST' });
      const payload = await response.json();
      show(payload);
      if (!payload.answered) {
        setStatus('Mitschnitt: ' + what + ' gesendet, aber keine Antwort von '
          + 'sclang – laeuft es?', 'warn');
      } else {
        setStatus(payload.recording
          ? 'Mitschnitt laeuft: ' + (payload.path || '?')
          : 'Mitschnitt gestoppt' + (payload.path ? ': ' + payload.path : ''),
          'ok');
      }
    } catch (err) {
      setStatus('Mitschnitt: ' + what + ' fehlgeschlagen – ' + err, 'err');
    } finally {
      busy = false;
      button.disabled = false;
    }
  }

  button.addEventListener('click', () => {
    if (recording === true) { call('/api/record/stop', 'Stop'); }
    else if (recording === false) { call('/api/record/start', 'Start'); }
    // Zustand unbekannt: umschalten statt raten. Verliert das UI den Kontakt
    // waehrend einer Aufnahme, bringt ein Klick sie so trotzdem zu Ende.
    else { call('/api/record/toggle', 'Umschalten'); }
  });

  async function poll() {
    // Waehrend eines laufenden Kommandos NICHT dazwischenfragen: die Antwort
    // des Kommandos ist die frischere, und zwei Abfragen gleichzeitig
    // koennten sich in der Anzeige ueberholen.
    if (busy) { return; }
    try {
      const response = await fetch('/api/record/status');
      show(await response.json());
    } catch (err) {
      show(null);            // Server weg: ehrlich "unbekannt" zeigen
    }
  }

  poll();
  recordTimer = setInterval(poll, RECORD_POLL_MS);

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

/* Kopfzeile, Presets und Melodie-Zuordnung sind FESTES Markup aus index.html
 * (ihre Listener haengen an festen IDs, siehe MarkupWiringTest) und werden
 * hier nur umgehaengt. Vor jedem Neuaufbau muessen sie zurueck auf den
 * Abstellplatz: tabPanelsEl.innerHTML = '' wuerde sie sonst mitsamt dem alten
 * Panel aus dem Dokument werfen, und ein "Neu laden" liesse die Presets
 * verschwinden -- und mit der Kopfzeile #status/#autocommit/#meta, die aus dem
 * ganzen Code heraus beschrieben werden. */
function parkFixedSections() {
  if (!parkedEl) { return; }
  if (headlineEl) { parkedEl.appendChild(headlineEl); }
  if (presetsEl) { parkedEl.appendChild(presetsEl); }
  if (melodyEl) { parkedEl.appendChild(melodyEl); }
}

function buildTabs(data) {
  parkFixedSections();
  tabBarEl.innerHTML = '';
  tabPanelsEl.innerHTML = '';
  const tabs = data.tabs || [];
  if (!tabs.length) {
    // Aelterer Server ohne Tab-Daten: alles in einen Block, damit die
    // Oberflaeche nicht leer bleibt. Die drei festen Sektionen kommen dann
    // wieder nach oben -- ohne Tabs gibt es keinen Platz, an den sie sonst
    // gehoerten, und ein Preset-Dropdown im Abstellplatz waere unsichtbar.
    if (headlineEl) { tabPanelsEl.appendChild(headlineEl); }
    if (presetsEl) { tabPanelsEl.appendChild(presetsEl); }
    if (melodyEl) { tabPanelsEl.appendChild(melodyEl); }
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

    // 0. Kopfzeile. Seit 2026-08-02 steht ueber der Tab-Leiste nichts mehr;
    //    Titel, Meta-Zeile, Status, automatische Sicherung, Speed-Kopplung und
    //    "Neu laden" sitzen als erste Karte in demselben Tab wie die Presets.
    //    Der Server sagt nicht eigens, welcher das ist -- sie haengt sich an
    //    die Preset-Sektion, weil beides fuer die ganze Seite gilt und
    //    zusammen gelesen wird (Szene waehlen, Rueckmeldung lesen).
    //
    //    Findet sich kein solcher Tab (aelterer Server), bleibt sie auf dem
    //    Abstellplatz stehen: unsichtbar, aber im Dokument -- #status und
    //    #autocommit werden von ueberall her beschrieben und duerfen nie
    //    fehlen.
    if (headlineEl && (tab.sections || []).indexOf('presets') >= 0) {
      panel.appendChild(headlineEl);
    }

    // 1. Spezial-Sektionen (Presets, Melodie-Zuordnung, Sequencer-Panel,
    //    Speed-Klassen, Split-Verhalten, Palette, Song-Struktur)
    (tab.sections || []).forEach((name) => {
      // Die zwei festen Sektionen werden umgehaengt, nicht gebaut: ihr
      // Markup steht in index.html und traegt die IDs, an denen die Listener
      // schon haengen. Der Server entscheidet ueber tab.sections, WO sie
      // landen -- Presets als erstes im Mixer-Tab, die Melodie-Zuordnung als
      // einziger Inhalt des Tonleiter-Tabs.
      if (name === 'presets' && presetsEl) { panel.appendChild(presetsEl); }
      if (name === 'melody' && melodyEl) { panel.appendChild(melodyEl); }
      if (name === 'sequencer') { buildSequencer(data, panel); }
      if (name === 'speedClasses') { buildSpeedClasses(data, panel); }
      if (name === 'impulseColor') { buildImpulseColor(data, panel); }
      if (name === 'fade') { buildFade(data, panel); }
      if (name === 'palette') { buildPaletteSection(panel); }
      if (name === 'split') { buildSplit(data, panel); }
      if (name === 'songStructure') { buildSongStructure(data, panel); }
      if (name === 'record') { buildRecordSection(panel); }
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

// ---------------------------------------------------------------------------
// Automatische Sicherung
//
// Reine Anzeige. Die relative Zeit rechnet der Browser, weil der Server die
// Uhr des Betrachters nicht kennt -- geliefert werden Unix-Sekunden.
// ---------------------------------------------------------------------------

const AUTOCOMMIT_POLL_MS = 60000;
const AUTOCOMMIT_HINT = 'Lokaler Git-Commit auf dem Rechner, auf dem dieses '
  + 'Web-UI laeuft. Es wird NICHT gepusht – zum Uebertragen weiterhin von '
  + 'Hand: git push';

function relativeTime(seconds) {
  if (seconds === null || seconds === undefined) { return null; }
  const delta = Date.now() / 1000 - seconds;
  if (delta < 90) { return 'gerade eben'; }
  const minutes = Math.round(delta / 60);
  if (minutes < 90) { return 'vor ' + minutes + ' Minuten'; }
  const hours = Math.round(minutes / 60);
  if (hours < 36) { return 'vor ' + hours + ' Stunden'; }
  return 'vor ' + Math.round(hours / 24) + ' Tagen';
}

function renderAutocommit(state) {
  if (!autocommitEl) { return; }
  autocommitEl.title = AUTOCOMMIT_HINT;
  if (!state || !state.enabled) {
    autocommitEl.textContent = 'Automatische Sicherung: aus – Aenderungen an '
      + 'Presets, Farben und Kalibrierung liegen nur auf diesem Rechner, bis '
      + 'jemand sie von Hand committet.';
    autocommitEl.dataset.level = 'off';
    return;
  }
  const minutes = Math.max(1, Math.round((state.intervalSeconds || 0) / 60));
  const parts = ['Automatische Sicherung: alle ' + minutes + ' min lokal '
    + 'committet (kein Push)'];
  const last = relativeTime(state.lastCommitAt);
  parts.push(last ? 'zuletzt gesichert ' + last
                  : 'seit dem Start gab es nichts zu sichern');
  let level = 'ok';
  if (state.lastStatus === 'error') {
    parts.push('FEHLER: ' + (state.lastDetail || 'unbekannt'));
    level = 'err';
  } else if (state.lastStatus === 'skipped') {
    parts.push('uebersprungen: ' + (state.lastDetail || ''));
    level = 'warn';
  }
  autocommitEl.dataset.level = level;
  autocommitEl.textContent = parts.join(' – ');
}

async function pollAutocommit() {
  try {
    const response = await fetch('/api/autocommit');
    if (response.ok) { renderAutocommit(await response.json()); }
  } catch (err) {
    /* Die Anzeige ist Beiwerk; ein Netzfehler darf das UI nicht stoeren. */
  }
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
renderAutocommit(bootstrap.autocommit);
setInterval(pollAutocommit, AUTOCOMMIT_POLL_MS);
render(bootstrap);
startPulseClock();
