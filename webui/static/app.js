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
 * /net/impulse/energyDecay reicht von 0.0001 bis 0.5, mit den zwei Stellen der
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
  controls.set(param.address, handle);
  return handle.element;
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
// Aufbau der Seite
// ---------------------------------------------------------------------------

function render(data) {
  controls.clear();
  colorCards.length = 0;
  groupsEl.innerHTML = '';

  (data.groups || []).forEach((group) => {
    const section = document.createElement('section');
    const title = document.createElement('h2');
    title.textContent = group.title;
    section.appendChild(title);
    group.controls.forEach((control) => {
      if (control.kind === 'color') {
        section.appendChild(buildColorCard(control, data.values));
      } else {
        section.appendChild(buildParam(control, data.values[control.address]));
      }
    });
    groupsEl.appendChild(section);
  });

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

const coupling = bootstrap.coupling || { speedAddress: '/net/impulse/speed', targets: [] };

couplingEl.checked = localStorage.getItem(COUPLING_STORAGE_KEY) !== '0';
couplingEl.addEventListener('change', () => {
  localStorage.setItem(COUPLING_STORAGE_KEY, couplingEl.checked ? '1' : '0');
  setStatus(couplingEl.checked
    ? 'Speed-Kopplung aktiv – Aenderung an ' + coupling.speedAddress + ' zieht '
      + coupling.targets.map((t) => t.address).join(', ') + ' mit'
    : 'Speed-Kopplung aus – ' + coupling.speedAddress + ' wirkt jetzt isoliert');
});

reloadEl.addEventListener('click', reload);

render(bootstrap);
