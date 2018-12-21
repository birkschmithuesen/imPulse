import oscP5.*;
import processing.core.PApplet;
import processing.core.PVector;
import netP5.*;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

///////////////////////////////////////////////////////////
// models a set of activations travelling along the stripes
///////////////////////////////////////////////////////////
public class LedNetworkTransportEffect implements runnableLedEffect, OscMessageSink {


  PApplet papplet;
  String name = "Impulse";
  String id;
  int numLeds, nStripes, nLedsInStripe;
  StripeInfo[] stripeInfos;
  LedInNetInfo[] ledNetInfo;
  LedColor[] bufferLedColors;
  ArrayList <LedNetworkNode> nodes;	
  double lastCyclePos=(double)System.currentTimeMillis()/1000;

  LinkedList<TravellingActivation> activations= new LinkedList<TravellingActivation>();

  //osc out
  OscP5 oscP5;
  NetAddress remoteLocation;


  //settings
  RemoteControlledFloatParameter nodeDeadTime; // Time between two activations of a node
  RemoteControlledFloatParameter impulseDecay; // loss of energy/second
  RemoteControlledIntParameter impulseSpeed; // speed (leds/second)

  RemoteControlledFloatParameter impulseGamma= new RemoteControlledFloatParameter("/net/impulse/color/gamma", 0f, 0.1f, 5f);

  RemoteControlledFloatParameter impulseR;
  RemoteControlledFloatParameter impulseG;
  RemoteControlledFloatParameter impulseB;

  RemoteControlledFloatParameter fadeOutR;
  RemoteControlledFloatParameter fadeOutG;
  RemoteControlledFloatParameter fadeOutB;



  LedNetworkTransportEffect(String _id, int _numLeds, int _nStripes, int _nLedsInStripe, LedInNetInfo[] _ledNetInfo, 	ArrayList <LedNetworkNode> nodes_, OscP5 _oscP5, NetAddress _remoteLocation) {
    id=_id;
    numLeds = _numLeds;
    nStripes = _nStripes;
    nLedsInStripe=_nLedsInStripe;
    bufferLedColors = LedColor.createColorArray(numLeds);
    ledNetInfo=_ledNetInfo;
    nodes=nodes_;
    oscP5=_oscP5;
    remoteLocation=_remoteLocation;

    nodeDeadTime= new RemoteControlledFloatParameter("/net/impulse/nodeDeadTime", 0f, 0.0f, 10);
    impulseDecay= new RemoteControlledFloatParameter("/net/impulse/energyDecay", 0.05f, 0.0001f, 0.5f);
    impulseSpeed= new RemoteControlledIntParameter("/net/impulse/speed", 160, 1, 200);

    impulseR= new RemoteControlledFloatParameter("/net/impulse/color/r", 1, 0, 1); // color of travelling impulse
    impulseG= new RemoteControlledFloatParameter("/net/impulse/color/g", 1, 0, 1); // color of travelling impulse
    impulseB= new RemoteControlledFloatParameter("/net/impulse/color/b", 1, 0, 1); // color of travelling impulse

    fadeOutR= new RemoteControlledFloatParameter("/net/impulse/fadeOut/r", 0.98f, 0f, 1f); // color of travelling impulse
    fadeOutG= new RemoteControlledFloatParameter("/net/impulse/fadeOut/g", 0.97f, 0f, 1f); // color of travelling impulse
    fadeOutB= new RemoteControlledFloatParameter("/net/impulse/fadeOut/b", 0.95f, 0f, 1f); // color of travelling impulse

    OscMessageDistributor.registerAdress("/net/activateNode", this);
    OscMessageDistributor.registerAdress("/net/activateStripe", this);

    OscMessageDistributor.registerAdress("/tube/trigger", this);
  
    //just for the Wisp Session:
    // leave it here for the moment because the sender from MaxMSP gives this addresses
    /*
    OscMessageDistributor.registerAdress("/tube_1/trigger", this);
    OscMessageDistributor.registerAdress("/tube_2/trigger", this);
    OscMessageDistributor.registerAdress("/tube_3/trigger", this);
    OscMessageDistributor.registerAdress("/tube_4/trigger", this);
    OscMessageDistributor.registerAdress("/tube_5/trigger", this);
    OscMessageDistributor.registerAdress("/tube_6/trigger", this);
    OscMessageDistributor.registerAdress("/tube_7/trigger", this);
    OscMessageDistributor.registerAdress("/tube_8/trigger", this);
    */
  }

