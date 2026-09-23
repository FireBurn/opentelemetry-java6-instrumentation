import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashSet;
import org.codehaus.mojo.animal_sniffer.SignatureChecker;
import org.codehaus.mojo.animal_sniffer.logging.Logger;

/**
 * Checks class files (a jar or a directory) against the Java 6 API signature using
 * animal-sniffer — the same tool the main build uses (it checks against the Android API 23
 * signature; this checker uses the {@code java16} signature, which is the right one for a Java 6
 * target).
 *
 * <p>Usage: {@code Java6ApiChecker <java16.signature> <jar-or-directory> [ignored-package...]}
 *
 * <p>Exits non-zero if any class references a JDK API that does not exist in Java 6.
 */
public final class Java6ApiChecker {

  private Java6ApiChecker() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.out.println("usage: Java6ApiChecker <signature> <jar-or-directory> [ignored-package...]");
      System.exit(2);
    }
    HashSet<String> ignoredPackages = new HashSet<String>(Arrays.asList(args).subList(2, args.length));
    final Logger logger =
        new Logger() {
          public void info(String m) {}

          public void info(String m, Throwable t) {}

          public void debug(String m) {}

          public void debug(String m, Throwable t) {}

          public void warn(String m) {
            System.out.println("WARN: " + m);
          }

          public void warn(String m, Throwable t) {
            System.out.println("WARN: " + m + " " + t);
          }

          public void error(String m) {
            System.out.println(m);
          }

          public void error(String m, Throwable t) {
            System.out.println(m + " " + t);
          }
        };
    FileInputStream in = new FileInputStream(args[0]);
    try {
      SignatureChecker checker = new SignatureChecker(in, ignoredPackages, logger);
      checker.setSourcePath(java.util.Collections.<File>emptyList());
      checker.process(new File(args[1]));
      if (checker.isSignatureBroken()) {
        System.out.println("JAVA 6 API CHECK FAILED");
        System.exit(1);
      }
    } finally {
      in.close();
    }
    System.out.println("JAVA 6 API CHECK OK");
  }
}
