package fr.umlv.valuetypify.test;

import java.util.Optional;

//import java.util.Arrays;

public class ColorList {
  private Color[] colors;
  private int size;
  
  public ColorList() {
    colors = new Color[1];
  }
  
  public void add(Color color) {
    int size = this.size;
    Color[] colors = this.colors;
    if (size == colors.length) {
      colors = resize();
    }
    colors[size] = color;
    this.size = size + 1;
  }
  
  public Optional<Color> average() {
    if (size == 0) {
      return Optional.empty();
    }
    Color c = colors[0];
    for(int i = 1; i < size; i++) {
      c = c.plus(colors[i]);
    }
    return Optional.of(c.div(size));
  }
  
  private Color[] resize() {
    Color[] colors = this.colors;
    Color[] newColors = new Color[colors.length << 1];
    System.arraycopy(colors, 0, newColors, 0, colors.length);
    return this.colors = newColors;
    //return this.colors = Arrays.copyOf(colors, colors.length << 1);
  }
  
  public static void main(String[] args) {
    long start = System.nanoTime();
    ColorList list = new ColorList();
    for(int i = 0; i < 50_000_000; i++) {
      list.add(new Color(i, i, i));
    }
    long end = System.nanoTime();
    
    long start2 = System.nanoTime();
    Color average = list.average().get();
    long end2 = System.nanoTime();
    
    System.out.println(average);
    System.out.println("creation " + ((end - start) / 1_000_000d) + " ms");
    System.out.println("average " + ((end2 - start2) / 1_000_000d) + " ms");
  }
}
