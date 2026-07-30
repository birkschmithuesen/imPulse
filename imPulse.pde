import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;

import netP5.*;
import oscP5.*;

//import spout.*; //use this on Windows
import codeanticode.syphon.*; //use this on MacOS


//////////////////////////////////////////////////////////////////////////
// To Do List
//////////////////////////////////////////////////////////////////////////
/*
 - solve problem when traveling speed of impulses is higher then framerate (when impulses jumps over an led from one frame to the next, the led is not light up)
 */

//////////////////////////////////////////////////////////////////////////
// LED data can be send as in a grafic buffer to Madmapper, or directly over Art-Net to the LED Controller
//////////////////////////////////////////////////////////////////////////
// canvs is a grafic buffer for the texture to send over syphon
// width: length of led stripes
// height: number of stripes 
PGraphics canvas;
//Spout server; //use this on Windows
SyphonServer server; //use this on MacOS

// Pixel2LED-Controller: nur die letzten Oktette. IP ist 2.2.2.<oktett>,
// das Start-Universum nach Konvention <oktett>*100. Die Reihenfolge im
// Array bestimmt die Stripe-Nummerierung: Controller k bedient die
// Stripes 2k (Output 1) und 2k+1 (Output 2).
int[] controllerOctets = { 2, 4, 6, 7, 8, 10, 12, 13, 14, 16, 17, 18, 19, 20, 21 };
ArtNetOutput artNetOutput;
RemoteControlledFloatParameter masterLevel;

OscP5 oscP5;
NetAddress oscOutput;

// an array of LedColor objects. One for each LED
LedColor[] ledColors;
LedInNetInfo[] ledNetInfo;
// create an array with all the nodes/crossings
ArrayList <LedNetworkNode> listOfNodes;
NodeCrossingStore crossingStore;

// the stripe configuration
int numLedsPerStripe = 600;                                   // 2 x 5 m je Output
int numStripes = controllerOctets.length * ArtNetOutput.OUTPUTS_PER_CONTROLLER;
int numLeds = numStripes * numLedsPerStripe;

// Draufsicht der Installation, am 2026-07-30 mit dem Betreiber festgelegt.
// Ursprung senkrecht unter der Netzmitte, X nach rechts, Y nach vorn.
// Installationsspezifisch - nicht aendern, ohne dass es um einen konkreten
// Aufbau geht.
float footprintX = 14f;                      // Meter
float footprintY = 8f;                       // Meter
float stripeLengthM = 10f;                   // 2 x 5 m, durchgehend verbunden
float ledPitchM = stripeLengthM / numLedsPerStripe;   // 0.0166667 m

// Draufsicht-Flaeche im Positionsmodus. 525:300 entspricht 14:8 genau, es
// gibt also keine Verzerrung; ein Pixel sind 2,67 cm.
int paneX = 0, paneY = 0, paneW = 525, paneH = 300;

LedAnchorStore ledAnchorStore;
LedPositionMap ledPositionMap;
LedPositionCalibration ledPositionCalibration;
boolean positionMode = false;

// a mixer object where all visuals come together and are merged
Mixer mixer;

//visual generators
LedNetworkTransportEffect ledNetworkTransportEffect;
LedNetworkNodeEffects ledNetworkNodeEffects;

int counter=0;

// Zwei-Cursor-Werkzeug fuer die Node-Kalibrierung (ersetzt das alte
// Dropdown-Menue mit sieben Modi).
NodeCalibration nodeCalibration;
boolean calibrationMode = false;

// Sicherheitsventil ausschliesslich fuer die Testbilder (TestPatterns 1-5,
// siehe drawPattern()/calibrationMode) - unabhaengig vom Show-Fader
// masterLevel, der seit 2026-07-30 bis 1.0 gehen darf. TestPatterns 3/5
// senden bewusst Vollweiss (1,1,1); bei 10-m-Stripe-Laenge ist das laut
// Handbuch schon ein Spannungsabfall-Risiko. Bewusst eine Konstante statt
// eines OSC-Parameters, damit sie sich nicht versehentlich hochschrauben
// laesst (gleiche Begruendung wie test/PatternProbe.java MASTER_LEVEL).
static final float CALIBRATION_MASTER_LEVEL = 0.1f;


