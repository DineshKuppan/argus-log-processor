package dev.dinesh.argus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ArgusWebApplication {
  public static void main(String[] args) {
    SpringApplication.run(ArgusWebApplication.class, args);
  }
}
