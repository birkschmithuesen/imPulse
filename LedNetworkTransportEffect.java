import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

import oscP5.*;
import netP5.*;

import processing.core.PApplet;
import processing.core.PVector;

///////////////////////////////////////////////////////////
// models a set of activations travelling along the stripes
///////////////////////////////////////////////////////////
public class LedNetworkTransportEffect implements runnableLedEffect, OscMessageSink {


  PApplet papplet;
  String name = "Impulse";
  String id;
  int numLeds, nStripes, nLedsInStripe;
  LedInNetInfo[] ledNetInfo;
  LedColor[] bufferLedColors;
  ArrayList <LedNetworkNode> nodes;
  double lastCyclePos=(double)System.currentTimeMillis()/1000;
  double lastRandomSpawnTime=(double)System.currentTimeMillis()/1000; // Zeitpunkt des letzten randomSpawn-Events

  // Die acht Stripe-Farben standen bis 2026-08-01 hier als Literal-Array
  // (stripeColorMapping) und waren weder per OSC noch im Web-UI erreichbar.
  // Sie sind jetzt 24 RemoteControlledFloatParameter, siehe stripeColorR/G/B
  // weiter unten; die Zahlen selbst liegen in StripeColorDefaults.

  LinkedList<TravellingActivation> activations = new LinkedList<TravellingActivation>();

  // Fortlaufende ID je Impuls, fuer den Positionsstrom /net/impulse. Steht in
  // der aeusseren Klasse, weil eine innere Klasse in Java 8 keine
  // nichtkonstanten statischen Felder haben darf.
  //
  // Ein Ueberlauf nach 2^31 Impulsen ist hingenommen: bei 1000 neuen Impulsen
  // je Sekunde nach etwa 25 Tagen Dauerbetrieb, und eine Kollision verwirrt
  // kurz eine Drohne auf der Klangseite.
  private int nextImpulseId = 0;

  //osc out
  OscP5 oscP5;
  NetAddress remoteLocation;

  LedPositionMap positionMap;
  final ImpulseOscThrottle impulseThrottle = new ImpulseOscThrottle();
  RemoteControlledFloatParameter impulseOscRate;      // Hz, 0 schaltet ab
  RemoteControlledIntParameter impulseOscMaxCount;    // Obergrenze je Takt

  // Sendetakt fuer /net/hitNode - die Nachricht, die auf der Klangseite
  // wirklich Synths erzeugt. Anders als impulseThrottle waehlt diese Drossel
  // nicht aus, sie STRECKT: jeder Treffer kommt, nur nicht alle in derselben
  // Millisekunde. Begruendung und Vorfall stehen in HitNodeOscThrottle.java.
  final HitNodeOscThrottle hitNodeThrottle = new HitNodeOscThrottle();
  RemoteControlledFloatParameter hitNodeOscRate;      // Hz, Notbremse
  // Stand des Verwurfszaehlers bei der letzten Meldung, und wann die war.
  // Am Deckel verworfene Treffer sind ausbleibende Toene und sollen nicht
  // still passieren - eine Meldung je Treffer waere bei Dauerueberlast
  // allerdings genau die Log-Flut, die dieser Fix verhindern will.
  private int lastReportedHitNodeDrops = 0;
  private double lastHitNodeDropReport = Double.NEGATIVE_INFINITY;
  private static final double DROP_REPORT_INTERVAL_S = 5.0;

  //settings
  RemoteControlledFloatParameter nodeDeadTime; // Time between two activations of a node
  // Energiezerfall pro Sekunde, also umgekehrt proportional zur Lebensdauer eines
  // Impulses: impulseEnergy -= lifetime*time. Trotz des Namens ist der Wert der
  // Zerfallsfaktor selbst, keine Sekundenangabe - klein = langlebig.
  RemoteControlledFloatParameter impulseLifetime;
  RemoteControlledIntParameter impulseEnergyExponent; // Exponent applied to input volume provided by /tube/trigger
  RemoteControlledIntParameter impulseSpeed; // speed (leds/second)

  // Streuung der Kindwerte bei einer Aufspaltung an einem Node. Ohne sie
  // bekommt jedes Kind exakt Speed und Lebensdauer des Elternimpulses, die
  // Geschwister sterben synchron und wirken identisch.
  //
  // Beide Auslieferungswerte 0 - das ist exakt das bisherige Verhalten, ein
  // Operator dreht sie bewusst hoch. Unabhaengig voneinander einstellbar.
  RemoteControlledFloatParameter splitSpeedJitter;
  RemoteControlledFloatParameter splitLifetimeJitter;

  // Wieviele der moeglichen Zweige eine Aufspaltung tatsaechlich nimmt, und
  // wie weit die gewaehlten zeitlich auseinander starten.
  //
  // Ein Gewicht je Kategorie aus SplitFanout, gleiche Reihenfolge:
  // alle / einer weniger / genau einer. Auslieferungswerte 100/0/0 - das ist
  // bitgleich das Verhalten von vor diesem Feature, und der Split-Layer laeuft
  // gerade live.
  RemoteControlledFloatParameter[] splitFanoutWeights =
      new RemoteControlledFloatParameter[SplitFanout.CATEGORY_COUNT];
  // Eigener Schalter statt "alle Gewichte 0 heisst aus": lauter Nullen sind
  // der entartete Fall der Ziehung und fallen auf Sechzehntel zurueck (siehe
  // SplitStagger.NEUTRAL_NOTE_INDEX), sind also gerade kein Aus. Ausserdem
  // behaelt ein Operator so seine eingestellte Verteilung, waehrend er den
  // Versatz zum Vergleich ab- und wieder anschaltet.
  RemoteControlledIntParameter splitStaggerEnabled;
  // 0 = jeder Zweig bekommt die volle Energie des Elternimpulses (bisheriges,
  // live gefahrenes Verhalten), 1 = anteilig UND halbiert geteilte Energie
  // (curActivation.energy/nActivations/2.0f). Ohne Teilung vervielfacht jede
  // Aufspaltung die Gesamtenergie im Netz - siehe activationEncounteredNode()
  // fuer die Formel und die Begruendung der Halbierung. Default 0: ein
  // Neustart mit diesem Stand darf das laufende Klangbild nicht ungefragt
  // aendern, dieselbe Regel wie bei splitFanoutWeights/splitStaggerEnabled.
  RemoteControlledIntParameter splitEnergyConservation;
  // Ein Gewicht je Klasse, Reihenfolge wie OriginSequencer.NOTE_VALUES
  // (Ganze .. Sechzehntel) und dahinter "gleichzeitig". Bis 2026-08-01 stand
  // hier ein einzelner fester Notenwert - er machte jeden Versatz im ganzen
  // Betrieb gleich lang. Gezogen wird je Aufspaltung, nicht je Zweig, damit
  // die Kinder eines Splits auf demselben Raster stehen (siehe
  // SplitStagger.pickNoteValue).
  RemoteControlledFloatParameter[] splitStaggerNoteWeights =
      new RemoteControlledFloatParameter[SplitStagger.CLASS_COUNT];
  // Wiederverwendet statt je Treffer neu angelegt - bei dichtem Betrieb
  // spalten mehrere Impulse je Frame auf.
  private final float[] fanoutScratch = new float[SplitFanout.CATEGORY_COUNT];
  private final float[] staggerScratch = new float[SplitStagger.CLASS_COUNT];
  private final ArrayList<PendingSpawn> splitCandidates = new ArrayList<PendingSpawn>();
  final SplitStagger splitStagger = new SplitStagger();

  // Rhythmisch quantisierte Spawn-Geschwindigkeit: die Geschwindigkeit eines
  // neuen Impulses ist ein Vielfaches von impulseSpeed (der 1x-Klasse), nach
  // Gewichten gezogen. Referenz ist impulseSpeed SELBST, kein eigener
  // Parameter - sonst gaebe es zwei Regler, die beide "die Geschwindigkeit"
  // heissen, und die Zeitbasis-Kopplung (lifetime, nodeDeadTime,
  // randomSpawn/interval haengen an impulseSpeed) haette einen zweiten,
  // unbeteiligten Bezugspunkt.
  //
  // enabled=0 im Auslieferungszustand: dann bekommt jeder Spawn exakt
  // impulseSpeed wie bisher, ohne Ziehung.
  RemoteControlledIntParameter speedQuantizeEnabled;
  RemoteControlledFloatParameter speedQuantizeJitter;
  // Ein Gewicht je Klasse aus SpeedQuantizer.MULTIPLIERS, gleiche Reihenfolge.
  RemoteControlledFloatParameter[] speedClassWeights =
      new RemoteControlledFloatParameter[SpeedQuantizer.MULTIPLIERS.length];
  // Wiederverwendet statt je Spawn neu angelegt - bei dichtem Betrieb spawnen
  // mehrere Impulse je Frame.
  private final float[] weightScratch = new float[SpeedQuantizer.MULTIPLIERS.length];

  // Optionaler Sinus-Randomizer je Parameter (Speed und Lifetime unabhaengig,
  // kein gemeinsamer Takt). Bei enabled=1 UEBERSCHREIBT der Oszillator den
  // manuell gesetzten Wert in jedem Frame:
  //   wert = min + (max-min) * (0.5 + 0.5*sin(2*PI*t/period))
  // t laeuft ab dem Einschalten, period ist die Dauer eines vollen Auf-Ab-
  // Zyklus in Sekunden (nicht Hz). Bei enabled=0 passiert gar nichts, der
  // Parameter bleibt wie bisher rein manuell steuerbar.
  //
  // Die min/max-Ranges sind bewusst dieselben wie die des jeweiligen
  // Zielparameters - deshalb kann der Oszillatorwert nicht ausserhalb von
  // dessen Bereich landen und braucht beim setValue() keine zweite Klemmung.
  RemoteControlledIntParameter speedRandomizeEnabled;
  RemoteControlledIntParameter speedRandomizeMin;
  RemoteControlledIntParameter speedRandomizeMax;
  RemoteControlledFloatParameter speedRandomizePeriod;
  RemoteControlledIntParameter lifetimeRandomizeEnabled;
  RemoteControlledFloatParameter lifetimeRandomizeMin;
  RemoteControlledFloatParameter lifetimeRandomizeMax;
  RemoteControlledFloatParameter lifetimeRandomizePeriod;
  final ParameterOscillator speedOscillator = new ParameterOscillator();
  final ParameterOscillator lifetimeOscillator = new ParameterOscillator();

  RemoteControlledFloatParameter impulseGamma= new RemoteControlledFloatParameter("/net/impulse/color/gamma", 0f, 0.1f, 5f);

  // 1 = jeder Impuls bekommt die EINE zentral gesetzte Farbe (impulseR/G/B),
  // 0 = er bekommt die Farbe seines Stripes aus den acht Slots darunter.
  // Hiess bis 2026-08-01 impulseUseRemoteCol an der Adresse
  // /net/impulse/color/useRemoteCol -- "remote" beschrieb, WOHER der Wert
  // kommt (per OSC), nicht was der Schalter tut. Beide Faelle kommen per OSC.
  RemoteControlledIntParameter impulseUseSpecificColor;
  RemoteControlledFloatParameter impulseR;
  RemoteControlledFloatParameter impulseG;
  RemoteControlledFloatParameter impulseB;

