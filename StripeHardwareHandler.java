import processing.core.PApplet;

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