void setup() {
  // Fensterhoehe: die Vorschau braucht numStripes*10 Pixel (image() weiter
  // unten skaliert genau darauf), bei 30 Stripes also 300px. Darunter zeigt
  // das HUD im Kalibriermodus vier Textzeilen (Cursorstaende/Zaehler/Auswahl,
  // Meldung, Tastenbelegung auf zwei Zeilen) beginnend bei y=numStripes*10+20 - dafuer
  // braucht es nochmal gut 100px Reserve. 450 ist bei 30 Stripes sicher
  // ausreichend (300 Vorschau + 20 Abstand + 4 Zeilen + Rand). size()
  // erlaubt hier keine Variablen, deshalb ein Literal - bei einer anderen
  // Stripe-Zahl muss dieser Wert von Hand neu aus derselben Rechnung
  // (numStripes*10 + ca. 150) bestimmt werden.
  size(1400, 450, P3D);
  frameRate(40);
  //opens the port to receive OSC
  oscP5 = new OscP5(this, 8001);
  //when a node is activated an osc impuls is send to Ableton Live
  oscOutput = new NetAddress("127.0.0.1", 8002);//("192.168.88.253", 8002);

  // Create Syhpon/Spout server to send frames out directly shared on gpu.
  //server = new Spout(this); //use this on Windows
  //server.createSender("Lightstrument"); //use this on Windows
  //server = new SyphonServer(this, "Lightstrument"); //use this on MacOs

  artNetOutput = new ArtNetOutput(controllerOctets, numLedsPerStripe); // used to send data to leds
  System.out.print(artNetOutput.describeMapping());
  // 2026-07-30, Birk: Obergrenze auf 0..1 freigegeben - die Show fährt die
  // Stripes bewusst nie auf Vollweiss, das Hardware-Risiko (Spannungsabfall
  // bei Weiss auf 10 m Laenge) betraf nur die Testbilder (TestPatterns 3/5
  // senden (1,1,1)). Die Testbilder haben deshalb jetzt einen eigenen, vom
  // Fader unabhaengigen Fixpegel CALIBRATION_MASTER_LEVEL statt masterLevel
  // zu nutzen - der Fader selbst darf nun bis 1.0 gehen.
  masterLevel = new RemoteControlledFloatParameter("/master/level", 0.1f, 0f, 1f);
  artNetOutput.start();

  // use the canvas to create the visuals to send over syphon
  // the size depends on the stripe configuration
  canvas = createGraphics(numLedsPerStripe, numStripes, P3D);

  ledColors = LedColor.createColorArray(numLeds);        // build a color buffer with the length of the position file
  ledNetInfo = LedInNetInfo.buildNetInfo(numStripes, numLedsPerStripe); //create an Array with data for each LED if they are part of a node
  crossingStore = new NodeCrossingStore(numStripes, numLedsPerStripe);
  crossingStore.load(dataPath("nodeCrossings.txt"));
  System.out.println(crossingStore.lastMessage());
  listOfNodes = new ArrayList<LedNetworkNode>();
  LedInNetInfo.applyCrossings(crossingStore.crossings(), ledNetInfo, listOfNodes);  // all sets of Leds that are on different stripes but close to each other
  ledAnchorStore = new LedAnchorStore(numStripes, numLedsPerStripe,
      footprintX, footprintY, ledPitchM);
  ledAnchorStore.load(dataPath("ledPositions.txt"));
  System.out.println(ledAnchorStore.lastMessage());
  ledPositionMap = new LedPositionMap(numStripes, numLedsPerStripe, footprintX, footprintY);
  ledPositionMap.apply(ledAnchorStore);
  LedNetworkNode.applyPositions(ledPositionMap, listOfNodes);
  // Eine Warnzeile, wenn Positionen fehlen. Die Show laeuft dann wie bisher,
  // nur ohne Raumbezug - jede Koordinate ist (0,0), also die Netzmitte.
  if (ledPositionMap.undefinedCount() > 0) {
    System.out.println("WARNUNG: " + ledPositionMap.coverageReport(ledAnchorStore));
    System.out.println("WARNUNG: diese LEDs senden (0,0) als Klangposition. "
        + "Positionen mit P aufnehmen, siehe docs/positionen-anleitung.md");
  }
  ledPositionCalibration = new LedPositionCalibration(ledAnchorStore, ledPositionMap,
      crossingStore, listOfNodes, numStripes, numLedsPerStripe,
      dataPath("ledPositions.txt"), paneX, paneY, paneW, paneH, footprintX, footprintY);
  nodeCalibration = new NodeCalibration(crossingStore, ledNetInfo, listOfNodes,
      numStripes, numLedsPerStripe, dataPath("nodeCrossings.txt"));

  //initialize visual effects
  ledNetworkTransportEffect = new LedNetworkTransportEffect("1", numLeds, numStripes, numLedsPerStripe, ledNetInfo, listOfNodes, ledPositionMap, oscP5, oscOutput);
  ledNetworkNodeEffects = new LedNetworkNodeEffects("1", numLeds, ledNetInfo, listOfNodes);

  mixer = new Mixer(numLeds);
  mixer.addEffect(ledNetworkTransportEffect);
  mixer.addEffect(ledNetworkNodeEffects);

  //to save the osc-adresses
  try {
    System.out.println(dataPath("remoteSettings.txt"));
 //   DataOutputStream dataOut = new DataOutputStream(new FileOutputStream("C:\\Users\\VideoServer\\Desktop\\impulsPlayground\\imPulse\\data\\remoteSettings.txt"));
 DataOutputStream dataOut = new DataOutputStream(new FileOutputStream(dataPath("remoteSettings.txt")));
    OscMessageDistributor.dumpParameterInfo(dataOut);
  }
  catch (FileNotFoundException e) {
    println("file not found");
  }
}

