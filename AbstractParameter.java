import netP5.*;
import oscP5.*;
import processing.core.*;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.io.IOException;
import java.util.*;


//////////////////////////////////////////////////////////////////////////
// a class to store any incoming value. not considering the value type, any variable has a changeFlag parameter. the different data types (int,float) will extend from this class
//////////////////////////////////////////////////////////////////////////
class AbstractParameter {
	boolean changeFlag;

	public boolean getChangedSinceReset() { ///< find out if the setting content was changed since "resetChange" was called the last time;
		return changeFlag;
	}

	public void resetChanged() {
		changeFlag=false;
	}
}


//////////////////////////////////////////////////////////////////////////
// an interface to implement for the different RemoteControlls, where all incoming messages will arrive
//////////////////////////////////////////////////////////////////////////
interface OscMessageSink {
	void digestMessage(OscMessage newMessage);
	void writeToStream(DataOutputStream outStream);    //write a description of the setting into a stream. used to build parameter dump files
}


//////////////////////////////////////////////////////////////////////////
// distributes the incoming messages 
//////////////////////////////////////////////////////////////////////////
class OscMessageDistributor {
	static ArrayList<OscMessage> queuedMessages=new ArrayList<OscMessage>(); // messages accumulated from other threads


	static HashSet<OscMessageSink> allInstances=new HashSet<OscMessageSink>();        ///< freshly constructed instances are added to this list to enable easy handling of message distribution

	//register any MessageSink in the distributer
	public static void registerAdress(String _adress, OscMessageSink _oscMessageSink) { 
		allInstances.add(_oscMessageSink);
	}

	//distributes the incoming message to all Instances
	public static void distributeMessages() {
		synchronized(queuedMessages) {
			for(OscMessage curMessage:queuedMessages) {
				for (OscMessageSink theOscMessageSink:allInstances) {
					theOscMessageSink.digestMessage(curMessage);
				}
			}
			queuedMessages.clear();
		}
	}
	//distributes the incoming message to all Instances
	public static void queueMessage(OscMessage message) {
		synchronized(queuedMessages) {
			queuedMessages.add(message);
		}
	}

	
	//writes the list of addresses and actual values to a stream
	public static void dumpParameterInfo(DataOutputStream outStream) {
		for (OscMessageSink theOscMessageSink:allInstances) {
			theOscMessageSink.writeToStream(outStream);
		}
	}

}

//*************************************************************************
//*************************************************************************
// THE FOLLOWING HANDLES INCOMING FLOAT MESSAGES
//*************************************************************************
//*************************************************************************

//////////////////////////////////////////////////////////////////////////
// If the Incoming Value should be an Float, this class handels the value
//////////////////////////////////////////////////////////////////////////
class RemoteControlledFloatParameter extends FloatParameter implements OscMessageSink { //
	String oscAdress;
	RemoteControlledFloatParameter(String _oscAdress, float _startValue, float _minValue, float _maxValue) {
		super(_startValue, _minValue, _maxValue);  //super calls the constructor from the class, that it extends from
		OscMessageDistributor.registerAdress(_oscAdress, this);
		oscAdress=_oscAdress;
	}
	// checks the adress pattern and data type of the incoming message, and sets the currentValue of the receiving instance
	public void digestMessage(OscMessage newMessage) {
		if (newMessage.checkAddrPattern(oscAdress) && newMessage.arguments().length>0) {
			if (newMessage.getTypetagAsBytes()[0]=='i') {
				float theValue=newMessage.get(0).intValue();
				theValue=PApplet.constrain(theValue, minValue, maxValue);
				setValue(theValue);
			}
			if (newMessage.getTypetagAsBytes()[0]=='f') {
				float theValue=newMessage.get(0).floatValue();
				theValue=PApplet.constrain( PApplet.map(theValue, 0,1,minValue, maxValue),minValue, maxValue);
				setValue(theValue);
			}
		}
	}
	// the method writes the names of the current remote Controlled Values and the currentValues itself to a file on the harddrive. This file is used to configurate the remote programm and to save the current setting 
	public void writeToStream(DataOutputStream outStream) {
		String outData="float"+"\t"+oscAdress+"\t"+"space for descripiton"+"\t"+getValue()+"\t"+minValue+"\t"+maxValue+"\n";  
		try {
			outStream.writeBytes(outData);
		}  
		catch (
				IOException e) {
			System.err.println("Could not write to file"+e);
		}
	}
}


