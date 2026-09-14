import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import org.jetbrains.java.decompiler.main.CancellationManager;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.*;

/** Local-only diagnostic CLI: reads bytecode as data, never loads the supplied class. */
public class DecompilerProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 4) throw new IllegalArgumentException("input.class internal/name output.java genericSignatures(true/false)");
    Path input = Path.of(args[0]).toAbsolutePath().normalize();
    byte[] bytes = Files.readAllBytes(input);
    String[] source = new String[1];
    IResultSaver saver = new IResultSaver() {
      public void saveClassFile(String p, String q, String e, String c, int[] m) { if (args[1].equals(q)) source[0] = c; }
      public void saveFolder(String p) {}
      public void copyFile(String s, String p, String e) {}
      public void createArchive(String p, String a, Manifest m) {}
      public void saveDirEntry(String p, String a, String e) {}
      public void copyEntry(String s, String p, String a, String e) {}
      public void saveClassEntry(String p, String a, String q, String e, String c) {}
      public void closeArchive(String p, String a) {}
    };
    IFernflowerLogger logger = new IFernflowerLogger() {
      public void writeMessage(String m, Severity s) { if (s.compareTo(Severity.WARN) >= 0) System.err.println(s + ": " + m); }
      public void writeMessage(String m, Severity s, Throwable t) { writeMessage(m, s); t.printStackTrace(System.err); }
    };
    CancellationManager cancel = new CancellationManager() {
      public void checkCanceled() { if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException(); }
      public void startMethod(String c, String m) { checkCanceled(); }
      public void finishMethod(String c, String m) { checkCanceled(); }
    };
    Map<String, Object> options = new HashMap<>();
    options.put(IFernflowerPreferences.DECOMPILE_INNER, "0");
    options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, Boolean.parseBoolean(args[3]) ? "1" : "0");
    options.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "0");
    options.put(IFernflowerPreferences.NEW_LINE_SEPARATOR, "1");
    options.put(IFernflowerPreferences.INDENT_STRING, "    ");
    try {
      BaseDecompiler engine = new BaseDecompiler((external, internal) -> {
        if (internal != null || !Path.of(external).toAbsolutePath().normalize().equals(input))
          throw new java.io.IOException("Unselected bytecode requested: " + external);
        return bytes;
      }, saver, options, logger, cancel);
      engine.addSource(input.toFile());
      engine.decompileContext();
    } finally { DecompilerContext.setCurrentContext(null); }
    if (source[0] == null || source[0].isBlank()) { System.out.println("sourceProduced=false"); System.exit(2); }
    Files.writeString(Path.of(args[2]), source[0]);
    System.out.println("sourceProduced=true chars=" + source[0].length());
  }
}