  // Die acht Farben des Gegenfalls ("Stripe-Farben"), Stripe -> Slot per
  // Modulo. Bis 2026-08-01 ein Literal-Array mitten in dieser Klasse und
  // damit weder per OSC noch im Web-UI erreichbar; die Startwerte kommen
  // jetzt aus StripeColorDefaults, der Rest ist ein ganz normaler Parameter.
  RemoteControlledFloatParameter[] stripeColorR =
      new RemoteControlledFloatParameter[StripeColorDefaults.COUNT];
  RemoteControlledFloatParameter[] stripeColorG =
      new RemoteControlledFloatParameter[StripeColorDefaults.COUNT];
  RemoteControlledFloatParameter[] stripeColorB =
      new RemoteControlledFloatParameter[StripeColorDefaults.COUNT];

  RemoteControlledFloatParameter fadeOutR;
  RemoteControlledFloatParameter fadeOutG;
  RemoteControlledFloatParameter fadeOutB;

  // ambient/idle-Verhalten: unabhaengig von /tube/trigger und Node-Kettenreaktionen spawnen
  // in regelmaessigen (oder verjitterten) Abstaenden zufaellige Impulse am Anfang zufaellig
  // gewaehlter Stripes - Geschwindigkeit kommt bewusst von impulseSpeed (kein eigener
  // Speed-Parameter), damit random gespawnte und tube-getriggerte Impulse gleich schnell wirken
  RemoteControlledIntParameter randomSpawnEnabled;      // /net/randomSpawn/enabled - 0/1, ganz abschaltbar ohne Neustart
  RemoteControlledIntParameter randomSpawnCount;        // /net/randomSpawn/count - Stripes pro Spawn-Event
  RemoteControlledFloatParameter randomSpawnInterval;   // /net/randomSpawn/interval - Sekunden zwischen Spawn-Events
  RemoteControlledFloatParameter randomSpawnEnergy;     // /net/randomSpawn/energy - Energie je gespawntem Impuls
  RemoteControlledFloatParameter randomSpawnDirectionBias; // /net/randomSpawn/directionBias - Wahrscheinlichkeit fuer "vorwaerts"
  RemoteControlledFloatParameter randomSpawnJitter;     // /net/randomSpawn/jitter - 0=exakt periodisch, 1=stark verjittert

  // Strukturierter Layer neben dem chaotischen randomSpawn: ein BPM-Takt und
  // sechs Tracks, die von wiederkehrenden Urspruengen spawnen. Beide Layer
  // laufen unabhaengig und sind gleichzeitig aktivierbar.
  //
  // Kein /net/sequencer/activeTracks: zwei Schalter fuer dieselbe Sache
  // erzeugen einen stillen Fehlerzustand (Operator schaltet Track 4 ein, es
  // passiert nichts, weil activeTracks=3 ihn abschneidet). enabled je Track
  // ist ausserdem ausdrucksstaerker - jede Teilmenge statt nur ein Praefix.
  // Der grobe Not-Aus ist /net/sequencer/enabled.
  RemoteControlledIntParameter sequencerEnabled;
  RemoteControlledFloatParameter sequencerBpm;
  RemoteControlledIntParameter[] trackEnabled = new RemoteControlledIntParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledIntParameter[] trackNoteValue = new RemoteControlledIntParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledIntParameter[] trackRepeatCount = new RemoteControlledIntParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledFloatParameter[] trackEnergy = new RemoteControlledFloatParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledFloatParameter[] trackSwingJitter = new RemoteControlledFloatParameter[OriginSequencer.TRACK_COUNT];
  RemoteControlledIntParameter[] trackOriginOverride = new RemoteControlledIntParameter[OriginSequencer.TRACK_COUNT];
  // 0 = kein Filter, 1..4 = nur Stripes des jeweiligen Baums (siehe
  // StripeTreeStore.TREE_NAMES). Wirkt nur, wenn originStripeOverride == -1;
  // ein ausdruecklich gesetzter Stripe hat Vorrang.
  RemoteControlledIntParameter[] trackOriginTree = new RemoteControlledIntParameter[OriginSequencer.TRACK_COUNT];
  // Die Baum-Zuordnung. Wird von aussen gesetzt (setStripeTrees), damit der
  // Effekt die Datei nicht selbst liest - dieselbe Trennung wie bei
  // LedPositionMap.
  StripeTreeStore stripeTrees;
  final MusicalClock musicalClock = new MusicalClock();
  OriginSequencer originSequencer;
  // Wiederverwendet statt in jedem Frame neu angelegt - drawMe() laeuft mit
  // 40 Hz, und ein Frame soll den Speicherbereiniger nicht beschaeftigen.
  private final TrackConfig[] trackConfigs = new TrackConfig[OriginSequencer.TRACK_COUNT];
  private final RandomSource mathRandom = new RandomSource() {
    public double next() {
      return Math.random();
    }
  };

  // Ruhemomente, orthogonal zu Sequencer und RandomSpawn: tickt auf
  // derselben Uhr (musicalClock) und blockiert bei Bedarf neue Spawns in
  // beiden Ebenen, laesst schon fliegende Impulse aber unbeeindruckt
  // weiterlaufen. Siehe PauseGate.java und /net/pause/* unten.
  final PauseGate pauseGate = new PauseGate();
  private final PauseGateConfig pauseGateConfig = PauseGateConfig.withDefaults();
  RemoteControlledIntParameter pauseEnabled;
  RemoteControlledFloatParameter pauseCheckIntervalBars;
  RemoteControlledFloatParameter pauseProbability;
  RemoteControlledFloatParameter pauseLengthMinBars;
  RemoteControlledFloatParameter pauseLengthMaxBars;
  RemoteControlledIntParameter pauseAffectsSequencer;
  RemoteControlledIntParameter pauseAffectsRandomSpawn;

