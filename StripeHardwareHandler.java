import netP5.*;
import oscP5.*;
import processing.core.PApplet;
import ch.bildspur.artnet.*;

// ----------------------------------------------------
//    reads the calibration data and writes an array with information about the led in the stripe 
// ----------------------------------------------------

class StripeConfigurator {
  int numStripes, numLedsPerStripe, numStripesPerController, numLeds;

  public StripeConfigurator(int _numStripes, int _numLedsPerStripe, int _numStripesPerController) {
    numStripes = _numStripes;
    numLedsPerStripe = _numLedsPerStripe;
    numStripesPerController = _numStripesPerController;
    numLeds = numStripes * numLedsPerStripe;
  }

  // if used without direct Art-Net output, we don't need to know the controller
  // configuration
  public StripeConfigurator(int _numStripes, int _numLedsPerStripe) {
    numStripes = _numStripes;
    numLedsPerStripe = _numLedsPerStripe;
    numLeds = numStripes * numLedsPerStripe;
  }

  // build an array of information (from the Class LedInStripeInfo) about the
  // position of the led in the physical stripe
  public LedInStripeInfo[] builtStripeInfo() {
    LedInStripeInfo[] ledInStripeInfos = new LedInStripeInfo[numLeds];
    for (int i = 0; i < numStripes; i++) {
      for (int j = 0; j < numLedsPerStripe; j++) {
        int prevIndex;
        if (j == 0) {
          prevIndex = -1; // -1 aka physical beginning of the stripe
        } else {
          prevIndex = (j + (i * numLedsPerStripe)) - 1;
        }

        int nextIndex;
        if (j == numLedsPerStripe - 1) {
          nextIndex = -1; // -1 aka physical ending of the stripe
        } else {
          nextIndex = (j + (i * numLedsPerStripe)) + 1;
        }

        ledInStripeInfos[i * numLedsPerStripe + j] = new LedInStripeInfo(prevIndex, nextIndex, j, i, 
          numLedsPerStripe);
      }
    }
    return ledInStripeInfos;
  }
}

// ----------------------------------------------------
// handles pushing the led color over ArtNet to the Stripe-Controllers
// ----------------------------------------------------

class ArtNetSender {
  PApplet parent;
  int numUniverses, numUniversesPerController, numController, numLeds, startIP, numPixelPerController;
  String ipPrefix;

  ArtNetClient artnet;
  byte[] dmxData = new byte[512];
  ;
  NetAddress myRemoteLocation;

  public ArtNetSender(StripeConfigurator _stripeConfiguration, String _ipPrefix, int _startIp) {
    // create artnet client without buffer (no receving needed)
    artnet = new ArtNetClient(null);
    artnet.start();

    ipPrefix = _ipPrefix;
    startIP = _startIp;

    numController = parent.ceil((float) _stripeConfiguration.numStripes / (float) _stripeConfiguration.numStripesPerController);
    numUniversesPerController = parent.ceil(((float) _stripeConfiguration.numStripesPerController * (float) _stripeConfiguration.numLedsPerStripe * 3.f) / 510.f);
    numUniverses = numController * numUniversesPerController;
    numLeds = _stripeConfiguration.numLeds;
    numPixelPerController = _stripeConfiguration.numStripesPerController * _stripeConfiguration.numLedsPerStripe;
  }

  public void sendToLeds(LedColor[] _ledColors) {

    for (int i = 0; i < numUniverses; i++) {
      int controllerNumber = parent.ceil(i / numUniversesPerController); // first conroller is 0
      int pixelOffset = controllerNumber * numPixelPerController
        + (i - (controllerNumber * numUniversesPerController)) * 170; // calculates the id of the first pixel connected to the controller
      buildPackage(pixelOffset, _ledColors);
      sendPackage(i, controllerNumber);
    }
  }

  void sendPackage(int universe, int _controllerNumber) {
    int ip = startIP + _controllerNumber;
    String ipAdress = ipPrefix + ip;
    artnet.unicastDmx(ipAdress, 0, universe, dmxData);
  }

  void buildPackage(int offset, LedColor[] _ledColors) {

    if (numLeds - offset < 170) { // if last universe is not filled up
      for (int i = 0; i < (numLeds - offset); i++) { // 170 pixel per universum => 510 bytes
        int rgbPosition = i * 3;
        dmxData[rgbPosition + 0] = (byte) (parent.constrain(_ledColors[i + offset].x * 255, 0, 255)); // Colors are saved in an extended PVector.
        // Here is the place to constrain to values from 0-255
        dmxData[rgbPosition + 1] = (byte) (parent.constrain(_ledColors[i + offset].y * 255, 0, 255));
        dmxData[rgbPosition + 2] = (byte) (parent.constrain(_ledColors[i + offset].z * 255, 0, 255));
      }
    } else {
      for (int i = 0; i < 170; i++) { // 170 pixel per universum => 510 bytes
        int rgbPosition = i * 3;
        dmxData[rgbPosition + 0] = (byte) (parent.constrain(_ledColors[i + offset].x * 255, 0, 255));
        dmxData[rgbPosition + 1] = (byte) (parent.constrain(_ledColors[i + offset].y * 255, 0, 255));
        dmxData[rgbPosition + 2] = (byte) (parent.constrain(_ledColors[i + offset].z * 255, 0, 255));
      }
    }
  }
}