//////////////////////////////////////////////////////////////////////////
// a class to store the Float values
//////////////////////////////////////////////////////////////////////////
class FloatParameter extends AbstractParameter { //can save, get and set an Floateger number 
	protected float currentValue;
	protected float minValue, maxValue;

	FloatParameter(float _startValue, float _minValue, float _maxValue) {
		maxValue=_maxValue;
		minValue=_minValue;
		currentValue=_startValue;
	}
	public float getValue() {
		return currentValue;
	}
	//for any value change, the changeFlag will be set true 
	public void setValue(float _newValue) {
		if (_newValue!=currentValue) {
			changeFlag=true;
			currentValue=_newValue;
		}
	}
}


//*************************************************************************
//*************************************************************************
// THE FOLLING HANDLES INCOMING INT MESSAGES
//*************************************************************************
//*************************************************************************

//////////////////////////////////////////////////////////////////////////
// If the Incoming Value should be an Int, this class handels the value
//////////////////////////////////////////////////////////////////////////
class RemoteControlledIntParameter extends IntParameter implements OscMessageSink { //
	String oscAdress;
	RemoteControlledIntParameter(String _oscAdress, int _startValue, int _minValue, int _maxValue) {
		super(_startValue, _minValue, _maxValue);  //super calls the constructor from the class, that it extends from
		OscMessageDistributor.registerAdress(_oscAdress, this);
		oscAdress=_oscAdress;
	}
	// checks the adress pattern and data type of the incoming message, and sets the currentValue of the receiving instance
	public void digestMessage(OscMessage newMessage) {
		if (newMessage.checkAddrPattern(oscAdress) && newMessage.arguments().length>0) {
			if (newMessage.getTypetagAsBytes()[0]=='i') {
				float theValue=newMessage.get(0).intValue();
				theValue=PApplet.constrain(theValue, minValue, maxValue);
				setValue((int)theValue);
			}
			if (newMessage.getTypetagAsBytes()[0]=='f') {
				float theValue=newMessage.get(0).intValue();
				theValue=PApplet.constrain( PApplet.map(theValue, 0f,1f,minValue, maxValue),minValue, maxValue);
				setValue((int)theValue);
			}
		}
	}
	// the method writes the names of the current remote Controlled Values and the currentValues itself to a file on the harddrive. This file is used to configurate the remote programm and to save the current setting 
	public void writeToStream(DataOutputStream outStream) {
		String outData="int"+"\t"+oscAdress+"\t"+"space for descripiton"+"\t"+getValue()+"\t"+minValue+"\t"+maxValue+"\n";  
		try {
			outStream.writeBytes(outData);
		}  
		catch (
				IOException e) {
			System.err.println("Could not write to file"+e);
		}
	}
}


//////////////////////////////////////////////////////////////////////////
// a class to store the Int values
//////////////////////////////////////////////////////////////////////////
class IntParameter extends AbstractParameter { //can save, get and set an Integer number 
	protected int currentValue;
	protected int maxValue, minValue;

	IntParameter(int _startValue, int _minValue, int _maxValue) {
		maxValue=_maxValue;
		minValue=_minValue;
		currentValue=_startValue;
	}
	public int getValue() {
		return currentValue;
	}
	//for any value change, the changeFlag will be set true 
	public void setValue(int _newValue) {
		if (_newValue!=currentValue) {
			changeFlag=true;
			currentValue=_newValue;
		}
	}
}


//*************************************************************************
//*************************************************************************
// THE FOLLOWING HANDLES INCOMING COLOR MESSAGES (HUE, SATURATION, BRIGHTNESS)
//*************************************************************************
//*************************************************************************

