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
        for (Integer nodeLedIdx : activeNode.ledIndices) {
          LedInNetInfo curLedInfo=ledNetInfo[nodeLedIdx]; //which stripe are we on?
          //  activation spreads in boths directions
          int forwPos=nodeLedIdx +1;           
          if (forwPos>0&&forwPos<nLeds) {
			activations.add(new TravellingActivation(forwPos, curLedInfo.stripeIndex, impulseSpeed.getValue(), 1f ));
		}
          //do not go back the same stripe:
          int backwPos=nodeLedIdx -1;            
          if (backwPos>0&&backwPos<nLeds) {
			activations.add(new TravellingActivation(backwPos, curLedInfo.stripeIndex, -impulseSpeed.getValue(), 1f));
		}
        }
      }
    }
    if (newMessage.checkAddrPattern("/net/activateStripe") &&
      newMessage.arguments().length>0&&
      newMessage.getTypetagAsBytes()[0]=='i'
      ) {
      int theValue=newMessage.get(0).intValue();
      activations.add(new TravellingActivation(theValue*nLedsInStripe, theValue, impulseSpeed.getValue(), 1f ));
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
        activations.add(new TravellingActivation(theValue*nLedsInStripe, theValue, impulseSpeed.getValue(), energy));
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
      this(ledIdxPos_, stripeIdx_, speed_, energy_, nextImpulseId++);
    }

    // Mit ausdruecklicher ID - nur fuer den Filler, der die ID seines
    // Elternimpulses uebernimmt statt eine neue zu verbrauchen.
    TravellingActivation(float ledIdxPos_, int stripeIdx_, float speed_, float energy_, int id_) {
      ledIdxPos=ledIdxPos_;
      stripeIdx=stripeIdx_;
      speed=speed_;
      energy=energy_;
      id=id_;
    }

    int getLedIndex() {
      return (int)(ledIdxPos+0.5f); // global led position
    }
    float ledIdxPos; // absolute led position - used for mapping to led buffer
    int stripeIdx; // stripe the activation was created on
    float speed; // [leds/second] also encodes direction in sign
    float energy; // some measure of strength
    final int id; // fortlaufend, fuer /net/impulse
    void setEnergy(float _energy){energy=_energy;}
  }

  //represents fillers needed when high travelling speeds lead to skipping some leds in each frame
  public class TravellingActivationFiller extends TravellingActivation {
    TravellingActivationFiller(float ledIdxPos_, int stripeIdx_, float speed_, float energy_,
        int parentId_) {
      super(ledIdxPos_, stripeIdx_, speed_, energy_, parentId_);
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
    // auch spawnRandomImpulses() liest impulseSpeed
    applyRandomizers(currentTime);
    float speed=impulseSpeed.getValue();

    spawnRandomImpulses(currentTime);

    //iterate through activations and build a new list of activations in the meanwhile.
    LinkedList<TravellingActivation> newActivations=new LinkedList<TravellingActivation>();

    for (TravellingActivation curActivation : activations) {
      int prevActivationLedIdx=curActivation.getLedIndex();
      // let each activation travel a bit in it's direction
      curActivation.ledIdxPos+=curActivation.speed*timeStep;
      // loose energy
      curActivation.energy -= timeStep*impulseLifetime.getValue();
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
          newActivations.add(new TravellingActivationFiller(curActivationLedIdx, curLedInfo.stripeIndex, curActivation.speed, curActivation.energy, curActivation.id));
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
            newActivations.add(new TravellingActivation(forwPos, curLedInfo.stripeIndex, curActivation.speed, childEnergy));
          }
          //do not go back the same stripe:
          if (ledNetInfo[nodeLedIdx].stripeIndex!=ledNetInfo[activationLedIdx].stripeIndex || activationLedIdx < nodeLedIdx) {//ledNetInfo[nodeLedIdx].stripeIndex!=ledNetInfo[activationLedIdx].stripeIndex) {
            int backwPos=nodeLedIdx -jump;
            if (backwPos>0&&backwPos<nLeds) {
              newActivations.add(new TravellingActivation(backwPos, curLedInfo.stripeIndex, -curActivation.speed, childEnergy));
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

  // Gedrosselter Positionsstrom der reisenden Impulse.
  //
  // Wird bewusst NACH der Zeichenschleife gerufen: die hat die Filler im
  // selben Frame ueber iter.remove() wieder entfernt, in activations steht
  // hier also genau ein Eintrag je echtem Impuls. Ohne diese Reihenfolge
  // muesste man Filler von Hand aussortieren und wuerde denselben Impuls
  // mehrfach melden.
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
      flat[k] = a;
      energies[k] = a.energy;
      k++;
    }
    int[] chosen = impulseThrottle.select(energies, impulseOscMaxCount.getValue());
    for (int i = 0; i < chosen.length; i++) {
      TravellingActivation a = flat[chosen[i]];
      int ledIndex = a.getLedIndex();
      OscMessage myMessage = new OscMessage("/net/impulse");
      myMessage.add(a.id);
      myMessage.add(positionMap.x(ledIndex));
      myMessage.add(positionMap.y(ledIndex));
      myMessage.add(a.energy);
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
    float speed=impulseSpeed.getValue(); // bewusst kein eigener Speed-Parameter, siehe Feldkommentar

    for (int stripeIdx : pickDistinctStripes(count)) {
      boolean forward=Math.random() < directionBias;
      // "rueckwaerts" beginnt am anderen Ende des Stripes, sonst wuerde der Impuls sofort
      // wieder aus den Bounds fallen (siehe activationIsValid) statt eine sichtbare Strecke zu reisen
      float startPos=forward ? stripeIdx*nLedsInStripe : stripeIdx*nLedsInStripe + (nLedsInStripe-1);
      activations.add(new TravellingActivation(startPos, stripeIdx, forward ? speed : -speed, energy));
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