  LedNetworkTransportEffect(String _id, int _numLeds, int _nStripes, int _nLedsInStripe,
      LedInNetInfo[] _ledNetInfo, ArrayList <LedNetworkNode> nodes_,
      LedPositionMap _positionMap, OscP5 _oscP5, NetAddress _remoteLocation) {
    id=_id;
    numLeds = _numLeds;
    nStripes = _nStripes;
    nLedsInStripe=_nLedsInStripe;
    bufferLedColors = LedColor.createColorArray(numLeds);
    ledNetInfo=_ledNetInfo;
    nodes=nodes_;
    oscP5=_oscP5;
    remoteLocation=_remoteLocation;
    positionMap=_positionMap;

    // 2026-07-30, Birk: Working-State-Defaults - Speed×10 langsamer als der
    // urspruengliche Auslieferungswert, alle davon abhaengigen Zeit-Parameter
    // proportional mitskaliert (siehe scripts/tune_speed.py in der Skill
    // devops/klangnetz-remote-control fuer die Herleitung). Bei Aenderung von
    // impulseSpeed IMMER auch diese drei mitziehen, sonst reissen die Impulse
    // (zu kurze Lebensdauer) oder das Netz verstopft (zu haeufige Kreuzungs-
    // Feuerung / Ambient-Spawns).
    nodeDeadTime= new RemoteControlledFloatParameter("/net/impulse/nodeDeadTime", 5f, 0.0f, 10);
    impulseLifetime= new RemoteControlledFloatParameter("/net/impulse/lifetime", 0.02f, 0.0001f, 1f);
    impulseSpeed= new RemoteControlledIntParameter("/net/impulse/speed", 16, 1, 1500);
    impulseEnergyExponent = new RemoteControlledIntParameter("/net/impulse/energyExponent", 2, 1, 10);
    splitSpeedJitter= new RemoteControlledFloatParameter("/net/impulse/splitSpeedJitter", 0f, 0f, 1f);
    splitLifetimeJitter= new RemoteControlledFloatParameter("/net/impulse/splitLifetimeJitter", 0f, 0f, 1f);

    // Auslieferungswerte: alles auf "alle Zweige", Versatz aus. Zusammen ist
    // das exakt das Verhalten von vor diesem Feature - ein Neustart mit
    // diesem Stand darf das Klangbild der laufenden Installation nicht
    // ungefragt aendern (Birk, 2026-08-01).
    //
    // Adressnamen: "oneLess" statt "n-1". Ein Minus in einer OSC-Adresse ist
    // erlaubt, liest sich in remoteSettings.txt und im Web-UI aber wie ein
    // Rechenzeichen statt wie ein Name.
    String[] fanoutNames = { "all", "oneLess", "single" };
    float[] fanoutDefaults = { 100f, 0f, 0f };
    for (int i=0; i<SplitFanout.CATEGORY_COUNT; i++) {
      splitFanoutWeights[i]= new RemoteControlledFloatParameter(
          "/net/impulse/split/weight/"+fanoutNames[i], fanoutDefaults[i], 0f, 100f);
    }
    splitStaggerEnabled= new RemoteControlledIntParameter("/net/impulse/split/staggerEnabled", 0, 0, 1);
    // Adresse und Default siehe Deklaration oben - 0 laesst die Berechnung
    // von childEnergy in activationEncounteredNode() bitgleich dem
    // bisherigen Verhalten.
    splitEnergyConservation= new RemoteControlledIntParameter("/net/impulse/split/energyConservation", 0, 0, 1);
    // Gewichte der Notenwert-Klassen, Schwerpunkt auf den kurzen: Sechzehntel
    // 60, Achtel 30, Viertel 10. Der Versatz soll knapp bleiben - ein halber
    // Takt zwischen zwei Zweigen liest sich nicht mehr als eine Aufspaltung,
    // sondern als zwei unabhaengige Impulse. Halbe und Ganze stehen deshalb
    // auf 0 und sind da, wenn jemand sie haben will.
    //
    // Bereich 0..100 wie bei den Fanout-Gewichten und den Speed-Klassen: eine
    // dritte Skala fuer dieselbe Sache waere ein Regler, dessen Zahl in der
    // Nachbarsektion etwas anderes bedeutet. Normalisiert wird ohnehin
    // (WeightedChoice), die Summe muss nicht 100 sein.
    //
    // Adressnamen ausgeschrieben statt "16": eine Zahl in der Adresse liest
    // sich in remoteSettings.txt wie eine Anzahl, nicht wie ein Notenwert.
    //
    // "simultaneous" ist die sechste Klasse und KEIN Notenwert: sie laesst
    // alle Zweige gleichzeitig starten, nicht nur den ersten (der startet
    // ohnehin immer sofort, siehe SplitStagger.delayBeats). Auslieferungswert
    // 0 - wer nichts verstellt, bekommt exakt das bisherige Verhalten; sie
    // ist da, wenn Birk sie hochzieht. Ihr Gewicht steht hinten, damit die
    // fuenf bestehenden Klassen ihre Indizes und damit ihre Adressen behalten.
    String[] staggerNoteNames = { "whole", "half", "quarter", "eighth", "sixteenth",
                                  "simultaneous" };
    float[] staggerNoteDefaults = { 0f, 0f, 10f, 30f, 60f, 0f };
    for (int i=0; i<SplitStagger.CLASS_COUNT; i++) {
      splitStaggerNoteWeights[i]= new RemoteControlledFloatParameter(
          "/net/impulse/split/stagger/weight/"+staggerNoteNames[i],
          staggerNoteDefaults[i], 0f, 100f);
    }

    // Randomizer: Auslieferungszustand aus (0), ein Operator schaltet ihn live
    // per OSC/Web-UI ein. Die Defaults spannen einen Bereich um den jeweiligen
    // Arbeitspunkt (speed 16, lifetime 0.02) auf.
    speedRandomizeEnabled= new RemoteControlledIntParameter("/net/impulse/speed/randomize/enabled", 0, 0, 1);
    speedRandomizeMin= new RemoteControlledIntParameter("/net/impulse/speed/randomize/min", 16, 1, 1500);
    speedRandomizeMax= new RemoteControlledIntParameter("/net/impulse/speed/randomize/max", 160, 1, 1500);
    speedRandomizePeriod= new RemoteControlledFloatParameter("/net/impulse/speed/randomize/period", 30f, 1f, 300f);
    lifetimeRandomizeEnabled= new RemoteControlledIntParameter("/net/impulse/lifetime/randomize/enabled", 0, 0, 1);
    lifetimeRandomizeMin= new RemoteControlledFloatParameter("/net/impulse/lifetime/randomize/min", 0.005f, 0.0001f, 1f);
    lifetimeRandomizeMax= new RemoteControlledFloatParameter("/net/impulse/lifetime/randomize/max", 0.05f, 0.0001f, 1f);
    lifetimeRandomizePeriod= new RemoteControlledFloatParameter("/net/impulse/lifetime/randomize/period", 20f, 1f, 300f);

    impulseUseSpecificColor = new RemoteControlledIntParameter("/net/impulse/color/useSpecificColor", 1, 0, 1);
    impulseR= new RemoteControlledFloatParameter("/net/impulse/color/r", 1, 0, 1); // color of travelling impulse
    impulseG= new RemoteControlledFloatParameter("/net/impulse/color/g", 1, 0, 1); // color of travelling impulse
    impulseB= new RemoteControlledFloatParameter("/net/impulse/color/b", 1, 0, 1); // color of travelling impulse

    // Die acht Stripe-Farben. Startwerte aus StripeColorDefaults, damit die
    // Installation ohne jedes Preset genauso aussieht wie vorher.
    for (int i=0; i<StripeColorDefaults.COUNT; i++) {
      String base="/net/impulse/stripeColor/"+i+"/";
      stripeColorR[i]= new RemoteControlledFloatParameter(base+"r", StripeColorDefaults.rgb(i, 0), 0, 1);
      stripeColorG[i]= new RemoteControlledFloatParameter(base+"g", StripeColorDefaults.rgb(i, 1), 0, 1);
      stripeColorB[i]= new RemoteControlledFloatParameter(base+"b", StripeColorDefaults.rgb(i, 2), 0, 1);
    }

    fadeOutR= new RemoteControlledFloatParameter("/net/impulse/fadeOut/r", 0.97f, 0f, 1f); // color of travelling impulse
    fadeOutG= new RemoteControlledFloatParameter("/net/impulse/fadeOut/g", 0.96f, 0f, 1f); // color of travelling impulse
    fadeOutB= new RemoteControlledFloatParameter("/net/impulse/fadeOut/b", 0.56f, 0f, 1f); // color of travelling impulse

    // Start-Default an (1) - Klangnetz ist eine nicht-interaktive Installation, Auto-Spawn
    // ist der Normalzustand und muss auch nach einem Processing-Neustart sofort laufen
    // (Birk, 2026-07-30). Ueber OSC weiterhin jederzeit live abschaltbar.
    randomSpawnEnabled= new RemoteControlledIntParameter("/net/randomSpawn/enabled", 1, 0, 1);
    randomSpawnCount= new RemoteControlledIntParameter("/net/randomSpawn/count", 1, 1, nStripes);
    randomSpawnInterval= new RemoteControlledFloatParameter("/net/randomSpawn/interval", 30f, 0.05f, 40f);
    randomSpawnEnergy= new RemoteControlledFloatParameter("/net/randomSpawn/energy", 0.6f, 0f, 1f);
    // 2026-07-30, Birk: directionBias=1 (immer vorwaerts vom Stripe-Anfang) -
    // bei 0.5 spawnten die Haelfte der Ambient-Impulse rueckwaerts vom
    // Stripe-ENDE, was optisch wie eine Aktivierung aus Knotenpunkten heraus
    // wirkte statt vom Stripe-Anfang. Range bis 1 belassen, da 1 = "immer
    // vorwaerts" die vom Nutzer gewuenschte Grenze ist.
    randomSpawnDirectionBias= new RemoteControlledFloatParameter("/net/randomSpawn/directionBias", 1f, 0f, 1f);
    randomSpawnJitter= new RemoteControlledFloatParameter("/net/randomSpawn/jitter", 0f, 0f, 1f);

    // Sequencer: global aus im Auslieferungszustand. Die Track-Defaults sind
    // nur der Zustand, den ein Operator vorfindet, wenn er ihn erstmals
    // einschaltet - deshalb zwei laufende Tracks (Ganze und Halbe, ruhig)
    // statt sechs.
    sequencerEnabled= new RemoteControlledIntParameter("/net/sequencer/enabled", 0, 0, 1);
    sequencerBpm= new RemoteControlledFloatParameter("/net/sequencer/bpm", 60f, 20f, 200f);
    originSequencer= new OriginSequencer(nStripes);
    // Ganze, Halbe, Viertel, Achtel, Viertel, Achtel - die ersten zwei an.
    int[] defaultNoteValues = { 1, 2, 4, 8, 4, 8 };
    int[] defaultEnabled = { 1, 1, 0, 0, 0, 0 };
    for (int i=0; i<OriginSequencer.TRACK_COUNT; i++) {
      String base="/net/sequencer/track"+i+"/";
      trackEnabled[i]= new RemoteControlledIntParameter(base+"enabled", defaultEnabled[i], 0, 1);
      // Range 1..16 statt einer Aufzaehlung - RemoteControlledIntParameter
      // kann keine. OriginSequencer.quantizeNoteValue() rastet beim Lesen auf
      // 1/2/4/8/16, ein Regler auf 5 verhaelt sich also wie 4.
      trackNoteValue[i]= new RemoteControlledIntParameter(base+"noteValue", defaultNoteValues[i], 1, 16);
      trackRepeatCount[i]= new RemoteControlledIntParameter(base+"repeatCount", 3, 1, 8);
      trackEnergy[i]= new RemoteControlledFloatParameter(base+"energy", 0.6f, 0f, 1f);
      trackSwingJitter[i]= new RemoteControlledFloatParameter(base+"swingJitter", 0f, 0f, 1f);
      // -1 = zufaelliger Ursprung (Normalfall), sonst fixer Stripe.
      trackOriginOverride[i]= new RemoteControlledIntParameter(base+"originStripeOverride", -1, -1, nStripes-1);
      trackOriginTree[i]= new RemoteControlledIntParameter(base+"originTreeFilter", 0, 0, StripeTreeStore.TREE_NAMES.length);
      trackConfigs[i]= new TrackConfig();
    }

    // Ruhemomente: global aus im Auslieferungszustand, wie Sequencer und
    // SongStructure - eine neue Ebene darf eine laufende Show nicht ohne
    // Zutun stumm schalten. checkIntervalBars/lengthMin/lengthMaxBars in
    // TAKTEN (nicht Beats), damit ein Operator in derselben Einheit denkt
    // wie beim Notenwert-Raster des Sequencers.
    pauseEnabled= new RemoteControlledIntParameter("/net/pause/enabled", 0, 0, 1);
    pauseCheckIntervalBars= new RemoteControlledFloatParameter("/net/pause/checkIntervalBars", 8f, 1f, 64f);
    pauseProbability= new RemoteControlledFloatParameter("/net/pause/probability", 0.25f, 0f, 1f);
    pauseLengthMinBars= new RemoteControlledFloatParameter("/net/pause/lengthMinBars", 2f, 0.5f, 32f);
    pauseLengthMaxBars= new RemoteControlledFloatParameter("/net/pause/lengthMaxBars", 6f, 0.5f, 32f);
    pauseAffectsSequencer= new RemoteControlledIntParameter("/net/pause/affectsSequencer", 1, 0, 1);
    pauseAffectsRandomSpawn= new RemoteControlledIntParameter("/net/pause/affectsRandomSpawn", 1, 0, 1);

    // Auslieferungswerte: aus. Die Gewichte darunter sind der Zustand, den
    // ein Operator vorfindet, wenn er einschaltet - 1x bleibt der weit
    // ueberwiegende Normalfall, ein 8x-Ausreisser ist etwa jeder hundertste.
    speedQuantizeEnabled= new RemoteControlledIntParameter("/net/impulse/speedQuantize/enabled", 0, 0, 1);
    speedQuantizeJitter= new RemoteControlledFloatParameter("/net/impulse/speedQuantize/jitter", 0f, 0f, 1f);
    // Adressnamen ohne Punkt: "0x5" statt "0.5x". Ein Punkt in einer
    // OSC-Adresse ist zwar erlaubt, aber remoteSettings.txt und das Web-UI
    // lesen Adressen als Text und der Punkt liest sich dort wie ein
    // Dezimaltrenner in einem Namen.
    String[] weightNames = { "0x5", "1x", "2x", "4x", "8x" };
    float[] weightDefaults = { 0f, 85f, 10f, 4f, 1f };
    for (int i=0; i<SpeedQuantizer.MULTIPLIERS.length; i++) {
      speedClassWeights[i]= new RemoteControlledFloatParameter(
          "/net/impulse/speedQuantize/weight/"+weightNames[i], weightDefaults[i], 0f, 100f);
    }

    // 0 schaltet den Strom ab - der Notausgang, wenn Netz oder Klangrechner
    // waehrend der Show nicht mitkommen. /net/hitNode laeuft davon unberuehrt
    // weiter.
    impulseOscRate = new RemoteControlledFloatParameter("/net/impulse/oscRate", 10f, 0f, 40f);
    impulseOscMaxCount = new RemoteControlledIntParameter("/net/impulse/oscMaxCount", 32, 0, 256);

    // Hoechste Rate, mit der Node-Treffer an die Klangseite gehen. Es ist eine
    // NOTBREMSE gegen den Extremfall, kein gestalterischer Regler: der
    // Auslieferungswert liegt bewusst so hoch, dass er im Normalbetrieb nie
    // greift.
    //
    // Die Rechnung dahinter: 92 Knoten, jeder durch /net/impulse/nodeDeadTime
    // (Auslieferungswert 5 s) auf hoechstens einen Treffer je 5 s begrenzt,
    // macht rund 18 Treffer/s. Aus einem Treffer werden bis zu drei gemeldete
    // Kinder (SplitFanout), also grob 55 Meldungen/s als obere Schranke bei
    // Auslieferungswerten - und das ist schon der theoretische Vollausbau, bei
    // dem jeder Knoten dauernd am Anschlag feuert. 100 Hz liegt darueber und
    // fuegt damit keine hoerbare Latenz hinzu.
    //
    // Nach unten offen bis 1 Hz, damit der Wert an der Installation auch
    // wirklich als Bremse taugt; die Untergrenze ist NICHT 0. Ein
    // /net/hitNode-Strom "aus" waere eine stumme Installation, kein Not-Aus -
    // anders als bei /net/impulse/oscRate, wo genau das der Sinn ist.
    hitNodeOscRate = new RemoteControlledFloatParameter("/net/hitNode/rateHz", 100f, 1f, 200f);

    OscMessageDistributor.registerAdress("/net/activateNode", this);
    OscMessageDistributor.registerAdress("/net/activateStripe", this);

    OscMessageDistributor.registerAdress("/tube/trigger", this);
  }

