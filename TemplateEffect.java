import processing.core.PApplet;
import processing.core.PVector;

public class TemplateEffect implements runnableLedEffect {
  PApplet papplet;
  String name = "Template";
  String id;
  LedColor[] bufferLedColors;
  int nLeds;
  RemoteControlledColorParameter remoteColor;
  LedColor.LedAlphaMode blendMode = LedColor.LedAlphaMode.NORMAL;

  TemplateEffect(String _id, int _nLeds) {
    id = _id;
    nLeds=_nLeds;
    bufferLedColors = LedColor.createColorArray(nLeds);
    remoteColor = new RemoteControlledColorParameter("template" + id, 0.5f, 1f, 0.5f);
  }

  public LedColor[] drawMe() {
    LedColor.set(bufferLedColors, remoteColor.getColor());
    return bufferLedColors;
  }

  public String getName() {
    return name;
  }

}