  public void digestMessage(OscMessage newMessage) {
    if (newMessage.checkAddrPattern("/net/activateNode") &&
      newMessage.arguments().length>0&&
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
          if (forwPos>0&&forwPos<nLeds)activations.add(new TravellingActivation(forwPos, curLedInfo.stripeIndex, impulseSpeed.getValue(), 1f ));
          //do not go back the same stripe:
          int backwPos=nodeLedIdx -1;            
          if (backwPos>0&&backwPos<nLeds)activations.add(new TravellingActivation(backwPos, curLedInfo.stripeIndex, -impulseSpeed.getValue(), 1f));
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
    
    
    //receive a bang on one of the tubes
    if (newMessage.checkAddrPattern("/tube/trigger") && newMessage.arguments().length>0) {
      int theValue=newMessage.get(0).intValue();
      PApplet.println(theValue);
      if (theValue<nStripes)activations.add(new TravellingActivation(theValue*nLedsInStripe, (int)theValue, impulseSpeed.getValue(), 1f ));
    }
    
    // just for the Wisp session : quick and dirty
    // leave it here for the moment because the sender from MaxMSP gives this addresses
    /*
    if (newMessage.checkAddrPattern("/tube_1/trigger") && newMessage.arguments().length>0) {
      float theValue=newMessage.get(0).floatValue();
      if (theValue>0)activations.add(new TravellingActivation(0*nLedsInStripe, 0, impulseSpeed.getValue(), 1f ));
    }
    if (newMessage.checkAddrPattern("/tube_2/trigger") && newMessage.arguments().length>0) {
      float theValue=newMessage.get(0).floatValue();
      if (theValue>0)activations.add(new TravellingActivation(1*nLedsInStripe, 1, impulseSpeed.getValue(), 1f ));
    }
    if (newMessage.checkAddrPattern("/tube_3/trigger") && newMessage.arguments().length>0) {
      float theValue=newMessage.get(0).floatValue();
      if (theValue>0)activations.add(new TravellingActivation(2*nLedsInStripe, 2, impulseSpeed.getValue(), 1f ));
    }
    if (newMessage.checkAddrPattern("/tube_4/trigger") && newMessage.arguments().length>0) {
      float theValue=newMessage.get(0).floatValue();
      if (theValue>0)activations.add(new TravellingActivation(3*nLedsInStripe, 3, impulseSpeed.getValue(), 1f ));
    }
    if (newMessage.checkAddrPattern("/tube_5/trigger") && newMessage.arguments().length>0) {
      float theValue=newMessage.get(0).floatValue();
      if (theValue>0)activations.add(new TravellingActivation(4*nLedsInStripe, 4, impulseSpeed.getValue(), 1f ));
    }
    if (newMessage.checkAddrPattern("/tube_6/trigger") && newMessage.arguments().length>0) {
      float theValue=newMessage.get(0).floatValue();
      if (theValue>0)activations.add(new TravellingActivation(5*nLedsInStripe, 5, impulseSpeed.getValue(), 1f ));
    }
    if (newMessage.checkAddrPattern("/tube_7/trigger") && newMessage.arguments().length>0) {
      float theValue=newMessage.get(0).floatValue();
      if (theValue>0)activations.add(new TravellingActivation(6*nLedsInStripe, 6, impulseSpeed.getValue(), 1f ));
    }
    if (newMessage.checkAddrPattern("/tube_8/trigger") && newMessage.arguments().length>0) {
      float theValue=newMessage.get(0).floatValue();
      if (theValue>0)activations.add(new TravellingActivation(7*nLedsInStripe, 7, impulseSpeed.getValue(), 1f ));
    }
    */
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
      ledIdxPos=ledIdxPos_;
      stripeIdx=stripeIdx_;
      speed=speed_;
      energy=energy_;
    }

    int getLedIndex() {
      return (int)(ledIdxPos+0.5f); // global led position
    }
    float ledIdxPos; // absolute led position - used for mapping to led buffer
    int stripeIdx; // stripe the activation was created on
    float speed; // [leds/second] also encodes direction in sign
    float energy; // some measure of strength
  }
  
  //represents fillers needed when high travelling speeds lead to skipping some leds in each frame
  public class TravellingActivationFiller extends TravellingActivation {
    TravellingActivationFiller(float ledIdxPos_, int stripeIdx_, float speed_, float energy_){
      super(ledIdxPos_, stripeIdx_, speed_, energy_);
    }
  }

