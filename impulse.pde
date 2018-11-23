import netP5.*;
import oscP5.*;
//import codeanticode.syphon.*;

import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;


//////////////////////////////////////////////////////////////////////////
// To Do List
//////////////////////////////////////////////////////////////////////////
/*
- define crossings between two led stripes (nodes) manually 
 => in LedStripeNetworks.java Line 60 function buildClusterInfo
 - solve problem when traveling speed of impulses is higher then framerate (when impulses jumps over an led from one frame to the next, the led is not light up)
 */


// canvs is a grafic buffer for the texture to send over syphon
// width: length of led stripes
// height: number of stripes 
PGraphics canvas;
//SyphonServer server;

OscP5 oscP5;
NetAddress oscOutput;

// an array of LedColor objects. One for each LED
LedColor[] ledColors;
//can be usefull to gather information for each LED
LedInStripeInfo[] stripeInfos;
LedInNetInfo[] ledNetInfo;
// create an array with all the nodes/crossings
ArrayList <LedNetworkNode> listOfNodes; 

// the stripe configuration
int numStripes = 8;
int numLedsPerStripe = 600;
int numLeds = numStripes * numLedsPerStripe;
StripeConfigurator stripeConfiguration; 

// a mixer object where all visuals come together and are merged
Mixer mixer;

//visual generators
LedNetworkTransportEffect ledNetworkTransportEffect;
LedNetworkNodeEffects ledNetworkNodeEffects;

int counter=0;

void setup() { 
  size(1200, 80, P3D);
  frameRate(120);
  //opens the port to receive OSC
  oscP5 = new OscP5(this, 8001);
  //when a node is activated an osc impuls is send to Ableton Live
  oscOutput = new NetAddress("192.168.111.100", 8002);
  
  // Create syhpon server to send frames out.
  //server = new SyphonServer(this, "Lightstrument");
  // create stripe information
  stripeConfiguration = new StripeConfigurator(numStripes, numLedsPerStripe); // used to generate per led info.

  // use the canvas to create the visuals to send over syphon
  // the size depends on the stripe configuration
  canvas = createGraphics(numLedsPerStripe, numStripes, P3D);

  ledColors = LedColor.createColorArray(numLeds);        // build a color buffer with the length of the position file
  stripeInfos = stripeConfiguration.builtStripeInfo();   
  ledNetInfo = LedInNetInfo.buildNetInfo(numStripes, numLedsPerStripe); //create an Array with data for each LED if they are part of a node
  listOfNodes = LedInNetInfo.buildClusterInfo(ledNetInfo);  // all sets of Leds that are on different stripes but close to each other

  //initialize visual effects
  ledNetworkTransportEffect = new LedNetworkTransportEffect("1", numLeds, numStripes, numLedsPerStripe, ledNetInfo, listOfNodes, oscP5, oscOutput);
  ledNetworkNodeEffects = new LedNetworkNodeEffects("1", numLeds, ledNetInfo, listOfNodes);

  mixer = new Mixer(numLeds);
  mixer.addEffect(ledNetworkTransportEffect);
  mixer.addEffect(ledNetworkNodeEffects);
  mixer.addEffect(new TemplateEffect("1", numLeds));

  //to save the osc-adresses
  try {
    DataOutputStream dataOut = new DataOutputStream(new FileOutputStream(dataPath("remoteSettings.txt")));
    OscMessageDistributor.dumpParameterInfo(dataOut);
  } 
  catch (FileNotFoundException e) {
    println("file not found");
  }
}

void draw() {
  OscMessageDistributor.distributeMessages();
  createRandomPipeTrigger();  // for test purpose create random activations (instead of hitting a pipe)
  ledColors=mixer.mix(); // calculate the visuals  
  drawLedColorsToCanvas(); // the visuals to be displayed on the led-stripes are drawn into the canvas to be displayed on the screen
  image(canvas, 0, 0, numLedsPerStripe*2, numStripes*10); // display the led-stripes
  //server.sendImage(canvas); // send the visuals over Syphon to MadMapper. MadMapper can mix the impulses with other visuals/shaders, control brightness (...) with nice UI and send the data out over UDP (Art-Net)
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
    myMessage.add((int)random(numStripes));
    NetAddress localhost = new NetAddress("127.0.0.1", 8001);
    oscP5.send(myMessage, localhost);
  }
  counter++;
}
