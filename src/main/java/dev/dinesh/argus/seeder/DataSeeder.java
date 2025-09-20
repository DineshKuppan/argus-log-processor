package dev.dinesh.argus.seeder;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for generating synthetic log data for API requests. The generated logs mimic
 * real-world API patterns and are written to a JSON file.
 */
@Slf4j
public class DataSeeder {

  public static void generate() throws Exception {
    String filePath = "src/main/resources/logs/app.json";
    generate(filePath);
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
    String[] ips = {"10.0.0.12", "10.0.0.13", "10.0.0.14", "10.0.0.15"};
    String[] methods = {"GET", "POST", "PUT"};
    String[] endpoints = {"/api/orders", "/api/products", "/api/status", "/api/users", "/health"};
    int[] statusCodes = {200, 201, 400, 401, 403, 404, 500, 502, 503};
    String[] userAgents = {"curl/8.0", "Mozilla/5.0", "k8s-probe", "PostmanRuntime/7.32.0"};
    log.info("Generating logs for nginx ...");
    Random rand = new Random();
    java.time.format.DateTimeFormatter dtf =
        java.time.format.DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z")
            .withZone(java.time.ZoneOffset.UTC);

    try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
      for (int i = 0; i < 5000; i++) {
        String ip = ips[rand.nextInt(ips.length)];
        String method = methods[rand.nextInt(methods.length)];
        String endpoint = endpoints[rand.nextInt(endpoints.length)];
        int status = statusCodes[rand.nextInt(statusCodes.length)];
        int size = 1 + rand.nextInt(2000);
        String userAgent = userAgents[rand.nextInt(userAgents.length)];
        java.time.Instant instant = java.time.Instant.now().minusSeconds(rand.nextInt(100000));
        String timestamp = dtf.format(instant);

        String log =
            String.format(
                "%s - - [%s] \"%s %s HTTP/1.1\" %d %d \"-\" \"%s\"",
                ip, timestamp, method, endpoint, status, size, userAgent);
        writer.write(log);
        writer.newLine();
      }
    }
    log.info("Logs for nginx generated at {}", filePath);
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
