package dev.dinesh.argus.ingest;

import dev.dinesh.argus.ArgusWebApplication;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs the whole application in its own JVM for the push load tests, so its heap limit is enforced
 * and it can be killed. Prints {@code READY port=N} once serving; a line (or EOF) on stdin shuts it
 * down gracefully and prints {@code HEAP peakHeapMB=… maxHeapMB=…}.
 */
public final class PushServerMain {

  /**
   * {@code path:bytes}: write a replay file of NDJSON lines first (in the server's file system).
   */
  static final String GENERATE = "argus.load.generate";

  public static void main(String[] args) throws Exception {
    String generate = System.getProperty(GENERATE);
    if (generate != null) {
      int colon = generate.lastIndexOf(':');
      writeLines(
          Path.of(generate.substring(0, colon)), Long.parseLong(generate.substring(colon + 1)));
    }
    HeapSampler heap = HeapSampler.start();
    ConfigurableApplicationContext context = SpringApplication.run(ArgusWebApplication.class, args);
    System.out.println("READY port=" + context.getEnvironment().getProperty("local.server.port"));
    System.out.flush();
    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    context.close();
    heap.stop();
    System.out.printf(
        "HEAP peakHeapMB=%d maxHeapMB=%d%n",
        heap.peakUsedBytes() / (1024 * 1024), Runtime.getRuntime().maxMemory() / (1024 * 1024));
    System.out.flush();
    System.exit(0);
  }

  /** About 200-byte NDJSON lines until {@code bytes}, streamed to disk. */
  static void writeLines(Path file, long bytes) throws IOException {
    Files.createDirectories(file.getParent());
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)) {
      long written = 0;
      for (int b = 0; written < bytes; b++) {
        byte[] batch = PushLoad.batch(99, b, 1000);
        out.write(batch);
        written += batch.length;
      }
    }
  }
}
