import java.io.*; import java.nio.file.*; import java.util.zip.CRC32;
public class Calc {
  public static void main(String[] a) throws Exception {
    long target = Long.parseLong(a[0]);
    String prefix = a[1];
    for (int i = 0; i < 1000000; i++) {
      String name = String.format(" %s%06d.sql\,