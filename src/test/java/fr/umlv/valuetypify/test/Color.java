package fr.umlv.valuetypify.test;

import jvm.internal.value.ValueCapableClass;

@ValueCapableClass
public final class Color {
  /*private*/ final float red;
  /*private*/ final float green;
  /*private*/ final float blue;
  
  public Color(float red, float green, float blue) {
    this.red = red;
    this.green = green;
    this.blue = blue;
  }
  
  public Color plus(Color c) {
    return new Color(red + c.red, green + c.green, blue + c.blue);
  }
  
  @Override
  public String toString() {
    return "Color(" + red + ", " + green + ", " + blue + ')';
  }

  public Color div(float value) {
    return new Color(red / value, green / value, blue / value);
  }
  
  public static void main(String[] args) {
    Color color = new Color(0.1f, 0.2f, 0.3f);
    System.out.println(color.red);
    System.out.println(color);
  }
}
