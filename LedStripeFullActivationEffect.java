import processing.core.PApplet;
import processing.core.PVector;

///////////////////////////////////////////////////////////
// turns on a single hardware LED stripe
///////////////////////////////////////////////////////////

public class LedStripeFullActivationEffect implements runnableLedEffect {
  PApplet papplet;
  String name = "Activate Stripe";
  String id;
  LedColor[] bufferLedColors;
  LedInStripeInfo[] stripeInfo;
  int activatedStripeIndex;

  LedStripeFullActivationEffect(String _id, LedInStripeInfo[] _stripeInfo, int _activatedStripeIndex) {
    id = _id;
    stripeInfo = _stripeInfo;
    bufferLedColors = LedColor.createColorArray(stripeInfo.length);
    activatedStripeIndex = _activatedStripeIndex;
  }

  public LedColor[] drawMe() {
    for(int i = 0; i < stripeInfo.length; i++){
      if(stripeInfo[i].whichStripeIndex == activatedStripeIndex){
        bufferLedColors[i] = new LedColor(1, 1, 1);
      } else {
        bufferLedColors[i] = new LedColor(0, 0, 0);
      }
    }
    return bufferLedColors;
  }

  public String getName() {
    return name;
  }

}