class RemoteControlledColorParameter extends ColorParameter implements OscMessageSink { //
	String oscAdress;
	RemoteControlledColorParameter(String _oscAdress, float _startHue, float _startSaturation, float _startBrightness) {
		super(_startHue, _startSaturation, _startBrightness);  //super calls the constructor from the class, that it extends from
		OscMessageDistributor.registerAdress(_oscAdress+"/Hue", this);
		OscMessageDistributor.registerAdress(_oscAdress+"/Satn", this);
		OscMessageDistributor.registerAdress(_oscAdress+"/Bright", this);
		oscAdress=_oscAdress;
	}
	// checks the adress pattern and data type of the incoming message, and sets the currentValue of the receiving instance
	public void digestMessage(OscMessage newMessage) {
		//when the incoming value is the hue
		if (newMessage.checkAddrPattern(oscAdress+"/Hue") && newMessage.arguments().length>0) {
			if (newMessage.getTypetagAsBytes()[0]=='f') {
				float theValue=newMessage.get(0).floatValue();
				theValue=PApplet.constrain(theValue, 0, 1);
				setHue(theValue);
			}
		}
		//when the incoming value is the Saturation
		if (newMessage.checkAddrPattern(oscAdress+"/Sat") && newMessage.arguments().length>0) {
			if (newMessage.getTypetagAsBytes()[0]=='f') {
				float theValue=newMessage.get(0).floatValue();
				theValue=PApplet.constrain(theValue, 0, 1);
				setSaturation(theValue);
			}
		}
		//when the incoming value is the Brightness
		if (newMessage.checkAddrPattern(oscAdress+"/Bright") && newMessage.arguments().length>0) {
			if (newMessage.getTypetagAsBytes()[0]=='f') {
				float theValue=newMessage.get(0).floatValue();
				theValue=PApplet.constrain(theValue, 0, 1);
				setBrightness(theValue);
			}
		}

	}
	// the method writes the names of the current remote Controlled Values and the currentValues itself to a file on the harddrive. This file is used to configurate the remote programm and to save the current setting 
	public void writeToStream(DataOutputStream outStream) {
		// Writes the Hue-Fader
		String outData="float"+"\t"+oscAdress+"/Hue"+"\t"+"space for descripiton"+"\t"+currentHue+"\t"+0+"\t"+1+"\n";  
		try {
			outStream.writeBytes(outData);
		}  
		catch (
				IOException e) {
			System.err.println("Could not write to file"+e);
		}
		// Writes the Saturation-Fader
		outData="float"+"\t"+oscAdress+"/Sat"+"\t"+"space for descripiton"+"\t"+currentSaturation+"\t"+0+"\t"+1+"\n";  
		try {
			outStream.writeBytes(outData);
		}  
		catch (
				IOException e) {
			System.err.println("Could not write to file"+e);
		}
		// Writes the Brightness-Fader
		outData="float"+"\t"+oscAdress+"/Bright"+"\t"+"space for descripiton"+"\t"+currentBrightness+"\t"+0+"\t"+1+"\n";  
		try {
			outStream.writeBytes(outData);
		}  
		catch (
				IOException e) {
			System.err.println("Could not write to file"+e);
		}
	}
}


//////////////////////////////////////////////////////////////////////////
// a class to store the Color values
//////////////////////////////////////////////////////////////////////////
class ColorParameter extends AbstractParameter { //can save, get and set an Floateger number 
	protected float currentHue, currentSaturation, currentBrightness;

	ColorParameter(float _startHue, float _startSaturation, float _startBrightness) {
		currentHue=_startHue;
		currentSaturation=_startSaturation;
		currentBrightness=_startBrightness;
	}
	public LedColor getColor() {
		LedColor theColor = new LedColor();
		theColor.setFromHSB(currentHue, currentSaturation, currentBrightness);
		return theColor;
	}
	//for any value change, the changeFlag will be set true 
	public void setHue(float _newHue) {
		if (_newHue!=currentHue) {
			changeFlag=true;
			currentHue=_newHue;
		}
	}
	public void setSaturation(float _newSaturation) {
		if (_newSaturation!=currentSaturation) {
			changeFlag=true;
			currentSaturation=_newSaturation;
		}
	}
	public void setBrightness(float _newBrightness) {
		if (_newBrightness!=currentBrightness) {
			changeFlag=true;
			currentBrightness=_newBrightness;
		}
	}
}
