import java.util.ArrayList;

import processing.core.PApplet;

class Mixer {	
	PApplet papplet;
  LedColor[] outputBufferLedColors;
  // Array of effects for the mixer and their mixer specific values
  ArrayList<runnableLedEffect> effectList;
  ArrayList<RemoteControlledFloatParameter> opacityList;
  RemoteControlledFloatParameter traceControl;
  float trace;
  int nLeds;

  public Mixer(int _nLeds) {
    nLeds = _nLeds;
    outputBufferLedColors=LedColor.createColorArray(nLeds); //creates a new ledColorBuffer as output of the mixer
    effectList= new ArrayList<runnableLedEffect> ();
    opacityList=new ArrayList<RemoteControlledFloatParameter>();
    traceControl=new RemoteControlledFloatParameter("Master/trace", 0.23f,0f, 1f);
  }

  //adds an effect to the effect ArrayList
  public void addEffect(runnableLedEffect _theEffect) {
    effectList.add(_theEffect);
    opacityList.add(new RemoteControlledFloatParameter("Master/"+opacityList.size()+"/opacity/"+papplet.str(opacityList.size())+"."+_theEffect.getName(), 0.7f, 0,1f));

  }

  public LedColor[] mix() {
   // LedColor.setAllBlack(outputBufferLedColors);
	  trace=traceControl.getValue();
	  LedColor.mult(outputBufferLedColors,trace);
    for (int i=0; i<effectList.size(); i++) {
      //copies the effects output with the remote opacity and blendmode on the mixerbuffer
      LedColor[] effectOutput =LedColor.createColorArray(nLeds); //creates a new ledColorBuffer to add filter without breaking the original colorbuffer
      effectOutput=effectList.get(i).drawMe(); //gets the output Buffer of the effect in the effectArray
      /*
      MISSING:: TURN HUE AND SATURATION OF LedColor[] effectOutput
      */
      LedColor.LedAlphaMode blendMode=LedColor.LedAlphaMode.ADD; //sets the blendmode, how the effect output is mixed with the other effects
      float opacity=opacityList.get(i).getValue();
      for (int j=0; j<nLeds; j++) {
        outputBufferLedColors[j].mixWithAlpha(effectOutput[j], blendMode, opacity);
      }
    }
    //LedColor.substract(outputBufferLedColors, new LedColor(0.05,0.002,0.03)); //colored master fade out
    return outputBufferLedColors;
  }
}


// every effect implements the run interface, so that they can easiely be summerised in an array within the mixer
interface runnableLedEffect {
 // String name;
  LedColor[] drawMe();
  String getName();
}