  //simulate one time step
  public LedColor[] drawMe() { 
    float spotR=impulseR.getValue(); 
    float spotG=impulseG.getValue();
    float spotB=impulseB.getValue();
    float gamma =impulseGamma.getValue();

    //parameters
    double currentTime=(double)System.currentTimeMillis()/1000;
    float timeStep=(float) (currentTime-lastCyclePos);
    lastCyclePos=currentTime;
    float speed=impulseSpeed.getValue(); 
    float energyLoss=impulseDecay.getValue();
    //iterate through activations and build a new list of activations in the meanwhile.
    LinkedList<TravellingActivation> newActivations=new LinkedList<TravellingActivation>();

    for (TravellingActivation curActivation : activations) {
      int prevActivationLedIdx=curActivation.getLedIndex();
      // let each activation travel a bit in it's direction
      curActivation.ledIdxPos+=curActivation.speed*timeStep;
      // if the activation hasn't fallen off the end of the stripe...
      int activationLedIdx=curActivation.getLedIndex(); // global led position
      if (activationLedIdx - prevActivationLedIdx >= 2) System.out.println("LED skipped, LedId1 - LedId2 = " + (activationLedIdx - prevActivationLedIdx));
      for(int curActivationLedIdx = prevActivationLedIdx; curActivationLedIdx <= activationLedIdx; curActivationLedIdx++){
        if (activationDiedOrEncounteredNode(activationLedIdx, curActivation, newActivations, currentTime, energyLoss)) break;
        if(curActivationLedIdx == activationLedIdx){
          newActivations.add(curActivation);
        } else {
          LedInNetInfo curLedInfo=ledNetInfo[curActivationLedIdx];
          newActivations.add(new TravellingActivation(curActivationLedIdx, curLedInfo.stripeIndex, curActivation.speed, curActivation.energy ));
        }
      }
    }
    
    activations=newActivations;

    //draw all
    LedColor.mult (bufferLedColors, new LedColor(fadeOutR.getValue(), fadeOutG.getValue(), fadeOutB.getValue()));
    for (LedNetworkTransportEffect.TravellingActivation curActivation : activations) {
      int curLedIndex=curActivation.getLedIndex(); // global led position
      float fade=(float)Math.pow(curActivation.energy, gamma);
      bufferLedColors[curLedIndex].set(spotR*fade, spotG*fade, spotB*fade);
      //bufferLedColors[curLedIndex].set(1, 0, 0);
      //if the travelling activation is a filler remove it
      if(curActivation.getClass() == TravellingActivationFiller.class) activations.remove(curActivation);
    }

    return bufferLedColors;
  }
  
  private boolean activationDiedOrEncounteredNode(Integer activationLedIdx, TravellingActivation curActivation, LinkedList<TravellingActivation> newActivations, double currentTime, float energyLoss){
    int nLeds=ledNetInfo.length;
     // should the activation survive this round?
      if (
        activationLedIdx>=0&&activationLedIdx<=(nLeds-1)&& //ledIndex is valid
        ledNetInfo[activationLedIdx].stripeIndex==curActivation.stripeIdx&& // activation is in it's original stripe
        curActivation.energy>0
        ) {
        //if activation hits a stripe crossing, create a new activation for each of the branches
        if (ledNetInfo[activationLedIdx].partOfNode!=null) {
          LedNetworkNode hitNode=ledNetInfo[activationLedIdx].partOfNode;
          // only multiply at nodes that have not been active for a while
          if (currentTime-hitNode.lastActivationTime>nodeDeadTime.getValue()) {

            hitNode.lastActivationTime=currentTime;
            //send osc Notification
            OscMessage myMessage = new OscMessage("/net/hitNode");
            myMessage.add(hitNode.id);
            myMessage.add(curActivation.energy);
            //myMessage.add(hitNode.position.x);
            //myMessage.add(hitNode.position.y);
            //myMessage.add(hitNode.position.z);
            oscP5.send(myMessage, remoteLocation);


            float nActivations=hitNode.ledIndices.size();
            for (Integer nodeLedIdx : hitNode.ledIndices) {
              LedInNetInfo curLedInfo=ledNetInfo[nodeLedIdx]; //which stripe are we on?

              float childEnergy=curActivation.energy/nActivations/2.0f-energyLoss;

              int jump; // jump one led to avoid activating the same node over and over again
              if (curActivation.speed>0)jump=1;
              else jump=-1;
              //  activation spreads in boths directions
              int forwPos=nodeLedIdx +jump;           
              if (forwPos>0&&forwPos<nLeds)newActivations.add(new TravellingActivation(forwPos, curLedInfo.stripeIndex, curActivation.speed, childEnergy ));
              //do not go back the same stripe:
              if (nodeLedIdx!=activationLedIdx) {
                int backwPos=nodeLedIdx -jump;            
                if (backwPos>0&&backwPos<nLeds)newActivations.add(new TravellingActivation(backwPos, curLedInfo.stripeIndex, -curActivation.speed, childEnergy));
              }
            }
          }
          return true;
        } else {
          //nothing special has happened, keep the activation for next round.
          return false;
        }
     }
     return true;
  }

  void createRandomActivation() {
    int ledIdx=0;//papplet.floor(papplet.random(ledNetInfo.length));
    activations.add(new TravellingActivation(ledIdx, ledNetInfo[ledIdx].stripeIndex, 20, 1));
  }


  public String getName() {
    return name;
  }
}