void draw() {
  OscMessageDistributor.distributeMessages();
  //createRandomPipeTrigger();  // for test purpose create random activations (instead of hitting a pipe)
  if (calibrationMode) {
    nodeCalibration.update();
    ledColors = nodeCalibration.drawMe();
  } else if (positionMode) {
    ledColors = ledPositionCalibration.drawMe();
  } else {
    ledColors = mixer.mix();
  }
  drawLedColorsToCanvas(); // the visuals to be displayed on the led-stripes are drawn into the canvas to be displayed on the screen
  if (positionMode) {
    // Im Positionsmodus belegt die Draufsicht-Flaeche den Bereich links, die
    // verkleinerte LED-Vorschau sitzt rechts daneben.
    // background(0) raeumt dabei zugleich den vorherigen Frame weg - P3D
    // behaelt ihn sonst, und Flaeche wie HUD wuerden sich uebereinander
    // stapeln, statt ersetzt zu werden.
    background(0);
    drawPositionPane();
    image(canvas, 560, 0, 600, 120);
    fill(255);
    text(ledPositionCalibration.hudText(), 10, paneY + paneH + 20);
  } else {
    image(canvas, 0, 0, numLedsPerStripe*2, numStripes*10); // display the led-stripes
    if (calibrationMode) {
      // P3D behaelt den Inhalt des vorherigen Frames - ohne dieses Loeschen
      // wuerde sich der HUD-Text unterhalb der Vorschau zu einem unlesbaren
      // Klumpen stapeln, statt jeden Frame ersetzt zu werden
      fill(0);
      noStroke();
      rect(0, numStripes * 10, width, height - numStripes * 10);
      fill(255);
      text(nodeCalibration.hudText(), 10, numStripes * 10 + 20);
    }
  }
  // send the visuals over Syphon/Spout to MadMapper. MadMapper can mix the impulses with other visuals/shaders, control brightness (...) with nice UI and send the data out over UDP (Art-Net)
  //server.sendTexture(canvas); //use this on Windows
  //server.sendImage(canvas); //use this on MacOS
  //send data directly to ArtNet Interface withoput MadMapper in between
  // Testbilder (calibrationMode) laufen auf einem eigenen Fixpegel statt dem
  // Show-Fader: TestPatterns 3/5 senden Vollweiss (1,1,1), das darf nicht mit
  // masterLevel bis 1.0 rausgehen (Spannungsabfall-Risiko bei Weiss auf 10 m).
  artNetOutput.setMasterLevel(calibrationMode ? CALIBRATION_MASTER_LEVEL : masterLevel.getValue());
  artNetOutput.publish(ledColors);
}

void oscEvent(OscMessage theOscMessage) {
  OscMessageDistributor.queueMessage(theOscMessage);
}

void drawLedColorsToCanvas() {
  canvas.beginDraw();
  canvas.loadPixels();
  for (int i = 0; i < numLeds; i++) {
    canvas.pixels[i] = color(map(ledColors[i].x, 0., 1., 0, 255), map(ledColors[i].y, 0., 1., 0, 255), map(ledColors[i].z, 0., 1., 0, 255));
  }
  canvas.updatePixels();
  canvas.endDraw();
}

