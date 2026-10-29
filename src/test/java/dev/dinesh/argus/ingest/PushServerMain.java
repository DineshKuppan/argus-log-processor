package dev.dinesh.argus.ingest;

import dev.dinesh.argus.ArgusWebApplication;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs the whole application in its own JVM for the push load tests, so its heap limit is enforced
 * and it can be killed. Prints {@code READY port=N} once serving; a line (or EOF) on stdin shuts it
 * down gracefully and prints {@code HEAP peakHeapMB=… maxHeapMB=…}.
 */
public final class PushServerMain {

  public static void main(String[] args) throws Exception {
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
}
