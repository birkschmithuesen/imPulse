import java.io.DataOutputStream;
import java.io.File;
import java.io.PrintWriter;
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

	// Die Song-Struktur-Ebene. Beide duerfen null sein - dann laeuft alles
	// genau wie vor diesem Feature.
	private final SongStructureDirector director;
	private final SongStructureParams songParams;
	private final String statePath;
	private boolean stateWriteFailed = false;

	// data/lastPreset.txt - Boot-Fallback, siehe PresetStore.readLastPresetName
	// fuer die Begruendung. null erlaubt (aeltere Aufrufer/Tests ohne dieses
	// Feature) - dann passiert bei jedem load()/save() einfach nichts.
	private final String lastPresetPath;
	private boolean lastPresetWriteFailed = false;

	// digestMessage() laeuft im Draw-Thread, weil distributeMessages() aus
	// draw() gerufen wird - eine Synchronisierung braucht es hier also nicht.
	// Gemerkt statt sofort ausgefuehrt wird trotzdem, damit das Lesen einer
	// Datei nicht mitten in der Verteilschleife passiert.
	private String pendingLoad = null;
	private String pendingSave = null;
	private boolean pendingNext = false;

	PresetManager(String presetDirectory, OscP5 _oscP5, NetAddress _soundTarget,
			SongStructureDirector _director, SongStructureParams _songParams,
			String _statePath) {
		this(presetDirectory, _oscP5, _soundTarget, _director, _songParams,
				_statePath, null);
	}

	// Ueberladung mit lastPresetPath (Boot-Fallback, Schritt 2a). Eigener
	// Konstruktor statt eines Pflichtparameters am bestehenden: Tests, die
	// den alten Fuenf-Parameter-Aufruf benutzen, bleiben unveraendert
	// lauffaehig - dieselbe Ueberlegung wie bei TravellingActivation weiter
	// oben im Effekt.
	PresetManager(String presetDirectory, OscP5 _oscP5, NetAddress _soundTarget,
			SongStructureDirector _director, SongStructureParams _songParams,
			String _statePath, String _lastPresetPath) {
		store = new PresetStore(presetDirectory);
		oscP5 = _oscP5;
		soundTarget = _soundTarget;
		director = _director;
		songParams = _songParams;
		statePath = _statePath;
		lastPresetPath = _lastPresetPath;
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
			return;
		}
		SongStructureConfig cfg = (songParams == null) ? null : songParams.config();
		if (director != null && cfg != null && cfg.enabled) {
			updateSongStructure(nowMillis, cfg, schedulerIntervalSeconds);
			return;
		}
		if (scheduler.isDue(nowMillis, schedulerEnabled, schedulerIntervalSeconds)) {
			switchToNext(nowMillis);
		}
	}

	// Die Song-Struktur-Ebene hat Vorrang vor dem alphabetischen Wechsler.
	//
	// Zwei Wechsler auf derselben Szene wuerden sich gegenseitig die Presets
	// wegnehmen - ohne Fehlermeldung, nur mit einem Bild, das oefter springt
	// als eingestellt. Der alphabetische Scheduler zieht deshalb nur seinen
	// Timer mit (isDue mit enabled=false), damit ein spaeteres Abschalten der
	// Song-Struktur nicht sofort einen Wechsel ausloest.
	private void updateSongStructure(long nowMillis, SongStructureConfig cfg,
			float schedulerIntervalSeconds) {
		int wish = songParams.takePendingLevel();
		if (wish >= 0) {
			director.requestLevel(wish);
		}
		scheduler.isDue(nowMillis, false, schedulerIntervalSeconds);
		if (!director.isDue(nowMillis, cfg)) {
			return;
		}
		String name = director.nextPreset(nowMillis, cfg, store.list());
		if (name == null) {
			System.out.println("Song-Struktur: kein Wechsel moeglich - "
					+ director.lastMessage());
			return;
		}
		if (director.lastMessage().length() > 0) {
			System.out.println("Song-Struktur: " + director.lastMessage());
		}
		System.out.println("Song-Struktur: Level " + director.currentLevelName()
				+ " fuer " + (director.dwellMillis()/1000L) + " s, Preset \"" + name + "\"");
		load(name, nowMillis);
		writeState(name, nowMillis);
	}

	// Schreibt den Zustand der Song-Struktur nach data/songStructureState.txt.
	//
	// Das ist der einzige Weg, auf dem das Web-UI den Live-Zustand erfaehrt: es
	// gibt keinen OSC-Rueckkanal dorthin, imPulse sendet nur an Port 8002 und
	// dort hoert SuperCollider. server.py laeuft auf derselben Maschine und
	// liest die Datei direkt - dasselbe Muster wie die Preset-Liste, die auch
	// vom Dateisystem kommt und nicht per OSC.
	//
	// Gerufen wird das bei jedem LEVELWECHSEL, also alle paar Minuten, nicht in
	// jedem Frame. Deshalb ist ein Dateizugriff hier vertretbar.
	private void writeState(String name, long nowMillis) {
		if (statePath == null) {
			return;
		}
		File target = new File(statePath);
		File temp = new File(statePath + ".tmp");
		PrintWriter writer = null;
		try {
			writer = new PrintWriter(temp, "UTF-8");
			writer.print("level\t" + director.currentLevelName() + "\n");
			writer.print("levelIndex\t" + director.currentLevel() + "\n");
			writer.print("preset\t" + name + "\n");
			writer.print("sinceMillis\t" + nowMillis + "\n");
			writer.print("dwellSeconds\t" + (director.dwellMillis()/1000L) + "\n");
			writer.flush();
		} catch (Exception e) {
			// Einmal melden, dann nicht mehr: eine Warnung alle paar Minuten
			// ueber eine ganze Nacht waere ein volles Log ohne neuen Inhalt.
			// Die Show laeuft weiter, nur die Anzeige im Web-UI steht still.
			if (!stateWriteFailed) {
				stateWriteFailed = true;
				System.out.println("Song-Struktur: Zustandsdatei nicht schreibbar ("
						+ e + ") - die Anzeige im Web-UI bleibt stehen");
			}
			return;
		} finally {
			if (writer != null) {
				writer.close();
			}
		}
		// renameTo ist auf Windows nur auf ein nicht existierendes Ziel
		// verlaesslich, deshalb vorher loeschen - wie in PresetStore.write().
		if (target.exists() && !target.delete()) {
			return;
		}
		if (!temp.renameTo(target) && !stateWriteFailed) {
			stateWriteFailed = true;
			System.out.println("Song-Struktur: Zustandsdatei nicht ersetzbar: "
					+ target.getPath());
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
		// Auch bei einem manuellen /preset/load: sonst liefe die Verweildauer
		// des alten Levels weiter und der naechste faellige Wechsel
		// ueberschriebe den Eingriff womoeglich Sekunden spaeter - der Eingriff
		// waere sinnlos, ohne dass das jemand sehen koennte.
		if (director != null) {
			director.noteLoaded(name, nowMillis,
					(songParams == null) ? null : songParams.config());
		}
		System.out.println("Preset \"" + name + "\" geladen: " + report.summary());
		forwardToSound("/sc/preset/load", name);
		writeLastPresetName(name);
		return true;
	}

	boolean save(String name) {
		List<String[]> entries = PresetStore.snapshot(OscMessageDistributor.presetTargets());
		if (!store.write(name, entries)) {
			System.out.println("Preset speichern fehlgeschlagen: " + store.lastMessage());
			return false;
		}
		System.out.println("Preset \"" + name + "\" gespeichert: " + store.lastMessage());
		// Erst NACH dem erfolgreichen eigenen Schreiben: sonst legte ein
		// fehlgeschlagenes Licht-Preset trotzdem ein Klang-Preset an, und der
		// Name stuende danach nur auf einer der zwei Seiten.
		//
		// Ohne diese Zeile erfasste "Speichern" im Web-UI still nur das Licht
		// - beim spaeteren Laden kaeme die Szene optisch zurueck und klanglich
		// nicht, ohne Fehlermeldung.
		forwardToSound("/sc/preset/save", name);
		writeLastPresetName(name);
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
	//
	// Der Name ist auf beiden Seiten derselbe: "hang_drum_slow" meint
	// data/presets/hang_drum_slow.txt fuer das Licht UND
	// supercollider/presets/hang_drum_slow.txt fuer den Klang. Fehlt die
	// SC-Datei, bleibt der Klang stehen - kein Fehler, siehe ~presetLoad in
	// klangnetz_bells.scd.
	private void forwardToSound(String address, String name) {
		if (oscP5 == null || soundTarget == null) {
			return;
		}
		OscMessage message = new OscMessage(address);
		message.add(name);
		oscP5.send(message, soundTarget);
	}

	// Schritt 2a: den Namen als Boot-Fallback fuer den naechsten Prozess-
	// Neustart merken. Ein Schreibfehler darf die laufende Show nicht
	// stoeren - genau dieselbe Ueberlegung wie bei writeState() oben, deshalb
	// auch dieselbe "einmal melden, dann nicht mehr"-Regel.
	private void writeLastPresetName(String name) {
		if (lastPresetPath == null) {
			return;
		}
		if (!PresetStore.writeLastPresetName(lastPresetPath, name) && !lastPresetWriteFailed) {
			lastPresetWriteFailed = true;
			System.out.println("Letztes Preset nicht sicherbar (" + lastPresetPath
					+ ") - ein Neustart faellt auf Sketch-Argument/IMPULSE_PRESET zurueck");
		}
	}

	// Statisch UND unabhaengig von einer PresetManager-Instanz aufrufbar:
	// imPulse.pde braucht den Namen VOR dem Anlegen des PresetManager (siehe
	// loadBootPreset() weiter oben - der Aufruf steht dort bewusst NACH dem
	// Anlegen aller Effekte, der Boot-Fallback-Lesevorgang selbst hat aber
	// keine solche Abhaengigkeit und darf frueher passieren).
	static String lastPresetName(String path) {
		return PresetStore.readLastPresetName(path);
	}
}