  public void digestMessage(OscMessage newMessage) {
    if (newMessage.checkAddrPattern("/net/activateNode") &&
      newMessage.arguments().length >0 &&
      newMessage.getTypetagAsBytes()[0]=='i'
      ) {
      int theValue=newMessage.get(0).intValue();
      if (theValue>0&&theValue<nodes.size()) {
        LedNetworkNode activeNode=nodes.get(theValue);
        int nLeds=ledNetInfo.length;
        // Einmal je Kommando gezogen, nicht je Richtung: die zwei Zweige
        // desselben Anstosses sollen zusammengehoeren.
        float cmdSpeed=spawnSpeed();
        // decayScale gehoert zur selben Ziehung wie cmdSpeed (siehe
        // spawnSpeed()/spawnDecayScale()) - Kettenreaktions-Fix, 2026-08-02.
        float cmdDecayScale=spawnDecayScale();
        for (Integer nodeLedIdx : activeNode.ledIndices) {
          LedInNetInfo curLedInfo=ledNetInfo[nodeLedIdx]; //which stripe are we on?
          int cmdTree=spawnTree(curLedInfo.stripeIndex);
          //  activation spreads in boths directions
          int forwPos=nodeLedIdx +1;
          if (forwPos>0&&forwPos<nLeds) {
			activations.add(new TravellingActivation(forwPos, curLedInfo.stripeIndex, cmdSpeed, 1f, cmdDecayScale, cmdTree));
		}
          //do not go back the same stripe:
          int backwPos=nodeLedIdx -1;
          if (backwPos>0&&backwPos<nLeds) {
			activations.add(new TravellingActivation(backwPos, curLedInfo.stripeIndex, -cmdSpeed, 1f, cmdDecayScale, cmdTree));
		}
        }
      }
    }
    if (newMessage.checkAddrPattern("/net/activateStripe") &&
      newMessage.arguments().length>0&&
      newMessage.getTypetagAsBytes()[0]=='i'
      ) {
      int theValue=newMessage.get(0).intValue();
      // decayScale gehoert zur selben Ziehung wie die Geschwindigkeit -
      // Kettenreaktions-Fix, 2026-08-02, siehe spawnSpeed()/spawnDecayScale().
      float stripeSpeed=spawnSpeed();
      activations.add(new TravellingActivation(theValue*nLedsInStripe, theValue, stripeSpeed, 1f, spawnDecayScale(), spawnTree(theValue)));
    }

    //System.out.println(newMessage);

    //receive a bang on one of the tubes
    if (newMessage.checkAddrPattern("/tube/trigger") && newMessage.arguments().length>0) {
      int theValue=newMessage.get(0).intValue()-1;
      float energy= 1f;
      if (newMessage.arguments().length > 1) {
        energy = newMessage.get(1).floatValue();
      }
      if (energy < 0) {
        energy = 0;
      }
      for (int i = 1; i < impulseEnergyExponent.getValue(); i++) {
        energy *= energy;
      }
      //System.out.println("Calculated Energy: "  + energy);
      //PApplet.println(theValue);
      if (theValue<nStripes) {
        // decayScale gehoert zur selben Ziehung wie die Geschwindigkeit -
        // Kettenreaktions-Fix, 2026-08-02, siehe spawnSpeed()/spawnDecayScale().
        float tubeSpeed=spawnSpeed();
        activations.add(new TravellingActivation(theValue*nLedsInStripe, theValue, tubeSpeed, energy, spawnDecayScale(), spawnTree(theValue)));
      }
    }
  }

  public void writeToStream(DataOutputStream outStream) {
    String outData="int"+"\t"+"/net/activateNode"+"\t"+"sactivateNode"+"\t"+0+"\t"+0+"\t"+(nodes.size()-1)+"\n"+"int"+"\t"+"/net/activateStripe"+"\t"+"activateStripe"+"\t"+0+"\t"+0+"\t"+(nStripes-1)+"\n";
    try {
      outStream.writeBytes(outData);
    }
    catch (
      IOException e) {
      System.err.println("Could not write to file"+e);
    }
  }

  //represents one travelling activation
  public class TravellingActivation {
    // Der Konstruktor OHNE originTree ist bewusst entfallen (2026-08-01): der
    // Ursprungs-Baum ist wie die id eine Eigenschaft, die der Impuls bei der
    // Geburt bekommt und bis zum Tod behaelt, und ein vergessener Wert waere
    // ein Impuls mit falscher Klangfarbe OHNE Fehlermeldung. Ohne den alten
    // Konstruktor weist der Compiler jede Konstruktionsstelle aus, die ihn
    // nicht setzt - dieselbe strukturelle Absicherung wie bei final int id.
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        int originTree_) {
      this(ledIdxPos_, stripeIdx_, speed_, energy_, nextImpulseId++, 1f, originTree_);
    }

