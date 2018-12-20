import netP5.*;
import oscP5.*;
import controlP5.*;
//import codeanticode.syphon.*;

import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


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
LedStripeFullActivationEffect ledStripeFullActivationEffect;

int counter=0;

// keep track of continuous key presses and control the LedStripeFullActivationEffect

enum StripeChangeMode {
  CYCLE_BLACK_STRIPE, CONTROL_BLACK_STRIPE_LEDS, CYCLE_BRIGHT_STRIPES, CONTROL_BRIGHT_STRIPE_LEDS,
  ACTIVATE_ALL_BRIGHT_STRIPES;
}
StripeChangeMode stripeChangeMode = StripeChangeMode.CYCLE_BLACK_STRIPE;

// GUI for LedStripeFullActivationEffect
ControlP5 cp5;


void setup() { 
  size(1400, 120, P3D);
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
  listOfNodes = LedInNetInfo.loadListOfNodes(ledNetInfo);  // all sets of Leds that are on different stripes but close to each other

  //initialize visual effects
  ledNetworkTransportEffect = new LedNetworkTransportEffect("1", numLeds, numStripes, numLedsPerStripe, ledNetInfo, listOfNodes, oscP5, oscOutput);
  ledNetworkNodeEffects = new LedNetworkNodeEffects("1", numLeds, ledNetInfo, listOfNodes);
  ledStripeFullActivationEffect = new LedStripeFullActivationEffect("1", stripeInfos, numStripes);

  mixer = new Mixer(numLeds);
  mixer.addEffect(ledNetworkTransportEffect);
  mixer.addEffect(ledNetworkNodeEffects);
  mixer.addEffect(ledStripeFullActivationEffect);

  //to save the osc-adresses
  try {
    DataOutputStream dataOut = new DataOutputStream(new FileOutputStream(dataPath("remoteSettings.txt")));
    OscMessageDistributor.dumpParameterInfo(dataOut);
  } 
  catch (FileNotFoundException e) {
    println("file not found");
  }
  
  //add GUI
  cp5 = new ControlP5(this);
  List l = Arrays.asList(StripeChangeMode.CYCLE_BLACK_STRIPE.name(), StripeChangeMode.CONTROL_BLACK_STRIPE_LEDS.name(), 
    StripeChangeMode.CYCLE_BRIGHT_STRIPES.name(), StripeChangeMode.CONTROL_BRIGHT_STRIPE_LEDS.name(),
    StripeChangeMode.ACTIVATE_ALL_BRIGHT_STRIPES.name());
  /* add a ScrollableList, by default it behaves like a DropdownList */
  cp5.addScrollableList("dropdown")
     .setPosition(1200, 0)
     .setSize(200, 100)
     .setBarHeight(20)
     .setItemHeight(20)
     .addItems(l)
     ;
}

void draw() {
  OscMessageDistributor.distributeMessages();
  createRandomPipeTrigger();  // for test purpose create random activations (instead of hitting a pipe)
  ledColors=mixer.mix(); // calculate the visuals  
  drawLedColorsToCanvas(); // the visuals to be displayed on the led-stripes are drawn into the canvas to be displayed on the screen
  image(canvas, 0, 0, numLedsPerStripe*2, numStripes*10); // display the led-stripes
  //server.sendImage(canvas); // send the visuals over Syphon to MadMapper. MadMapper can mix the impulses with other visuals/shaders, control brightness (...) with nice UI and send the data out over UDP (Art-Net)
  ledStripeFullActivationEffect.changeStripe();
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

void keyPressed() {
  if (key == CODED) {
    if (keyCode == UP) {
      ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.INCREASE_BRIGHTNESS);
    } else if (keyCode == DOWN) {
      ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.DECREASE_BRIGHTNESS);
    } else if (keyCode == RIGHT) {
      if(stripeChangeMode == StripeChangeMode.CONTROL_BLACK_STRIPE_LEDS){
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.ACTIVATE_NEXT_STRIPE_LED);
      } else if (stripeChangeMode == StripeChangeMode.CYCLE_BLACK_STRIPE) {
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.NEXT_BLACK_STRIPE);
      } else if (stripeChangeMode == StripeChangeMode.CYCLE_BRIGHT_STRIPES) {
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.NEXT_BRIGHT_STRIPE);
      } else if (stripeChangeMode == StripeChangeMode.CONTROL_BRIGHT_STRIPE_LEDS) {
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.ACTIVATE_NEXT_BRIGHT_STRIPE_LED);
      }
    } else if (keyCode == LEFT) {
      if(stripeChangeMode == StripeChangeMode.CONTROL_BLACK_STRIPE_LEDS){
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.DEACTIVATE_LAST_STRIPE_LED);
      } else if (stripeChangeMode == StripeChangeMode.CYCLE_BLACK_STRIPE) {
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.PREV_BLACK_STRIPE);
      } else if (stripeChangeMode == StripeChangeMode.CYCLE_BRIGHT_STRIPES) {
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.PREV_BRIGHT_STRIPE);
      } else if (stripeChangeMode == StripeChangeMode.CONTROL_BRIGHT_STRIPE_LEDS) {
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.DEACTIVATE_LAST_BRIGHT_STRIPE_LED);
      }
    }
  }
}

void keyReleased() {
  if (key == CODED) {
    ledStripeFullActivationEffect.stripeChange = LedStripeFullActivationEffect.StripeChange.NONE;
    
  } else if(key == ENTER || key == RETURN) {
      ledStripeFullActivationEffect.saveCurrentNodeCrossing();
  } else if(key == 's') {
    try {
      ledStripeFullActivationEffect.saveNodeCrossingsToFile();
    } catch (IOException e) {
      println(e);
    }
  } else if(key == 'n') {
    ledStripeFullActivationEffect.toggleShowNodes();
  }
}

//ControlP5 callback - method name must be the same as the string parameter of cp5.addScrollableList()
void dropdown(int index) {
  String selected = (String) cp5.get(ScrollableList.class, "dropdown").getItem(index).get("text");
  stripeChangeMode = StripeChangeMode.valueOf(selected);
  if(stripeChangeMode == StripeChangeMode.ACTIVATE_ALL_BRIGHT_STRIPES){
    ledStripeFullActivationEffect.resetCurrentStripeConfig();
  }
}
