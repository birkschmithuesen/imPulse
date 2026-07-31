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

  LedColor[] stripeColorMapping = {new LedColor(68/255f,0/255f,62/255f), new LedColor(189/255f,103/255f,0/255f), new LedColor(236/255f,204/255f,0/255f), new LedColor(221/255f,65/255f,8/255f),
                             new LedColor(187/255f,213/255f,67/255f), new LedColor(126/255f,201/255f,232/255f), new LedColor(210/255f,39/255f,45/255f), new LedColor(234/255f,147/255f,44/255f)};

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

  RemoteControlledIntParameter impulseUseRemoteCol; 
  RemoteControlledFloatParameter impulseR;
  RemoteControlledFloatParameter impulseG;
  RemoteControlledFloatParameter impulseB;

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

    impulseUseRemoteCol = new RemoteControlledIntParameter("/net/impulse/color/useRemoteCol", 1, 0, 1);
    impulseR= new RemoteControlledFloatParameter("/net/impulse/color/r", 1, 0, 1); // color of travelling impulse
    impulseG= new RemoteControlledFloatParameter("/net/impulse/color/g", 1, 0, 1); // color of travelling impulse
    impulseB= new RemoteControlledFloatParameter("/net/impulse/color/b", 1, 0, 1); // color of travelling impulse

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
      trackConfigs[i]= new TrackConfig();
    }

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
        for (Integer nodeLedIdx : activeNode.ledIndices) {
          LedInNetInfo curLedInfo=ledNetInfo[nodeLedIdx]; //which stripe are we on?
          //  activation spreads in boths directions
          int forwPos=nodeLedIdx +1;
          if (forwPos>0&&forwPos<nLeds) {
			activations.add(new TravellingActivation(forwPos, curLedInfo.stripeIndex, cmdSpeed, 1f ));
		}
          //do not go back the same stripe:
          int backwPos=nodeLedIdx -1;
          if (backwPos>0&&backwPos<nLeds) {
			activations.add(new TravellingActivation(backwPos, curLedInfo.stripeIndex, -cmdSpeed, 1f));
		}
        }
      }
    }
    if (newMessage.checkAddrPattern("/net/activateStripe") &&
      newMessage.arguments().length>0&&
      newMessage.getTypetagAsBytes()[0]=='i'
      ) {
      int theValue=newMessage.get(0).intValue();
      activations.add(new TravellingActivation(theValue*nLedsInStripe, theValue, spawnSpeed(), 1f ));
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
        activations.add(new TravellingActivation(theValue*nLedsInStripe, theValue, spawnSpeed(), energy));
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
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_) {
      this(ledIdxPos_, stripeIdx_, speed_, energy_, nextImpulseId++, 1f);
    }

    // Mit ausdruecklichem decayScale - fuer die Kinder einer Aufspaltung, die
    // ihre Lebensdauer streuen sollen (siehe /net/impulse/splitLifetimeJitter).
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        float decayScale_) {
      this(ledIdxPos_, stripeIdx_, speed_, energy_, nextImpulseId++, decayScale_);
    }

    // Mit ausdruecklicher ID - nur fuer den Filler, der die ID seines
    // Elternimpulses uebernimmt statt eine neue zu verbrauchen.
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        int id_, float decayScale_) {
      ledIdxPos=ledIdxPos_;
      stripeIdx=stripeIdx_;
      speed=speed_;
      energy=energy_;
      id=id_;
      decayScale=decayScale_;
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
    void setEnergy(float _energy){energy=_energy;}
  }

  //represents fillers needed when high travelling speeds lead to skipping some leds in each frame
  public class TravellingActivationFiller extends TravellingActivation {
    TravellingActivationFiller(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        int parentId_, float decayScale_) {
      super(ledIdxPos_, stripeIdx_, speed_, energy_, parentId_, decayScale_);
    }
  }

  //simulate one time step
  public LedColor[] drawMe() {
    int useRemoteCol = impulseUseRemoteCol.getValue();
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

    spawnRandomImpulses(currentTime);
    tickSequencer(currentTime);

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
          newActivations.add(new TravellingActivationFiller(curActivationLedIdx, curLedInfo.stripeIndex, curActivation.speed, curActivation.energy, curActivation.id, curActivation.decayScale));
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
      if (useRemoteCol == 1) {
        bufferLedColors[curLedIndex].set(spotR*fade*curActivation.energy, spotG*fade*curActivation.energy, spotB*fade*curActivation.energy);
      } else {
        // Tabelle hat acht Eintraege, es gibt aber 30 Stripes
        LedColor col = stripeColorMapping[ledNetInfo[curLedIndex].stripeIndex % stripeColorMapping.length];

      bufferLedColors[curLedIndex].set(col.x*fade, col.y*fade, col.z*fade);
      }
      //if the travelling activation is a filler remove it
      if (curActivation.getClass() == TravellingActivationFiller.class) {
        iter.remove();
      } else if (curActivation.speed < 0 && curLedIndex <= (ledNetInfo[curLedIndex].stripeIndex*nLedsInStripe+27)) {
        iter.remove();
      }
    }
    sendImpulseStream(currentTime);
    return bufferLedColors;
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
        //send osc Notification
        sendOscMessage(hitNode, curActivation);
        float nActivations=hitNode.ledIndices.size();
        //energieerhaltende Variante, bewusst nicht aktiv (siehe CLAUDE.md):
        //float childEnergy=curActivation.energy/nActivations/2.0f;
        //curActivation.setEnergy(childEnergy);
        float childEnergy=curActivation.energy;
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
            newActivations.add(new TravellingActivation(forwPos, curLedInfo.stripeIndex,
                SplitVariance.jitter(curActivation.speed, speedJitter, Math.random()),
                childEnergy,
                SplitVariance.jitter(1f, lifetimeJitter, Math.random())));
          }
          //do not go back the same stripe:
          if (ledNetInfo[nodeLedIdx].stripeIndex!=ledNetInfo[activationLedIdx].stripeIndex || activationLedIdx < nodeLedIdx) {//ledNetInfo[nodeLedIdx].stripeIndex!=ledNetInfo[activationLedIdx].stripeIndex) {
            int backwPos=nodeLedIdx -jump;
            if (backwPos>0&&backwPos<nLeds) {
              newActivations.add(new TravellingActivation(backwPos, curLedInfo.stripeIndex,
                  SplitVariance.jitter(-curActivation.speed, speedJitter, Math.random()),
                  childEnergy,
                  SplitVariance.jitter(1f, lifetimeJitter, Math.random())));
            }
          }
        }
        return true;
      }
    }
    return false;
  }


  private void sendOscMessage(LedNetworkNode hitNode, TravellingActivation curActivation) {
    OscMessage myMessage = new OscMessage("/net/hitNode");
    myMessage.add(hitNode.id);
    myMessage.add(curActivation.energy);
    // Draufsicht-Position des Knotens in Metern, Ursprung Netzmitte. Kein z -
    // das Netz haengt ueber Kopf, vier Lautsprecher in einer Ebene koennen die
    // Hoehe nicht darstellen.
    //
    // Rueckwaertskompatibel: ein Klang-Sketch, der nur msg[1] und msg[2]
    // liest, ignoriert die zwei zusaetzlichen Argumente.
    myMessage.add(hitNode.posX);
    myMessage.add(hitNode.posY);
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
      // "rueckwaerts" beginnt am anderen Ende des Stripes, sonst wuerde der Impuls sofort
      // wieder aus den Bounds fallen (siehe activationIsValid) statt eine sichtbare Strecke zu reisen
      float startPos=forward ? stripeIdx*nLedsInStripe : stripeIdx*nLedsInStripe + (nLedsInStripe-1);
      activations.add(new TravellingActivation(startPos, stripeIdx, forward ? speed : -speed, energy));
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
  private float spawnSpeed() {
    float base=impulseSpeed.getValue();
    if (speedQuantizeEnabled.getValue() != 1) {
      return base;
    }
    for (int i=0; i<speedClassWeights.length; i++) {
      weightScratch[i]=speedClassWeights[i].getValue();
    }
    int cls=SpeedQuantizer.pick(weightScratch, Math.random());
    float speed=base*SpeedQuantizer.multiplierAt(cls);
    // Swing auf der Geschwindigkeit, gleiche Formel und gleicher
    // Auslieferungswert 0 wie ueberall sonst: Choreografie primaer exakt,
    // Jitter ein optionaler Regler obendrauf.
    return SplitVariance.jitter(speed, speedQuantizeJitter.getValue(), Math.random());
  }

  // Strukturierter Spawn-Layer, siehe /net/sequencer/* in CLAUDE.md. Laeuft
  // unabhaengig neben spawnRandomImpulses() - beide Layer sind gleichzeitig
  // aktivierbar, der eine ist der chaotische Ambient-Teppich, der andere die
  // wiedererkennbare Choreografie.
  //
  // Die Uhr laeuft AUCH bei sequencerEnabled=0 weiter: sie ist die gemeinsame
  // Phase, und ein Stillstand waehrend der Aus-Phase machte das
  // Wiedereinschalten von der Dauer der Pause abhaengig.
  private void tickSequencer(double currentTime) {
    musicalClock.advance(currentTime, sequencerBpm.getValue());
    if (sequencerEnabled.getValue() != 1) {
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
    }
    int[] firing=originSequencer.update(musicalClock.beats(), trackConfigs, mathRandom);
    if (firing.length == 0) {
      return;
    }
    // Geschwindigkeit je Track einzeln gezogen (spawnSpeed): zwei Tracks, die
    // im selben Beat feuern, sollen nicht zwangslaeufig dieselbe Klasse
    // bekommen. Grundwert bleibt impulseSpeed, damit getaktete und zufaellige
    // Impulse denselben Bezugspunkt haben. decayScale 1.0 (der Konstruktor
    // ohne ausdruecklichen Wert): ein gespawnter Impuls folgt dem globalen
    // Lifetime, gestreut wird erst an einer Kreuzung.
    for (int i=0; i<firing.length; i++) {
      int track=firing[i];
      int stripeIdx=originSequencer.originOf(track);
      if (stripeIdx < 0 || stripeIdx >= nStripes) {
        continue;
      }
      activations.add(new TravellingActivation(stripeIdx*nLedsInStripe, stripeIdx,
          spawnSpeed(), trackConfigs[track].energy));
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
    activations.add(new TravellingActivation(ledIdx, ledNetInfo[ledIdx].stripeIndex, 20, 1));
  }


  public String getName() {
    return name;
  }
}