// Zeichnet die Draufsicht: Raster, Lautsprecher, gesetzte Anker, den Verlauf
// des aktuellen Stripes und den aktuellen Eintrag. Die Umrechnung kommt aus
// LedPositionCalibration.worldToPane, damit gezeichneter Punkt und
// angeklickte Stelle dieselbe Rechnung benutzen.
void drawPositionPane() {
  float[] p = new float[2];
  float[] q = new float[2];

  noFill();
  stroke(60);
  strokeWeight(1);
  rect(paneX, paneY, paneW, paneH);
  // 1-m-Raster
  for (float mx = -footprintX/2 + 1; mx < footprintX/2; mx += 1) {
    ledPositionCalibration.worldToPane(mx, 0, p);
    line(p[0], paneY, p[0], paneY + paneH);
  }
  for (float my = -footprintY/2 + 1; my < footprintY/2; my += 1) {
    ledPositionCalibration.worldToPane(0, my, p);
    line(paneX, p[1], paneX + paneW, p[1]);
  }

  // Die vier Lautsprecher auf den Seitenmitten
  float[][] speakers = { {0, footprintY/2}, {footprintX/2, 0},
                         {0, -footprintY/2}, {-footprintX/2, 0} };
  noStroke();
  fill(200, 160, 0);
  for (int i = 0; i < speakers.length; i++) {
    ledPositionCalibration.worldToPane(speakers[i][0], speakers[i][1], p);
    rect(p[0] - 4, p[1] - 4, 8, 8);
  }

  // Verlauf des aktuellen Stripes ueber alle seine Anker
  int cur = ledPositionCalibration.entryIndex();
  if (cur >= 0) {
    int firstLed = ledPositionCalibration.ledsOfEntry(cur)[0];
    int stripe = firstLed / numLedsPerStripe;
    stroke(0, 120, 200);
    noFill();
    int prev = -1;
    for (int i = 0; i < numLedsPerStripe; i += 10) {
      int idx = stripe * numLedsPerStripe + i;
      if (!ledPositionMap.isDefined(idx)) { prev = -1; continue; }
      ledPositionCalibration.worldToPane(ledPositionMap.x(idx), ledPositionMap.y(idx), p);
      if (prev >= 0) {
        ledPositionCalibration.worldToPane(ledPositionMap.x(prev), ledPositionMap.y(prev), q);
        line(q[0], q[1], p[0], p[1]);
      }
      prev = idx;
    }
  }

  // Alle gesetzten Anker
  noStroke();
  fill(120);
  for (int e = 0; e < ledPositionCalibration.entryCount(); e++) {
    if (!ledPositionCalibration.entryIsSet(e)) { continue; }
    int led = ledPositionCalibration.ledsOfEntry(e)[0];
    ledPositionCalibration.worldToPane(ledAnchorStore.x(led), ledAnchorStore.y(led), p);
    ellipse(p[0], p[1], 5, 5);
  }

  // Der aktuelle Eintrag: gefuellt wenn gesetzt, hohl wenn nur Vorschlag
  if (ledPositionCalibration.displayPosition(p)) {
    float wx = p[0], wy = p[1];
    ledPositionCalibration.worldToPane(wx, wy, p);
    stroke(255);
    strokeWeight(2);
    if (ledPositionCalibration.entryIsSet(cur)) {
      fill(255);
    } else {
      noFill();
    }
    ellipse(p[0], p[1], 13, 13);
    strokeWeight(1);
  }
}

void mousePressed() { positionClick(); }
void mouseDragged() { positionClick(); }

// Ein Klick oder Ziehen in der Draufsicht-Flaeche setzt die Position des
// aktuellen Eintrags. Klicks ausserhalb - etwa ins HUD - werden verworfen,
// darum kuemmert sich paneToWorld.
void positionClick() {
  if (!positionMode) { return; }
  float[] w = new float[2];
  if (ledPositionCalibration.paneToWorld(mouseX, mouseY, w)) {
    ledPositionCalibration.setCurrent(w[0], w[1]);
  }
}

void createRandomPipeTrigger() {
  if (counter > 60) {
    counter=0;    
    OscMessage myMessage = new OscMessage("/tube/trigger");
    myMessage.add((int)random(1, numStripes+2));
    myMessage.add((float)random(0.6, 1));
    NetAddress localhost = new NetAddress("127.0.0.1", 8001);
    oscP5.send(myMessage, localhost);
  }
  counter++;
}

void keyPressed() {
  if (calibrationMode && key == CODED) {
    nodeCalibration.handleKeyPressed(keyCode, key);
  } else if (positionMode && key == CODED) {
    // Pfeil hoch bewegt nach vorn, also nach +Y und auf dem Schirm nach oben
    if (keyCode == LEFT)  { ledPositionCalibration.nudge(-1, 0); }
    if (keyCode == RIGHT) { ledPositionCalibration.nudge(1, 0); }
    if (keyCode == UP)    { ledPositionCalibration.nudge(0, 1); }
    if (keyCode == DOWN)  { ledPositionCalibration.nudge(0, -1); }
    ledPositionCalibration.abortClearAll();
  }
}

