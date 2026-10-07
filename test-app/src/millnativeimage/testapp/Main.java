package millnativeimage.testapp;

/** Tiny application, built as a native image by the plugin tests */
public class Main {
  public static void main(String[] args) {
    System.out.println("Hello from mill-native-image");
    System.out.println("java.version=" + System.getProperty("java.version"));
    System.out.println("imagecode=" + System.getProperty("org.graalvm.nativeimage.imagecode"));
  }
}
