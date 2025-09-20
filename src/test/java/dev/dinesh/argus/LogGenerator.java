package dev.dinesh.argus;

import dev.dinesh.argus.seeder.DataSeeder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogGenerator {

  public static void main(String[] args) throws Exception {
    // DataSeeder.generate();
    //    DataSeeder.generateNginxLogs("src/main/resources/logs/nginx.log");
    DataSeeder.generateKeyValueLogs("src/main/resources/logs/custom-kv.log");
  }
}
