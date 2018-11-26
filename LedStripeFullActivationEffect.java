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
  int nLeds;
  RemoteControlledColorParameter remoteColor;
  LedColor.LedAlphaMode blendMode = LedColor.LedAlphaMode.NORMAL;
  LedInStripeInfo[] stripeInfo;
  int activatedStripeIndex;

  LedStripeFullActivationEffect(String _id, int _nLeds, LedInStripeInfo[] _stripeInfo, int _activatedStripeIndex) {
    id = _id;
    nLeds=_nLeds;
    bufferLedColors = LedColor.createColorArray(nLeds);
    remoteColor = new RemoteControlledColorParameter("template" + id, 0.5f, 1f, 0.5f);
    stripeInfo = _stripeInfo;
    activatedStripeIndex = _activatedStripeIndex;
  }

  public LedColor[] drawMe() {
    for(int i = 0; i < stripeInfo.length; i++){
      if(stripeInfo[i].whichStripeIndex == activatedStripeIndex){
        bufferLedColors[i] = new LedColor(255, 255, 255);
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
