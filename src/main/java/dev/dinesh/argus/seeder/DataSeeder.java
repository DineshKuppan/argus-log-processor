package dev.dinesh.argus.seeder;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.unit.DataSize;

/**
 * Utility class for generating synthetic log data for API requests. The generated logs mimic
 * real-world API patterns and are written to a JSON file.
 */
@Slf4j
public class DataSeeder {

  private static final String[] NGINX_IPS = {"10.0.0.12", "10.0.0.13", "10.0.0.14", "10.0.0.15"};
  private static final String[] NGINX_METHODS = {"GET", "POST", "PUT"};
  private static final String[] NGINX_ENDPOINTS = {
    "/api/orders", "/api/products", "/api/status", "/api/users", "/health"
  };
  private static final int[] NGINX_STATUS_CODES = {200, 201, 400, 401, 403, 404, 500, 502, 503};
  private static final String[] NGINX_USER_AGENTS = {
    "curl/8.0", "Mozilla/5.0", "k8s-probe", "PostmanRuntime/7.32.0"
  };
  private static final DateTimeFormatter NGINX_TIMESTAMP =
      DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ROOT).withZone(ZoneOffset.UTC);

  public static void generate() throws Exception {
    String filePath = "src/main/resources/logs/nginx_app.log";
    generateNginxLogs(filePath);
    //    generate(filePath);
  }

  /**
   * With no arguments, regenerates the bundled sample log. With {@code <path> <size>} (e.g. {@code
   * data/in/big.log 10GB}), streams a large Nginx log for load testing.
   */
  public static void main(String[] args) {
    try {
      if (args.length == 2) {
        generateNginxLogs(Path.of(args[0]), DataSize.parse(args[1]).toBytes(), 42L);
      } else {
        generate();
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void generate(String filePath) throws Exception {
    String[] levels = {"INFO", "ERROR", "WARN", "DEBUG"};
    String[] services = {"user", "payment", "order", "notification", "shipping"};
    String[] users = {"alice", "bob", "carol", "david", "eve", "frank"};
    String[] endpoints = {"/api/orders", "/api/products", "/api/status", "/api/users"};
    String[] methods = {"GET", "POST", "PUT"};
    String[] messages = {
      "User login successful",
      "Payment gateway timeout",
      "Inventory low",
      "Email sent",
      "Shipment dispatched",
      "Order placed",
      "User logout",
      "Payment processed",
      "Stock updated",
      "Notification sent"
    };
    String[] errorMessages = {
      "Failed to create order",
      "Database timeout",
      "Validation error",
      "Internal server error",
      "Deprecated API used"
    };
    log.info("Generating logs...");
    Random rand = new Random();
    DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
      for (int i = 0; i < 5000; i++) {
        String level = levels[rand.nextInt(levels.length)];
        String user = users[rand.nextInt(users.length)];
        String endpoint = endpoints[rand.nextInt(endpoints.length)];
        String method = methods[rand.nextInt(methods.length)];
        int latency = 40 + rand.nextInt(1200);

        String message = method + " " + endpoint + " HTTP/1.1";
        if ("ERROR".equals(level) || "WARN".equals(level)) {
          message += " - " + errorMessages[rand.nextInt(errorMessages.length)];
        }

        String log =
            String.format(
                "{\"timestamp\":\"%s\",\"level\":\"%s\",\"message\":\"%s\",\"user\":\"%s\",\"latency_ms\":%d}",
                formatter.format(Instant.now().minusSeconds(rand.nextInt(100000))),
                level,
                message,
                user,
                latency);
        writer.write(log);
        writer.newLine();
      }
      log.info("Logs generated at {}", filePath);
    }
  }

  /**
   * Generates random Nginx-style access logs and writes them to the specified file. Log format: IP
   * - - [timestamp] "METHOD ENDPOINT HTTP/1.1" STATUS SIZE "-" "USER_AGENT"
   *
   * @param filePath the path to the output log file
   * @throws Exception if an I/O error occurs
   */
  public static void generateNginxLogs(String filePath) throws Exception {
    log.info("Generating logs for nginx ...");
    Random rand = new Random();
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
      for (int i = 0; i < 5000; i++) {
        writer.write(nginxLine(rand, Instant.now().minusSeconds(rand.nextInt(100000))));
        writer.newLine();
      }
    }
    log.info("Logs for nginx generated at {}", filePath);
  }

  /**
   * Streams Nginx access-log lines to {@code path} until at least {@code targetBytes} are written,
   * without holding the file in memory. Timestamps increase from a fixed start, and a fixed {@code
   * seed} makes the file reproducible, which large-file ingest tests rely on.
   *
   * @return number of lines written
   */
  public static long generateNginxLogs(Path path, long targetBytes, long seed) throws IOException {
    Random rand = new Random(seed);
    Instant start = Instant.parse("2025-10-01T00:00:00Z");
    long written = 0;
    long lines = 0;
    try (BufferedWriter writer =
        Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      while (written < targetBytes) {
        String line = nginxLine(rand, start.plusMillis(lines * 10));
        writer.write(line);
        writer.write('\n');
        // Generated lines are ASCII, so chars equal bytes.
        written += line.length() + 1;
        lines++;
      }
    }
    log.info("Generated {} nginx lines ({} bytes) at {}", lines, written, path);
    return lines;
  }

  private static String nginxLine(Random rand, Instant instant) {
    return String.format(
        "%s - - [%s] \"%s %s HTTP/1.1\" %d %d \"-\" \"%s\"",
        NGINX_IPS[rand.nextInt(NGINX_IPS.length)],
        NGINX_TIMESTAMP.format(instant),
        NGINX_METHODS[rand.nextInt(NGINX_METHODS.length)],
        NGINX_ENDPOINTS[rand.nextInt(NGINX_ENDPOINTS.length)],
        NGINX_STATUS_CODES[rand.nextInt(NGINX_STATUS_CODES.length)],
        1 + rand.nextInt(2000),
        NGINX_USER_AGENTS[rand.nextInt(NGINX_USER_AGENTS.length)]);
  }

  /**
   * Generates random key-value style logs and writes them to the specified file. Log format:
   * key1=value1 key2=value2 key3=value3 ...
   *
   * @param filePath the path to the output log file
   * @throws Exception if an I/O error occurs
   */
  public static void generateKeyValueLogs(String filePath) throws Exception {
    String[] keys = {"timestamp", "level", "user", "action", "status", "latency_ms", "service"};
    String[] levels = {"INFO", "ERROR", "WARN", "DEBUG"};
    String[] users = {"alice", "bob", "carol", "david", "eve", "frank"};
    String[] actions = {"login", "logout", "create", "update", "delete", "fetch"};
    String[] statuses = {"success", "failure", "timeout", "invalid"};
    String[] services = {"user", "order", "payment", "notification", "shipping"};
    log.info("Generating logs for key-value formatted logs ...");
    Random rand = new Random();
    DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
      for (int i = 0; i < 5000; i++) {
        String log =
            String.format(
                "timestamp=%s level=%s user=%s action=%s status=%s latency_ms=%d service=%s",
                formatter.format(Instant.now().minusSeconds(rand.nextInt(100000))),
                levels[rand.nextInt(levels.length)],
                users[rand.nextInt(users.length)],
                actions[rand.nextInt(actions.length)],
                statuses[rand.nextInt(statuses.length)],
                40 + rand.nextInt(1200),
                services[rand.nextInt(services.length)]);
        writer.write(log);
        writer.newLine();
      }
    }
    log.info("Logs for key-value type generated at {}", filePath);
  }
}
