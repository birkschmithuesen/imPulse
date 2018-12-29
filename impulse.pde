import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import netP5.*;
import oscP5.*;
import controlP5.*;

//import spout.*; //use this on Windows
import codeanticode.syphon.*; //use this on MacOS


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
//Spout server; //use this on Windows
SyphonServer server; //use this on MacOS

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
  ACTIVATE_ALL_BRIGHT_STRIPES, SET_SAME_STRIPE_FIRST_NODE, SET_SAME_STRIPE_SECOND_NODE;
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
  oscOutput = new NetAddress("2.0.0.2", 8002);//("192.168.88.253", 8002);
  
  // Create Syhpon/Spout server to send frames out directly shared on gpu.
  //server = new Spout(this); //use this on Windows
  //server.createSender("Lightstrument"); //use this on Windows
  server = new SyphonServer(this, "Lightstrument"); //use this on MacOs
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
  //mixer.addEffect(ledStripeFullActivationEffect);

  //to save the osc-adresses
  try {
    System.out.println(dataPath("remoteSettings.txt"));
    DataOutputStream dataOut = new DataOutputStream(new FileOutputStream("C:\\Users\\VideoServer\\Desktop\\impulsPlayground\\imPulse\\data\\remoteSettings.txt"));
    OscMessageDistributor.dumpParameterInfo(dataOut);
  } 
  catch (FileNotFoundException e) {
    println("file not found");
  }
  
  //add GUI
  cp5 = new ControlP5(this);
  List l = Arrays.asList(StripeChangeMode.CYCLE_BLACK_STRIPE.name(), StripeChangeMode.CONTROL_BLACK_STRIPE_LEDS.name(), 
    StripeChangeMode.CYCLE_BRIGHT_STRIPES.name(), StripeChangeMode.CONTROL_BRIGHT_STRIPE_LEDS.name(),
    StripeChangeMode.ACTIVATE_ALL_BRIGHT_STRIPES.name(), StripeChangeMode.SET_SAME_STRIPE_FIRST_NODE.name(),
    StripeChangeMode.SET_SAME_STRIPE_SECOND_NODE.name());
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
  // send the visuals over Syphon/Spout to MadMapper. MadMapper can mix the impulses with other visuals/shaders, control brightness (...) with nice UI and send the data out over UDP (Art-Net)
  //server.sendTexture(canvas); //use this on Windows
  server.sendImage(canvas); //use this on MacOS
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
    myMessage.add((int)random(1,numStripes));
    myMessage.add((float)random(0.6, 1));
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
      } else if(stripeChangeMode == StripeChangeMode.SET_SAME_STRIPE_FIRST_NODE) {
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.ACTIVATE_NEXT_SAME_STRIPE_LED_FIRST_NODE);
      } else if(stripeChangeMode == StripeChangeMode.SET_SAME_STRIPE_SECOND_NODE){
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.ACTIVATE_NEXT_SAME_STRIPE_LED_SECOND_NODE);
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
      } else if(stripeChangeMode == StripeChangeMode.SET_SAME_STRIPE_FIRST_NODE) {
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.DEACTIVATE_LAST_SAME_STRIPE_LED_FIRST_NODE);
      } else if(stripeChangeMode == StripeChangeMode.SET_SAME_STRIPE_SECOND_NODE){
        ledStripeFullActivationEffect.setStripeChange(LedStripeFullActivationEffect.StripeChange.DEACTIVATE_LAST_SAME_STRIPE_LED_SECOND_NODE);
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
  } else if(key == 'f') {
    ledStripeFullActivationEffect.cycleSpeeds();
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
