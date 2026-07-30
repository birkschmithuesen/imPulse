import java.io.DataOutputStream;
import java.util.List;

import netP5.NetAddress;
import oscP5.OscMessage;
import oscP5.OscP5;

// Klebeschicht zwischen den OSC-Befehlen, dem PresetStore, den Parametern und
// SuperCollider. Die pruefbare Logik liegt bewusst nicht hier, sondern in
// PresetStore und PresetScheduler - diese Klasse importiert oscP5 und ist
// deshalb NICHT Teil von test/run.sh.
//
// imPulse ist Master: es gibt genau einen Scheduler, und der laeuft hier.
// Bei jedem Wechsel geht zusaetzlich /sc/preset/load an SuperCollider, damit
// Licht und Klang nicht auseinanderlaufen.
class PresetManager implements OscMessageSink {

	private final PresetStore store;
	private final PresetScheduler scheduler = new PresetScheduler();
	private final OscP5 oscP5;
	private final NetAddress soundTarget;

	// digestMessage() laeuft im Draw-Thread, weil distributeMessages() aus
	// draw() gerufen wird - eine Synchronisierung braucht es hier also nicht.
	// Gemerkt statt sofort ausgefuehrt wird trotzdem, damit das Lesen einer
	// Datei nicht mitten in der Verteilschleife passiert.
	private String pendingLoad = null;
	private String pendingSave = null;
	private boolean pendingNext = false;

	PresetManager(String presetDirectory, OscP5 _oscP5, NetAddress _soundTarget) {
		store = new PresetStore(presetDirectory);
		oscP5 = _oscP5;
		soundTarget = _soundTarget;
		OscMessageDistributor.registerAdress("/preset/load", this);
		OscMessageDistributor.registerAdress("/preset/save", this);
		OscMessageDistributor.registerAdress("/preset/next", this);
		System.out.println("Preset-Ordner: " + store.directoryPath());
	}

	public void digestMessage(OscMessage newMessage) {
		if (newMessage.checkAddrPattern("/preset/next")) {
			pendingNext = true;
			return;
		}
		if (newMessage.checkAddrPattern("/preset/load") && newMessage.arguments().length > 0) {
			pendingLoad = newMessage.get(0).stringValue();
			return;
		}
		if (newMessage.checkAddrPattern("/preset/save") && newMessage.arguments().length > 0) {
			pendingSave = newMessage.get(0).stringValue();
		}
	}

	// Dieser Sink haelt keine Parameter. Ohne das leere writeToStream bekaeme
	// remoteSettings.txt Kommando-Zeilen dazu - genau der Fehler, den das
	// Preset-System an anderer Stelle vermeidet.
	public void writeToStream(DataOutputStream outStream) {
	}

	// Aus draw() zu rufen, direkt nach OscMessageDistributor.distributeMessages().
	// Pro Frame wird nur der jeweils letzte Befehl ausgefuehrt: zwei Loads im
	// selben Frame sind ein Bedienfehler, kein Wunsch.
	void update(long nowMillis, boolean schedulerEnabled, float schedulerIntervalSeconds) {
		if (pendingSave != null) {
			String name = pendingSave;
			pendingSave = null;
			save(name);
		}
		if (pendingLoad != null) {
			String name = pendingLoad;
			pendingLoad = null;
			load(name, nowMillis);
		}
		if (pendingNext) {
			pendingNext = false;
			switchToNext(nowMillis);
		} else if (scheduler.isDue(nowMillis, schedulerEnabled, schedulerIntervalSeconds)) {
			switchToNext(nowMillis);
		}
	}

	// Aus setup() zu rufen, nachdem alle Effekte angelegt sind - vorher sind
	// die Parameter noch nicht registriert.
	void loadBootPreset(String name, long nowMillis) {
		if (name == null || name.trim().length() == 0) {
			return;
		}
		System.out.println("Start-Preset: " + name.trim());
		load(name.trim(), nowMillis);
	}

	boolean load(String name, long nowMillis) {
		List<String[]> entries = store.read(name);
		if (entries == null) {
			// Ein defektes Preset darf die Show nicht anhalten: alle Werte
			// bleiben stehen.
			System.out.println("Preset laden fehlgeschlagen: " + store.lastMessage());
			return false;
		}
		PresetApplyReport report = PresetStore.apply(entries, OscMessageDistributor.presetTargets());
		scheduler.noteLoaded(name, nowMillis);
		System.out.println("Preset \"" + name + "\" geladen: " + report.summary());
		forwardToSound(name);
		return true;
	}

	boolean save(String name) {
		List<String[]> entries = PresetStore.snapshot(OscMessageDistributor.presetTargets());
		if (!store.write(name, entries)) {
			System.out.println("Preset speichern fehlgeschlagen: " + store.lastMessage());
			return false;
		}
		System.out.println("Preset \"" + name + "\" gespeichert: " + store.lastMessage());
		return true;
	}

	private void switchToNext(long nowMillis) {
		String name = scheduler.advance(nowMillis, store.list());
		if (name == null) {
			System.out.println("Preset-Wechsel nicht moeglich: " + store.lastMessage());
			return;
		}
		load(name, nowMillis);
	}

	// Fire-and-forget: imPulse wartet auf keine Antwort. Laeuft sclang nicht,
	// laeuft die Visual-Show trotzdem weiter.
	private void forwardToSound(String name) {
		if (oscP5 == null || soundTarget == null) {
			return;
		}
		OscMessage message = new OscMessage("/sc/preset/load");
		message.add(name);
		oscP5.send(message, soundTarget);
	}
}
