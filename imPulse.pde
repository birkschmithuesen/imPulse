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
StripeConfigurator stripeConfiguration;

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


void setup() {
  // Fensterhoehe: die Vorschau braucht numStripes*10 Pixel (image() weiter
  // unten skaliert genau darauf), bei 30 Stripes also 300px. Darunter zeigt
  // das HUD im Kalibriermodus drei Textzeilen (Cursorstaende/Zaehler,
  // Meldung, Tastenbelegung) beginnend bei y=numStripes*10+20 - dafuer
  // braucht es nochmal gut 100px Reserve. 450 ist bei 30 Stripes sicher
  // ausreichend (300 Vorschau + 20 Abstand + 3 Zeilen + Rand). size()
  // erlaubt hier keine Variablen, deshalb ein Literal - bei einer anderen
  // Stripe-Zahl muss dieser Wert von Hand neu aus derselben Rechnung
  // (numStripes*10 + ca. 150) bestimmt werden.
  size(1400, 450, P3D);
  frameRate(40);
  //opens the port to receive OSC
  oscP5 = new OscP5(this, 8001);
  //when a node is activated an osc impuls is send to Ableton Live
  oscOutput = new NetAddress("127.0.0.1", 8002);//("192.168.88.253", 8002);

  // create stripe information
  stripeConfiguration = new StripeConfigurator(numStripes, numLedsPerStripe); // used to generate per led info.


  // Create Syhpon/Spout server to send frames out directly shared on gpu.
  //server = new Spout(this); //use this on Windows
  //server.createSender("Lightstrument"); //use this on Windows
  //server = new SyphonServer(this, "Lightstrument"); //use this on MacOs

  artNetOutput = new ArtNetOutput(controllerOctets, numLedsPerStripe); // used to send data to leds
  System.out.print(artNetOutput.describeMapping());
  // Obergrenze 0.3 ist eine Hardwaregrenze, keine Geschmacksfrage: laut
  // Handbuch der Stripes ist bei 10-m-Laengen schon bei Weiss mit
  // Spannungsabfall zu rechnen - voller Pegel (1.0) darf am Regler gar nicht
  // erst erreichbar sein.
  masterLevel = new RemoteControlledFloatParameter("/master/level", 0.1f, 0f, 0.3f);
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
  nodeCalibration = new NodeCalibration(crossingStore, ledNetInfo, listOfNodes,
      numStripes, numLedsPerStripe, dataPath("nodeCrossings.txt"));

  //initialize visual effects
  ledNetworkTransportEffect = new LedNetworkTransportEffect("1", numLeds, numStripes, numLedsPerStripe, ledNetInfo, listOfNodes, oscP5, oscOutput);
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
  } else {
    ledColors = mixer.mix();
  }
  drawLedColorsToCanvas(); // the visuals to be displayed on the led-stripes are drawn into the canvas to be displayed on the screen
  image(canvas, 0, 0, numLedsPerStripe*2, numStripes*10); // display the led-stripes
  if (calibrationMode) {
    fill(255);
    text(nodeCalibration.hudText(), 10, numStripes * 10 + 20);
  }
  // send the visuals over Syphon/Spout to MadMapper. MadMapper can mix the impulses with other visuals/shaders, control brightness (...) with nice UI and send the data out over UDP (Art-Net)
  //server.sendTexture(canvas); //use this on Windows
  //server.sendImage(canvas); //use this on MacOS
  //send data directly to ArtNet Interface withoput MadMapper in between
  artNetOutput.setMasterLevel(masterLevel.getValue());
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
  }
}

void keyReleased() {
  if (key == 'c' || key == 'C') {
    calibrationMode = !calibrationMode;
    println(calibrationMode ? "Kalibriermodus an" : "Kalibriermodus aus");
    nodeCalibration.handleKeyReleased();
    return;
  }
  if (!calibrationMode) {
    return;
  }
  if (key == CODED) {
    nodeCalibration.handleKeyReleased();
  } else {
    nodeCalibration.handleCommand(key);
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