void keyReleased() {
  if (key == 'c' || key == 'C') {
    calibrationMode = !calibrationMode;
    if (calibrationMode) { positionMode = false; }   // beide Modi belegen dieselben Tasten
    println(calibrationMode ? "Kalibriermodus an" : "Kalibriermodus aus");
    nodeCalibration.handleKeyReleased();
    // sonst zeigt ein Wiedereintritt das zuletzt gewaehlte Testbild statt
    // der Kalibrierung
    nodeCalibration.setPattern(0);
    return;
  }
  if (key == 'p' || key == 'P') {
    positionMode = !positionMode;
    if (positionMode) { calibrationMode = false; }
    println(positionMode ? "Positionsmodus an" : "Positionsmodus aus");
    ledPositionCalibration.abortClearAll();
    if (positionMode) {
      // Die Kreuzungsliste kann sich im Kalibriermodus geaendert haben
      ledPositionCalibration.reapply();
      println(ledPositionCalibration.lastMessage());
    }
    return;
  }
  if (positionMode) {
    if (key != 'l' && key != 'L') { ledPositionCalibration.abortClearAll(); }
    if (key == ',') { ledPositionCalibration.prev(); }
    else if (key == '.') { ledPositionCalibration.next(); }
    else if (key == 'o' || key == 'O') { ledPositionCalibration.nextOpen(); }
    else if (key == '\n' || key == '\r') { ledPositionCalibration.acceptProposal(); }
    else if (key == 8 || key == 127) { ledPositionCalibration.clearCurrent(); }
    else if (key == 'f' || key == 'F') { ledPositionCalibration.cycleStep(); }
    else if (key == 's' || key == 'S') { ledPositionCalibration.save(); }
    else if (key == 'r' || key == 'R') { ledPositionCalibration.reapply(); }
    else if (key == 't' || key == 'T') { ledPositionCalibration.coverageReport(); }
    else if (key == 'l' || key == 'L') {
      ledPositionCalibration.requestClearAll(System.currentTimeMillis());
    }
    return;
  }
  if (!calibrationMode) {
    return;
  }
  if (key == CODED) {
    nodeCalibration.handleKeyReleased();
  } else {
    nodeCalibration.handleCommand(key);
    if (key == 'r' || key == 'R') {
      // Muss NACH handleCommand() laufen: erst dort landen die neuen
      // Kreuzungen ueber LedInNetInfo.applyCrossings() in listOfNodes.
      // applyCrossings() baut die LedNetworkNode-Objekte dabei komplett neu
      // auf, die frischen Knoten haben also posX/posY = 0. Ohne dieses
      // Nachziehen meldete /net/hitNode ab diesem Moment fuer JEDEN Knoten
      // (0,0), die Netzmitte - die Klangmaschine legte alle Stimmen auf
      // denselben Punkt. Auf der Processing-Seite sieht man davon nichts:
      // kein Fehler, kein sichtbares Symptom, und es bleibt bis zum naechsten
      // Neustart so. Diese Zeile also nicht wegkuerzen.
      // reapply() ist hier auch unabhaengig davon richtig: es baut zusaetzlich
      // die Arbeitsliste des Positionswerkzeugs neu, und die aendert sich
      // durch eine neue Kreuzung ebenfalls (zwei bisher getrennte Eintraege
      // verschmelzen zu einem).
      ledPositionCalibration.reapply();
      println(ledPositionCalibration.lastMessage());
    }
  }
}

// Von Processing beim Beenden des Sketches aufgerufen. Ohne dieses dispose()
// wuerde artNetOutput.stop() nie gerufen und die Stripes blieben im letzten
// gesendeten Bild stehen - die Firmware blankt nicht von selbst
// (blackOnOpSyncTimeOut/blackOnOpPollTimeOut sind dort auskommentiert).
void dispose() {
  println("Sketch wird beendet, sende Schwarzbild und stoppe Art-Net-Sender");
  LedColor[] black = LedColor.createColorArray(numLeds);
  artNetOutput.publish(black);
  // kurze Pause, damit der 40-Hz-Sender-Thread den Schwarzbild-Frame noch
  // abholt und verschickt, bevor der Socket in stop() geschlossen wird
  try {
    Thread.sleep(100);
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
  }
  artNetOutput.stop();
}