    // Mit ausdruecklichem decayScale - fuer die Kinder einer Aufspaltung, die
    // ihre Lebensdauer streuen sollen (siehe /net/impulse/splitLifetimeJitter).
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        float decayScale_, int originTree_) {
      this(ledIdxPos_, stripeIdx_, speed_, energy_, nextImpulseId++, decayScale_,
          originTree_);
    }

    // Mit ausdruecklicher ID - nur fuer den Filler, der die ID seines
    // Elternimpulses uebernimmt statt eine neue zu verbrauchen.
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        int id_, float decayScale_, int originTree_) {
      ledIdxPos=ledIdxPos_;
      stripeIdx=stripeIdx_;
      speed=speed_;
      energy=energy_;
      id=id_;
      decayScale=decayScale_;
      originTree=originTree_;
    }

    int getLedIndex() {
      return (int)(ledIdxPos+0.5f); // global led position
    }
    float ledIdxPos; // absolute led position - used for mapping to led buffer
    int stripeIdx; // stripe the activation was created on
    float speed; // [leds/second] also encodes direction in sign
    float energy; // some measure of strength
    final int id; // fortlaufend, fuer /net/impulse
    // Faktor AUF den globalen impulseLifetime, nicht dessen Ersatz. 1.0 bei
    // jedem normalen Spawn, gestreut nur bei den Kindern einer Aufspaltung.
    //
    // Bewusst ein Multiplikator: mit einem absoluten Zerfallswert je Impuls
    // wuerde jeder Impuls den Wert seiner Geburt einfrieren. Dann erreichte
    // der Sinus-Randomizer (/net/impulse/lifetime/randomize/*) nur noch neu
    // gespawnte Impulse, und ein Operator, der den Lifetime-Regler zieht,
    // saehe die lebenden Impulse unbeeindruckt weiterlaufen - beides ohne
    // Fehlermeldung.
    final float decayScale;
    // Der Baum, an dem dieser Impuls entstanden ist: 0..3 nach
    // StripeTreeStore.TREE_NAMES, -1 = unbekannt. Wird an /net/hitNode
    // angehaengt und traegt dort den Klangbias.
    //
    // Unveraenderlich und vererbt, aus demselben Grund wie id und decayScale:
    // was einen Impuls als EIN Ereignis zusammenhaelt, ist sein Ursprung,
    // nicht die Region, in der er sich gerade befindet. Ein Bias, der beim
    // ersten Split verschwindet, waere lautlos falsch - deshalb uebernimmt
    // jedes Kind einer Aufspaltung und jeder Filler den Wert des
    // Elternimpulses.
    final int originTree;
    void setEnergy(float _energy){energy=_energy;}
  }

  //represents fillers needed when high travelling speeds lead to skipping some leds in each frame
  public class TravellingActivationFiller extends TravellingActivation {
    TravellingActivationFiller(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        int parentId_, float decayScale_, int originTree_) {
      super(ledIdxPos_, stripeIdx_, speed_, energy_, parentId_, decayScale_, originTree_);
    }
  }

  //simulate one time step
  public LedColor[] drawMe() {
    int useSpecificColor = impulseUseSpecificColor.getValue();
    float spotR=impulseR.getValue();
    float spotG=impulseG.getValue();
    float spotB=impulseB.getValue();
    float gamma =impulseGamma.getValue();

    //parameters
    double currentTime=(double)System.currentTimeMillis()/1000;
    float timeStep=(float) (currentTime-lastCyclePos);
    lastCyclePos=currentTime;
    // vor jeder Nutzung von impulseSpeed/impulseLifetime in diesem Frame -
    // spawnRandomImpulses() und tickSequencer() lesen impulseSpeed ueber
    // spawnSpeed()
    applyRandomizers(currentTime);
    // Gemeinsame Beat-Uhr fuer Sequencer UND Pause-Gate - vorgezogen aus
    // tickSequencer(), damit der Gate dieselbe Beat-Position sieht, bevor
    // beide Spawn-Ebenen fuer diesen Frame entscheiden.
    musicalClock.advance(currentTime, sequencerBpm.getValue());
    updatePauseGateConfig();
    pauseGate.tick(musicalClock.beats(), pauseGateConfig, mathRandom);

    spawnRandomImpulses(currentTime);
    // schreibt auch die MusicalClock fort, auf der der Split-Versatz laeuft -
    // deshalb muss releasePendingSplits() DAHINTER stehen, sonst arbeitete es
    // mit der Beat-Position des vorigen Frames.
    tickSequencer(currentTime);
    releasePendingSplits();

    //iterate through activations and build a new list of activations in the meanwhile.
    LinkedList<TravellingActivation> newActivations=new LinkedList<TravellingActivation>();

    for (TravellingActivation curActivation : activations) {
      int prevActivationLedIdx=curActivation.getLedIndex();
      // let each activation travel a bit in it's direction
      curActivation.ledIdxPos+=curActivation.speed*timeStep;
      // loose energy
      curActivation.energy -= timeStep*impulseLifetime.getValue()*curActivation.decayScale;
      // if the activation hasn't fallen off the end of the stripe...
      int activationLedIdx=curActivation.getLedIndex(); // global led position
      int direction;// needed to reuse loop for positive and negative speeds
      if (curActivation.speed > 0) {
        direction = 1;
      } else {
        direction = -1;
      }
      if (activationLedIdx != prevActivationLedIdx) {
        for (int curActivationLedIdx = prevActivationLedIdx+direction; curActivationLedIdx*direction < activationLedIdx*direction; curActivationLedIdx+=direction) {
          if ( !activationIsValid(activationLedIdx, curActivation)) {
            break;
          }
          if (activationEncounteredNode(curActivationLedIdx, curActivation, newActivations, currentTime)) {
            break;
          }
          LedInNetInfo curLedInfo=ledNetInfo[curActivationLedIdx];
          // Der Filler erbt id, decayScale UND originTree seines
          // Elternimpulses - er ist derselbe Impuls, nur an einer
          // uebersprungenen Position.
          newActivations.add(new TravellingActivationFiller(curActivationLedIdx, curLedInfo.stripeIndex, curActivation.speed, curActivation.energy, curActivation.id, curActivation.decayScale, curActivation.originTree));
        }
      }
      if (activationIsValid(activationLedIdx, curActivation) && (activationLedIdx == prevActivationLedIdx || !activationEncounteredNode(activationLedIdx, curActivation, newActivations, currentTime))) {
        newActivations.add(curActivation);
      }
    }

    activations=newActivations;

    //draw all
    LedColor.mult(bufferLedColors, new LedColor(fadeOutR.getValue(), fadeOutG.getValue(), fadeOutB.getValue()));
    ListIterator<TravellingActivation> iter = activations.listIterator();
    while (iter.hasNext()) {
      TravellingActivation curActivation = iter.next();
      int curLedIndex=curActivation.getLedIndex(); // global led position
      float fade=(float)Math.pow(curActivation.energy, gamma);
      // Die Kopffarbe wird EINMAL bestimmt und danach auf die zwei LEDs
      // verteilt, zwischen denen der Kopf gerade steht. Beide Farbquellen
      // gehen hindurch - die zentrale Impulsfarbe (useSpecificColor == 1)
      // genauso wie die Farbe des Stripes. Der Slot des Stripe-Falls gilt
      // fuer beide Ziel-LEDs, weil das Blending nie ueber den Stripe-Rand
      // hinaus schreibt (siehe blendHead()).
      float headR, headG, headB;
      if (useSpecificColor == 1) {
        headR=spotR*fade*curActivation.energy;
        headG=spotG*fade*curActivation.energy;
        headB=spotB*fade*curActivation.energy;
      } else {
        // Acht Slots, es gibt aber 30 Stripes - das Muster wiederholt sich
        int slot = ledNetInfo[curLedIndex].stripeIndex % StripeColorDefaults.COUNT;
        headR=stripeColorR[slot].getValue()*fade;
        headG=stripeColorG[slot].getValue()*fade;
        headB=stripeColorB[slot].getValue()*fade;
      }
      if (curActivation.getClass() == TravellingActivationFiller.class) {
        // Filler bleiben ausdruecklich beim harten Schreiben auf EINE LED.
        // Sie loesen das umgekehrte Problem - hohe Geschwindigkeit,
        // uebersprungene LEDs - und sitzen per Konstruktion auf ganzzahligen
        // Positionen, ein Bruchteil ist bei ihnen also immer 0. Sie hier
        // mitzublenden aenderte nichts am Bild und brauechte trotzdem eine
        // zweite Begruendung.
        bufferLedColors[curLedIndex].set(headR, headG, headB);
      } else {
        // Sub-Pixel-Blending des Impulskopfes.
        //
        // Problem: getLedIndex() rundet die float-Position auf die
        // naechstliegende LED. Bei kleinem /net/impulse/speed steht der
        // Impuls dadurch mehrere Frames auf derselben LED und springt dann
        // hart eine weiter - die Bewegung ruckelt sichtbar.
        //
        // Formel: der Kopf steht zwischen floor(ledIdxPos) und der LED
        // darueber und wird linear zwischen beide aufgeteilt.
        //
        //   lowerIdx = floor(ledIdxPos)
        //   frac     = ledIdxPos - lowerIdx        // 0..1
        //   lowerIdx bekommt Gewicht (1 - frac), lowerIdx+1 bekommt frac
        //
        // Bei frac == 0 - der Kopf sitzt exakt auf einer LED - bleibt das
        // volle Gewicht 1.0 auf lowerIdx: die Spitzenhelligkeit ist
        // unveraendert, es aendert sich ausschliesslich, wie der Kopf von
        // einer LED zur naechsten wandert.
        //
        // Das Vorzeichen von speed spielt hier bewusst KEINE Rolle: die zwei
        // Nachbarn klammern die Position von beiden Seiten ein, egal in
        // welche Richtung der Impuls laeuft. Welcher der beiden der
        // "vorauslaufende" ist, entscheidet allein frac, und beide gehen
        // durch dieselbe Randpruefung.
        int lowerIdx=(int)Math.floor(curActivation.ledIdxPos);
        float frac=curActivation.ledIdxPos-lowerIdx;
        blendHead(lowerIdx, curActivation.stripeIdx, 1f-frac, headR, headG, headB);
        blendHead(lowerIdx+1, curActivation.stripeIdx, frac, headR, headG, headB);
      }
      //if the travelling activation is a filler remove it
      if (curActivation.getClass() == TravellingActivationFiller.class) {
        iter.remove();
      } else if (curActivation.speed < 0 && curLedIndex <= (ledNetInfo[curLedIndex].stripeIndex*nLedsInStripe+27)) {
        iter.remove();
      }
    }
    // Erst die Knotentoene, dann der Positionsstrom - dieselbe Reihenfolge
    // wie vor der Drossel, als sendOscMessage() noch mitten in der Schleife
    // sendete. Auf der Klangseite kommt die Glocke damit weiterhin vor der
    // Drohne desselben Impulses.
    sendHitNodeStream(currentTime);
    sendImpulseStream(currentTime);
    return bufferLedColors;
  }

  // Traegt einen Anteil der Kopffarbe in eine LED ein - die eine Haelfte des
  // Sub-Pixel-Blendings aus drawMe(), dort steht auch die Formel.
  private void blendHead(int ledIdx, int stripeIdx, float weight, float r, float g, float b) {
    if (!(weight > 0f)) { // faengt auch NaN ab
      return;
    }
    // Der Nachbar darf weder aus dem globalen LED-Array laufen noch ueber den
    // Stripe-Rand in den naechsten Stripe: der globale Index ist dort zwar
    // fortlaufend, physisch liegt die LED aber an einer voellig anderen
    // Stelle im Netz, und der Kopf wuerde dort einen Punkt aufblitzen lassen,
    // an dem kein Impuls ist. Dieselbe Pruefung wie in activationIsValid(),
    // ohne dessen Energiebedingung - die Energie steckt beim Zeichnen schon
    // in der uebergebenen Farbe.
    if (ledIdx < 0 || ledIdx >= ledNetInfo.length) {
      return;
    }
    if (ledNetInfo[ledIdx].stripeIndex != stripeIdx) {
      return;
    }
    // Zusammengefuehrt wird je Kanal mit max(), nicht mit set(). Ein
    // Teilgewicht hart geschrieben wuerde den vorhandenen Pufferinhalt
    // UNTERschreiten: den nachleuchtenden Schweif, den LedColor.mult() weiter
    // oben in diesem Frame gedaempft hat, ebenso wie den Kopf eines zweiten
    // Impulses, der dieselbe LED anteilig trifft (vorher galt "letzter
    // gewinnt", der schwaechere Beitrag verschwand). Beides waere ein
    // Flackern genau dort, wo das Blending die Bewegung glaetten soll.
    //
    // max() ist ausserdem der Grund, warum der Kopf trotz der Aufteilung
    // nicht dunkler WIRKT, obwohl bei frac == 0.5 rechnerisch nur die Haelfte
    // auf jede der beiden LEDs faellt: die LED, die der Kopf gerade verlaesst,
    // haelt ihren Wert aus dem Vorframe, gedaempft nur um den
    // fadeOut-Faktor. Die vordere LED steigt an, die hintere klingt ab - das
    // ist genau der weiche Uebergang, um den es geht.
    LedColor target=bufferLedColors[ledIdx];
    float wr=r*weight;
    float wg=g*weight;
    float wb=b*weight;
    if (wr > target.x) {
      target.x = wr;
    }
    if (wg > target.y) {
      target.y = wg;
    }
    if (wb > target.z) {
      target.z = wb;
    }
  }

  private boolean activationIsValid(int activationLedIdx, TravellingActivation curActivation) {
    int nLeds=ledNetInfo.length;
    return
      activationLedIdx>=0&&activationLedIdx<=(nLeds-1)&& //ledIndex is valid
      ledNetInfo[activationLedIdx].stripeIndex==curActivation.stripeIdx&& // activation is in it's original stripe
      curActivation.energy>0;
  }

  private boolean activationEncounteredNode(Integer activationLedIdx, TravellingActivation curActivation, LinkedList<TravellingActivation> newActivations, double currentTime) {
    int nLeds=ledNetInfo.length;
    // should the activation survive this round?
    //if activation hits a stripe crossing, create a new activation for each of the branches
    if (ledNetInfo[activationLedIdx].partOfNode!=null) {
      LedNetworkNode hitNode=ledNetInfo[activationLedIdx].partOfNode;
      // only multiply at nodes that have not been active for a while
      if (currentTime-hitNode.lastActivationTime>nodeDeadTime.getValue()) {
        hitNode.lastActivationTime=currentTime;
        // Hier geht KEIN /net/hitNode mehr raus. Der Treffer selbst ist kein
        // Klangereignis mehr, sondern erst das Kind, das daraus entsteht -
        // gesendet wird deshalb in spawnSplitChildren() und
        // releasePendingSplits(), je einmal pro tatsaechlich gestartetem Kind.
        // Begruendung siehe sendOscMessage().
        float nActivations=hitNode.ledIndices.size();
        // Energieerhaltung am Knoten (Kettenreaktions-Fix, 2026-08-02):
        // standardmaessig AUS (/net/impulse/split/energyConservation, Default
        // 0) - dann bekommt jeder Zweig die VOLLE Energie des Elternimpulses,
        // das bisherige, live gefahrene Verhalten. Bei 1 wird die Energie
        // anteilig UND zusaetzlich halbiert geteilt
        // (curActivation.energy/nActivations/2.0f) - ohne das vervielfacht
        // jede Aufspaltung die Gesamtenergie im Netz, mit hoher Speed (und
        // dadurch vielen Kreuzungstreffern) verschaerft das die
        // Kettenreaktion zusaetzlich zum decayScale-Problem oben. Eigener
        // Schalter statt fest umzustellen: Birk faehrt aktuell das
        // Vollenergie-Verhalten live, ein Neustart mit diesem Stand darf das
        // Klangbild nicht ungefragt aendern (dieselbe Regel wie bei
        // splitFanoutWeights/splitStaggerEnabled oben).
        float childEnergy = (splitEnergyConservation.getValue() == 1)
            ? curActivation.energy/nActivations/2.0f
            : curActivation.energy;
        // Streuung je Kind, siehe /net/impulse/splitSpeedJitter und
        // /net/impulse/splitLifetimeJitter. Bei beiden Auslieferungswerten 0
        // liefert SplitVariance.jitter() den Ausgangswert unveraendert, das
        // Verhalten ist dann bitgleich dem vorherigen.
        //
        // Gezogen wird JE ZWEIG und je Groesse einzeln - ein gemeinsamer
        // Zufallswert fuer alle Zweige eines Treffers wuerde sie wieder
        // gleichschalten, also genau das nicht loesen, worum es geht.
        float speedJitter=splitSpeedJitter.getValue();
        float lifetimeJitter=splitLifetimeJitter.getValue();

        // Erst SAMMELN, dann auswaehlen. Bis zu diesem Feature spawnte jeder
        // moegliche Zweig sofort; jetzt entscheidet erst die Gewichtstabelle,
        // wieviele es werden. Die Bedingungen darunter - Bounds und "nicht
        // denselben Stripe zurueck" - sind unveraendert: sie sagen, welcher
        // Zweig ueberhaupt MOEGLICH ist, und das ist eine andere Frage als
        // welcher genommen wird.
        splitCandidates.clear();
        for (Integer nodeLedIdx : hitNode.ledIndices) {
          LedInNetInfo curLedInfo=ledNetInfo[nodeLedIdx]; //which stripe are we on?

          int jump; // jump one led to avoid activating the same node over and over again
          if (curActivation.speed>0) {
            jump=1;
          } else {
            jump=-1;
          }
          //  activation spreads in boths directions
          int forwPos=nodeLedIdx +jump;
          if (forwPos>0&&forwPos<nLeds) {
            splitCandidates.add(candidate(forwPos, curLedInfo.stripeIndex,
                SplitVariance.jitter(curActivation.speed, speedJitter, Math.random()),
                childEnergy,
                // Jitter-BASIS ist der decayScale des Elternimpulses, nicht
                // mehr 1f (Kettenreaktions-Fix, 2026-08-02): sonst faellt ein
                // schnelles Kind (hohe geerbte Speed, siehe
                // TravellingActivation.speed) beim Splitten auf den normalen
                // Zerfall zurueck und legt dadurch noch mehr Strecke zurueck
                // als sein Elternimpuls - jede Generation eskaliert weiter.
                // clampDecayScale() deckelt das Aufschaukeln ueber mehrere
                // Split-Generationen, siehe SplitVariance.MAX_DECAY_SCALE.
                SplitVariance.clampDecayScale(
                    SplitVariance.jitter(curActivation.decayScale, lifetimeJitter, Math.random())),
                curActivation.originTree, hitNode));
          }
          //do not go back the same stripe:
          if (ledNetInfo[nodeLedIdx].stripeIndex!=ledNetInfo[activationLedIdx].stripeIndex || activationLedIdx < nodeLedIdx) {//ledNetInfo[nodeLedIdx].stripeIndex!=ledNetInfo[activationLedIdx].stripeIndex) {
            int backwPos=nodeLedIdx -jump;
            if (backwPos>0&&backwPos<nLeds) {
              splitCandidates.add(candidate(backwPos, curLedInfo.stripeIndex,
                  SplitVariance.jitter(-curActivation.speed, speedJitter, Math.random()),
                  childEnergy,
                  SplitVariance.clampDecayScale(
                      SplitVariance.jitter(curActivation.decayScale, lifetimeJitter, Math.random())),
                  curActivation.originTree, hitNode));
            }
          }
        }
        spawnSplitChildren(newActivations);
        return true;
      }
    }
    return false;
  }


  // Ein moeglicher Zweig, noch ohne Entscheidung darueber, ob und wann er
  // laeuft. PendingSpawn statt TravellingActivation, weil ein Kandidat, der
  // nicht genommen wird, keine Impuls-ID verbrauchen soll: die IDs sind der
  // Schluessel des Positionsstroms /net/impulse, und eine Luecke darin waere
  // auf der Klangseite eine Drohne, die nie kommt.
  // Der getroffene Knoten wird MITGENOMMEN, nicht nur an die sofort startenden
  // Kinder weitergereicht: ein zeitversetztes Kind meldet sein /net/hitNode
  // erst beim Start, und bis dahin ist der Treffer laengst aus dem Aufrufweg
  // heraus. Uebernommen werden id und Position als Werte, nicht die Referenz -
  // Begruendung am Feld in SplitStagger.java.
  private PendingSpawn candidate(float ledPos, int stripeIdx, float speed,
      float energy, float decayScale, int originTree, LedNetworkNode hitNode) {
    PendingSpawn p = new PendingSpawn();
    p.ledPos=ledPos;
    p.stripeIdx=stripeIdx;
    p.speed=speed;
    p.energy=energy;
    p.decayScale=decayScale;
    p.originTree=originTree;
    p.nodeId=hitNode.id;
    p.nodePosX=hitNode.posX;
    p.nodePosY=hitNode.posY;
    return p;
  }

  // Zieht aus den gesammelten Kandidaten die Zahl der Zweige und startet sie -
  // den ersten sofort, die weiteren um je einen Notenwert versetzt.
  //
  // Der Versatz zaehlt in Beats derselben MusicalClock, auf der auch der
  // Origin-Sequencer laeuft. Die Uhr laeuft unabhaengig von
  // /net/sequencer/enabled weiter (siehe tickSequencer), der Versatz braucht
  // den Sequencer also nicht - er teilt nur seine Phase.
  private void spawnSplitChildren(LinkedList<TravellingActivation> newActivations) {
    int candidates=splitCandidates.size();
    if (candidates == 0) {
      return;
    }
    for (int i=0; i<splitFanoutWeights.length; i++) {
      fanoutScratch[i]=splitFanoutWeights[i].getValue();
    }
    int take=SplitFanout.branchCount(fanoutScratch, candidates, Math.random());
    // Die Reihenfolge ist gemischt: sie bestimmt, welcher Zweig sofort
    // startet. Der erste Kandidat waere sonst immer der mit dem kleinsten
    // LED-Index - ein Vorrang, den kein Regler zeigt.
    int[] order=SplitFanout.chooseOrder(candidates, take, mathRandom);
    boolean stagger=splitStaggerEnabled.getValue() == 1;
    // EINE Ziehung fuer die ganze Aufspaltung, nicht eine je Kind: alle
    // Zweige stehen damit auf demselben Raster. Je Kind gezogen waeren schon
    // die Abstaende innerhalb eines Splits ungleich - genau die
    // Gleichmaessigkeit, an der ein Rhythmus zu erkennen ist. Was von
    // Aufspaltung zu Aufspaltung wechseln soll, ist die Klasse selbst.
    // Bei ausgeschaltetem Versatz wird gar nicht gezogen: der Wert geht dann
    // in keine Rechnung ein (delay bleibt 0), und ein Math.random() je
    // Aufspaltung waere Arbeit fuer nichts. 16 steht hier als gueltiger
    // Notenwert, nicht als Bedeutung.
    int noteValue=16;
    if (stagger) {
      for (int i=0; i<splitStaggerNoteWeights.length; i++) {
        staggerScratch[i]=splitStaggerNoteWeights[i].getValue();
      }
      noteValue=SplitStagger.pickNoteValue(staggerScratch, Math.random());
    }
    double beats=musicalClock.beats();
    for (int slot=0; slot<order.length; slot++) {
      PendingSpawn p=splitCandidates.get(order[slot]);
      double delay=stagger ? SplitStagger.delayBeats(noteValue, slot) : 0.0;
      if (delay <= 0.0) {
        newActivations.add(new TravellingActivation(p.ledPos, p.stripeIdx, p.speed,
            p.energy, p.decayScale, p.originTree));
        // Ein Kind, ein Klangereignis. Gemeldet wird hier und nicht oben beim
        // Treffer, weil erst an dieser Stelle feststeht, wieviele Kinder es
        // wirklich werden.
        sendOscMessage(p);
      } else {
        p.dueBeats=beats + delay;
        splitStagger.schedule(p);
      }
    }
  }

  // Die faelligen Kinder aus der Warteschlange starten lassen. Laeuft
  // unabhaengig von splitStaggerEnabled: schaltet ein Operator den Versatz
  // mitten in der Show ab, sollen die schon geplanten Kinder trotzdem noch
  // kommen statt in der Schlange zu verhungern.
  private void releasePendingSplits() {
    List<PendingSpawn> due=splitStagger.due(musicalClock.beats());
    for (int i=0; i<due.size(); i++) {
      PendingSpawn p=due.get(i);
      activations.add(new TravellingActivation(p.ledPos, p.stripeIdx, p.speed,
          p.energy, p.decayScale, p.originTree));
      // Erst JETZT, nicht beim Einreihen: der Klang soll auf dem Beat sitzen,
      // auf dem das Kind losfaehrt. Beim Scheduling gesendet klaenge die
      // ganze Aufspaltung wieder als ein einziger Schlag - genau das, was der
      // Versatz aufloesen soll.
      sendOscMessage(p);
    }
  }

  // Ein /net/hitNode je tatsaechlich gestartetem Kind-Impuls, NICHT je
  // Treffer-Ereignis.
  //
  // Bis 2026-08-02 stand der Aufruf in activationEncounteredNode(), also
  // einmal pro Node-Treffer - eine Stelle aus der Zeit, als eine Aufspaltung
  // noch immer alle moeglichen Zweige im selben Frame nahm. Seit SplitFanout
  // und SplitStagger werden aus einem Treffer ein, zwei oder mehr Kinder, die
  // ausserdem zeitversetzt starten koennen; gemeldet wurde trotzdem genau
  // eines. Symptom: nur der erste Impuls einer Aufspaltung erzeugte Klang,
  // jeder weitere lief stumm durchs Netz.
  //
  // Der Parameter ist der PendingSpawn und nicht die fertige
  // TravellingActivation: die entsteht am Ort des sofortigen Spawns erst
  // danach, und beim zeitversetzten Kind traegt ohnehin nur der PendingSpawn
  // den Knoten, an dem es entstanden ist. Ein Kandidat, der nicht genommen
  // wird, kommt hier nie an und meldet also auch nichts.
  //
  // Folge fuer den seltenen Fall ohne einen einzigen moeglichen Zweig (Knoten
  // am aeussersten LED-Index): der Treffer bleibt jetzt stumm, wo er vorher
  // eine Glocke ausloeste. Das ist die Zusage dieses Verhaltens - kein
  // Impuls, kein Ton -, nicht ein uebersehener Fall.
  //
  // Gesendet wird hier NICHT sofort, sondern eingereiht: das Datagramm geht in
  // sendHitNodeStream() raus, sobald /net/hitNode/rateHz es erlaubt. Im
  // Normalbetrieb ist das derselbe Frame - die Drossel ist eine Notbremse
  // gegen Bursts, siehe HitNodeOscThrottle.java.
  private void sendOscMessage(PendingSpawn child) {
    hitNodeThrottle.enqueue(child);
  }

  // Die faelligen Treffer aus der Warteschlange rausschicken.
  //
  // Steht wie sendImpulseStream() am Ende von drawMe() und nicht am Anfang:
  // die Treffer dieses Frames entstehen in der Zeichenschleife darueber, und
  // ein Treffer soll in dem Frame rausgehen, in dem er entstanden ist, statt
  // einen Frame lang zu warten.
  private void sendHitNodeStream(double currentTime) {
    List<PendingSpawn> due=hitNodeThrottle.due(currentTime, hitNodeOscRate.getValue());
    for (int i=0; i<due.size(); i++) {
      emitHitNode(due.get(i));
    }
    // Am Deckel verworfene Treffer sind Toene, die ausbleiben. Sie zu melden
    // ist der Unterschied zwischen einem bekannten Kompromiss und einem
    // stillen Fehler - hoechstens alle DROP_REPORT_INTERVAL_S Sekunden, weil
    // eine Meldung je Treffer bei Dauerueberlast dieselbe Flut im Log waere,
    // die auf der Klangseite gerade behoben wird.
    int drops=hitNodeThrottle.droppedCount();
    if (drops > lastReportedHitNodeDrops
        && currentTime-lastHitNodeDropReport >= DROP_REPORT_INTERVAL_S) {
      System.out.println("WARNUNG: /net/hitNode ueberlastet - "
          +(drops-lastReportedHitNodeDrops)+" Treffer verworfen ("+drops
          +" insgesamt). /net/hitNode/rateHz steht auf "
          +hitNodeOscRate.getValue()+" Hz.");
      lastReportedHitNodeDrops=drops;
      lastHitNodeDropReport=currentTime;
    }
  }

  // Das eigentliche Datagramm. Getrennt von sendOscMessage(), damit die
  // Entscheidung "wann" und der Aufbau "was" nicht in derselben Methode
  // stehen - der Aufbau ist unveraendert.
  private void emitHitNode(PendingSpawn child) {
    OscMessage myMessage = new OscMessage("/net/hitNode");
    myMessage.add(child.nodeId);
    myMessage.add(child.energy);
    // Draufsicht-Position des Knotens in Metern, Ursprung Netzmitte. Kein z -
    // das Netz haengt ueber Kopf, vier Lautsprecher in einer Ebene koennen die
    // Hoehe nicht darstellen.
    //
    // Rueckwaertskompatibel: ein Klang-Sketch, der nur msg[1] und msg[2]
    // liest, ignoriert die zwei zusaetzlichen Argumente.
    myMessage.add(child.nodePosX);
    myMessage.add(child.nodePosY);
    // Der Ursprungs-Baum des Impulses (0..3, -1 = unbekannt), rein
    // ANGEHAENGT - genau dasselbe Muster, mit dem /net/hitNode schon um x/y
    // und /net/impulse um speed erweitert wurde: ein Empfaenger, der nur die
    // ersten vier Argumente liest, bleibt unberuehrt.
    //
    // Er ist eine Eigenschaft des IMPULSES, nicht des getroffenen Knotens -
    // deshalb kommt er vom Kind und nicht vom Knoten. Genau darin
    // liegt der Sinn: er ist fuer einen gegebenen Impuls konstant,
    // transponiert also seinen ganzen Weg gleichmaessig und laesst jedes
    // Intervall darauf unangetastet. Ein Bias nach der Region des Knotens
    // wuerde die topologisch gesetzten Intervalle an jeder Quadrantengrenze
    // wieder zerlegen.
    myMessage.add(child.originTree);
    oscP5.send(myMessage, remoteLocation);
  }

  // Gedrosselter Positionsstrom der reisenden Impulse:
  //   /net/impulse <id:int> <x:float> <y:float> <energy:float> <speed:float>
  // Das fuenfte Argument ist der BETRAG der Geschwindigkeit in LEDs/Sekunde
  // und kam spaeter dazu - rein angehaengt, siehe unten.
  //
  // Ueberspringt Filler explizit (gleiche Klassenpruefung wie an ihrer
  // Entfernungsstelle in der Zeichenschleife) - ein Elternimpuls und seine
  // Filler tragen im selben Frame dieselbe id, ein Filler im Strom saehe
  // fuer die Klangseite wie ein einziger, zwischen mehreren Positionen
  // hin- und herspringender Impuls aus. Die Invariante haengt damit nicht
  // mehr allein an der Aufrufreihenfolge.
  //
  // Muss trotzdem NACH der Zeichenschleife gerufen werden: erst dort stehen
  // die Positionen der echten Impulse fuer diesen Frame fest.
  //
  // Kein Todes-Signal: der Strom ist durch oscMaxCount ohnehin lueckenhaft -
  // ein Impuls kann aus der Auswahl fallen, ohne zu sterben. Die Klangseite
  // muss mit stillem Verschwinden umgehen koennen, und dann deckt ihr Timeout
  // auch den echten Tod ab.
  private void sendImpulseStream(double currentTime) {
    if (!impulseThrottle.due(currentTime, impulseOscRate.getValue())) {
      return;
    }
    int n = activations.size();
    if (n == 0) {
      return;
    }
    float[] energies = new float[n];
    TravellingActivation[] flat = new TravellingActivation[n];
    int k = 0;
    for (TravellingActivation a : activations) {
      if (a.getClass() == TravellingActivationFiller.class) {
        continue;
      }
      flat[k] = a;
      energies[k] = a.energy;
      k++;
    }
    if (k == 0) {
      return;
    }
    int[] chosen = impulseThrottle.select(Arrays.copyOf(energies, k), impulseOscMaxCount.getValue());
    for (int i = 0; i < chosen.length; i++) {
      TravellingActivation a = flat[chosen[i]];
      int ledIndex = a.getLedIndex();
      OscMessage myMessage = new OscMessage("/net/impulse");
      myMessage.add(a.id);
      myMessage.add(positionMap.x(ledIndex));
      myMessage.add(positionMap.y(ledIndex));
      myMessage.add(a.energy);
      // Betrag der Geschwindigkeit in LEDs/Sekunde, rein ANGEHAENGT - genau
      // das Muster, mit dem /net/hitNode schon um x/y erweitert wurde: ein
      // Empfaenger, der nur die ersten vier Argumente liest, bleibt unberuehrt.
      //
      // Das Vorzeichen traegt die Richtung und ist fuer die Klangfarbe
      // bedeutungslos, deshalb der Betrag. Die Klangseite koppelt daran die
      // Filterfrequenz des Travel-Sounds (schneller = schaerfer).
      myMessage.add(Math.abs(a.speed));
      oscP5.send(myMessage, remoteLocation);
    }
  }

  // Sinus-Randomizer fuer Speed und Lifetime, siehe /net/impulse/*/randomize/*
  // in CLAUDE.md und ParameterOscillator.
  //
  // Gesetzt wird bewusst der zugrundeliegende Parameter selbst, nicht ein
  // Schattenwert nur fuer diesen Frame: nur so steht der gerade gefahrene Wert
  // auch in remoteSettings.txt und in einem gespeicherten Preset. Ein manuelles
  // Nachjustieren waehrend enabled=1 wird dadurch im naechsten Frame wieder
  // ueberschrieben - gewollt.
  private void applyRandomizers(double currentTime) {
    if (speedRandomizeEnabled.getValue() == 1) {
      float value=speedOscillator.value(currentTime, speedRandomizePeriod.getValue(),
          speedRandomizeMin.getValue(), speedRandomizeMax.getValue());
      impulseSpeed.setValue(Math.round(value)); // runden, nicht abschneiden
    } else {
      speedOscillator.reset(); // Wiedereinschalten faengt einen frischen Zyklus an
    }
    if (lifetimeRandomizeEnabled.getValue() == 1) {
      impulseLifetime.setValue(lifetimeOscillator.value(currentTime,
          lifetimeRandomizePeriod.getValue(), lifetimeRandomizeMin.getValue(),
          lifetimeRandomizeMax.getValue()));
    } else {
      lifetimeOscillator.reset();
    }
  }

  // ambient/idle-Spawns: unabhaengig von /tube/trigger und Node-Kettenreaktionen, siehe
  // /net/randomSpawn/* in CLAUDE.md. papplet ist hier ungenutzt/null (siehe Konventionen),
  // also Math.random() statt papplet.random() fuer den Zufall.
  private void spawnRandomImpulses(double currentTime) {
    if (randomSpawnEnabled.getValue() != 1) {
      return;
    }
    if (pauseGate.blocksRandomSpawn()) {
      // Ruhemoment: der Intervall-Timer laeuft bewusst NICHT weiter (kein
      // lastRandomSpawnTime-Update) - sonst waere direkt nach Pausenende ein
      // Intervall bereits verstrichen und es kaeme sofort ein Spawn, statt
      // dass der normale Rhythmus einfach fortgesetzt wird. Dieselbe
      // Zurueckhaltung wie tickSequencer() bei blockiertem Sequencer.
      return;
    }
    float jitter=randomSpawnJitter.getValue();
    float jitterFactor=1f + jitter*(float) (Math.random()*2.0 - 1.0); // 0 => exakt periodisch, 1 => 0..2x interval
    float effectiveInterval=Math.max(randomSpawnInterval.getValue()*jitterFactor, 0.02f); // Mindestabstand gegen 0/negative Intervalle durch Jitter
    if (currentTime-lastRandomSpawnTime < effectiveInterval) {
      return;
    }
    lastRandomSpawnTime=currentTime;

    int count=randomSpawnCount.getValue(); // bereits durch min/max des Parameters auf 1..nStripes begrenzt
    float energy=randomSpawnEnergy.getValue();
    float directionBias=randomSpawnDirectionBias.getValue();

    for (int stripeIdx : pickDistinctStripes(count)) {
      boolean forward=Math.random() < directionBias;
      // Je Impuls eine eigene Klasse (spawnSpeed): bei count > 1 soll nicht
      // der ganze Schwung gleich schnell sein. Grundwert bleibt impulseSpeed,
      // bewusst kein eigener Speed-Parameter fuer den Ambient-Layer.
      float speed=spawnSpeed();
      // decayScale gehoert zur selben Ziehung wie speed - Kettenreaktions-Fix,
      // 2026-08-02, siehe spawnSpeed()/spawnDecayScale().
      float decayScale=spawnDecayScale();
      // "rueckwaerts" beginnt am anderen Ende des Stripes, sonst wuerde der Impuls sofort
      // wieder aus den Bounds fallen (siehe activationIsValid) statt eine sichtbare Strecke zu reisen
      float startPos=forward ? stripeIdx*nLedsInStripe : stripeIdx*nLedsInStripe + (nLedsInStripe-1);
      activations.add(new TravellingActivation(startPos, stripeIdx, forward ? speed : -speed, energy, decayScale, spawnTree(stripeIdx)));
    }
  }

  // Geschwindigkeit fuer EINEN neu gespawnten Impuls, immer als positiver
  // Betrag - die Richtung setzt der Aufrufer ueber das Vorzeichen.
  //
  // Der einzige Ort, an dem eine Spawn-Geschwindigkeit entsteht: alle fuenf
  // Spawn-Pfade (Tube-Trigger, activateStripe, activateNode, RandomSpawn,
  // Sequencer) gehen hierdurch. Split-Kinder NICHT - die erben die (schon
  // vervielfachte) Geschwindigkeit ihres Elternimpulses und bekommen
  // obendrauf splitSpeedJitter, siehe activationEncounteredNode().
  //
  // Legt nebenbei lastSpawnDecayScale fest (Kettenreaktions-Fix, 2026-08-02):
  // die gezogene Speed-KLASSE (nicht nur der Multiplikator auf impulseSpeed)
  // bestimmt auch den decayScale des gespawnten Impulses, siehe
  // spawnDecayScale(). Beides muss aus DERSELBEN Ziehung kommen - ein
  // zweiter, unabhaengiger Aufruf von SpeedQuantizer.pick() wuerde eine
  // andere Klasse treffen und Geschwindigkeit und Zerfall auseinanderlaufen
  // lassen, ohne dass sich das an einer der beiden Zahlen zeigt.
  private float lastSpawnDecayScale = 1f;

  private float spawnSpeed() {
    float base=impulseSpeed.getValue();
    if (speedQuantizeEnabled.getValue() != 1) {
      // Ohne Speed-Klassen bleibt auch der Zerfall unveraendert: 1.0 ist der
      // Ausgangswert, auf den impulseLifetime allein wirkt.
      lastSpawnDecayScale=1f;
      return base;
    }
    // base bleibt impulseSpeed - die Klassen sind ein VIELFACHES des
    // Grundreglers, kein eigener Arbeitspunkt. Bis 2026-08-02 stand hier eine
    // zweite Quelle (/net/impulse/speedQuantize/baseSpeed, Bereich 0.1..2.0),
    // die den Grundregler (Bereich 1..1500) bei eingeschalteten Klassen
    // komplett verwarf: das Grundtempo war dann unbedienbar und die
    // Zeitbasis-Kopplung (lifetime, nodeDeadTime, randomSpawn/interval haengen
    // an impulseSpeed) bezog sich auf einen Wert, den gar niemand fuhr.
    for (int i=0; i<speedClassWeights.length; i++) {
      weightScratch[i]=speedClassWeights[i].getValue();
    }
    int cls=SpeedQuantizer.pick(weightScratch, Math.random());
    float speed=base*SpeedQuantizer.multiplierAt(cls);
    // decayScale = derselbe Multiplikator M wie bei der Geschwindigkeit
    // (SpeedQuantizer.decayScaleFor == multiplierAt): ein M-fach schneller
    // Impuls zerfaellt M-mal schneller, die zurueckgelegte Grunddistanz bis
    // zum Energie-Aus bleibt ueber alle Speed-Klassen gleich. Ohne diese
    // Kopplung liefe ein 8x-Impuls die achtfache Strecke, traefe achtmal so
    // viele Kreuzungen und splittete sich dort entsprechend oefter - die
    // Kettenreaktion bei hoher Speed.
    lastSpawnDecayScale=SpeedQuantizer.decayScaleFor(cls);
    // Swing auf der Geschwindigkeit, gleiche Formel und gleicher
    // Auslieferungswert 0 wie ueberall sonst: Choreografie primaer exakt,
    // Jitter ein optionaler Regler obendrauf.
    return SplitVariance.jitter(speed, speedQuantizeJitter.getValue(), Math.random());
  }

  // Der zu spawnSpeed() GEHOERENDE decayScale derselben Ziehung - siehe dort.
  // Muss unmittelbar nach spawnSpeed() fuer denselben Impuls aufgerufen
  // werden, bevor ein anderer Spawn-Pfad erneut zieht; alle fuenf Spawn-Pfade
  // rufen beide Methoden im selben Atemzug.
  private float spawnDecayScale() {
    return lastSpawnDecayScale;
  }

  // Der Ursprungs-Baum fuer EINEN neu gespawnten Impuls: 0..3 nach
  // StripeTreeStore.TREE_NAMES, -1 = unbekannt.
  //
  // Der einzige Ort, an dem ein Ursprungs-Baum entsteht - genau wie
  // spawnSpeed() der einzige Ort fuer eine Spawn-Geschwindigkeit ist. Alle
  // fuenf Spawn-Pfade (Tube-Trigger, activateStripe, activateNode,
  // RandomSpawn, Sequencer) gehen hierdurch; Split-Kinder und Filler ziehen
  // NICHT neu, sondern erben den Wert ihres Elternimpulses. Ein sechster
  // Pfad, der das Feld selbst setzte, waere ein Impuls mit falscher
  // Klangfarbe ohne Fehlermeldung.
  //
  // Ohne geladene Baum-Zuordnung liefert die Methode -1: die Show laeuft
  // dann ohne Klangbias weiter, statt alle Impulse in die Faerbung des
  // ersten Baums zu legen.
  private int spawnTree(int stripeIdx) {
    return (stripeTrees == null) ? -1 : stripeTrees.treeOf(stripeIdx);
  }

  // Die Baum-Zuordnung nachreichen. Aus setup() zu rufen, nachdem
  // data/stripeTrees.txt geladen ist. Bleibt sie ungesetzt, wirkt jeder
  // originTreeFilter wie 0 - die Show laeuft dann ohne Filter weiter.
  void setStripeTrees(StripeTreeStore store) {
    stripeTrees=store;
  }

  // Liest die /net/pause/*-Parameter in den wiederverwendeten Konfig-
  // Behaelter - dasselbe Muster wie der Trackconfig-Fuellblock in
  // tickSequencer(). Vor jedem pauseGate.tick() zu rufen.
  private void updatePauseGateConfig() {
    pauseGateConfig.enabled=pauseEnabled.getValue()==1;
    pauseGateConfig.checkIntervalBars=pauseCheckIntervalBars.getValue();
    pauseGateConfig.probability=pauseProbability.getValue();
    pauseGateConfig.lengthMinBars=pauseLengthMinBars.getValue();
    pauseGateConfig.lengthMaxBars=pauseLengthMaxBars.getValue();
    pauseGateConfig.affectsSequencer=pauseAffectsSequencer.getValue()==1;
    pauseGateConfig.affectsRandomSpawn=pauseAffectsRandomSpawn.getValue()==1;
  }

  // Strukturierter Spawn-Layer, siehe /net/sequencer/* in CLAUDE.md. Laeuft
  // unabhaengig neben spawnRandomImpulses() - beide Layer sind gleichzeitig
  // aktivierbar, der eine ist der chaotische Ambient-Teppich, der andere die
  // wiedererkennbare Choreografie.
  //
  // Die Uhr laeuft AUCH bei sequencerEnabled=0 weiter: sie ist die gemeinsame
  // Phase, und ein Stillstand waehrend der Aus-Phase machte das
  // Wiedereinschalten von der Dauer der Pause abhaengig. Fortgeschrieben wird
  // sie bereits in drawMe() (vor dem Pause-Gate-Tick) - hier nur noch
  // gelesen, kein zweiter advance() noetig.
  private void tickSequencer(double currentTime) {
    if (sequencerEnabled.getValue() != 1) {
      return;
    }
    if (pauseGate.blocksSequencer()) {
      // Ruhemoment: keine neuen Treffer, aber der Sequencer bleibt fachlich
      // "an" - repeatsLeft/nextBeat laufen unten trotzdem NICHT weiter,
      // sondern das Update wird komplett uebersprungen. Das ist bewusst wie
      // ein ausgeschalteter Track: verpasste Schlaege werden nicht
      // nachgeholt (dieselbe Kein-Nachholen-Regel wie OriginSequencer selbst),
      // und beim Ende der Pause faengt der naechste faellige Beat normal an.
      return;
    }
    for (int i=0; i<OriginSequencer.TRACK_COUNT; i++) {
      TrackConfig c=trackConfigs[i];
      c.enabled=trackEnabled[i].getValue()==1;
      c.noteValue=trackNoteValue[i].getValue();
      c.repeatCount=trackRepeatCount[i].getValue();
      c.energy=trackEnergy[i].getValue();
      c.swingJitter=trackSwingJitter[i].getValue();
      c.originStripeOverride=trackOriginOverride[i].getValue();
      // Baum-Filter: der erlaubte Stripe-Vorrat. null = kein Filter, und
      // genau das liefert der Store auch fuer Filterwert 0, fuer einen
      // ungueltigen Wert und fuer einen Baum ohne Stripes - der Track
      // spawnt dann wie bisher, statt still zu verstummen.
      c.originPool=(stripeTrees == null)
          ? null : stripeTrees.stripesFor(trackOriginTree[i].getValue());
    }
    int[] firing=originSequencer.update(musicalClock.beats(), trackConfigs, mathRandom);
    if (firing.length == 0) {
      return;
    }
    // Geschwindigkeit je Track einzeln gezogen (spawnSpeed): zwei Tracks, die
    // im selben Beat feuern, sollen nicht zwangslaeufig dieselbe Klasse
    // bekommen. Grundwert bleibt impulseSpeed, damit getaktete und zufaellige
    // Impulse denselben Bezugspunkt haben. decayScale kommt aus derselben
    // Ziehung wie die Geschwindigkeit (spawnDecayScale(), Kettenreaktions-Fix
    // 2026-08-02) - bei speedQuantize/enabled=0 liefert das weiterhin 1.0,
    // bitgleich dem vorherigen Verhalten.
    for (int i=0; i<firing.length; i++) {
      int track=firing[i];
      int stripeIdx=originSequencer.originOf(track);
      if (stripeIdx < 0 || stripeIdx >= nStripes) {
        continue;
      }
      float trackSpeed=spawnSpeed();
      activations.add(new TravellingActivation(stripeIdx*nLedsInStripe, stripeIdx,
          trackSpeed, trackConfigs[track].energy, spawnDecayScale(), spawnTree(stripeIdx)));
    }
  }

  // liefert `count` verschiedene Stripe-Indizes (0..nStripes-1), Ziehen ohne Zuruecklegen
  // ueber einen partiellen Fisher-Yates-Shuffle
  private int[] pickDistinctStripes(int count) {
    int n=Math.min(count, nStripes);
    int[] pool=new int[nStripes];
    for (int i=0; i<nStripes; i++) {
      pool[i]=i;
    }
    for (int i=0; i<n; i++) {
      int j=i + (int) (Math.random()*(nStripes-i));
      int tmp=pool[i];
      pool[i]=pool[j];
      pool[j]=tmp;
    }
    return Arrays.copyOf(pool, n);
  }

  void createRandomActivation() {
    int ledIdx=0;//papplet.floor(papplet.random(ledNetInfo.length));
    activations.add(new TravellingActivation(ledIdx, ledNetInfo[ledIdx].stripeIndex, 20, 1,
        spawnTree(ledNetInfo[ledIdx].stripeIndex)));
  }


  public String getName() {
    return name;
  }
}
