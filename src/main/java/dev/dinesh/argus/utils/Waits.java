package dev.dinesh.argus.utils;

import java.util.concurrent.TimeUnit;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class Waits {

  public void sleep(long interval) {
    try {
      log.info("Sleeping for {{}} seconds%n", interval);
      TimeUnit.SECONDS.sleep(interval);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void sleepInMilliseconds(long interval) {
    try {
      log.info("Sleeping for {{}} milliseconds%n", interval);
      TimeUnit.MILLISECONDS.sleep(interval);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void sleepInMinutes(long interval) {
    try {
      log.info("Sleeping for {{}} minutes%n", interval);
      TimeUnit.MINUTES.sleep(interval);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
