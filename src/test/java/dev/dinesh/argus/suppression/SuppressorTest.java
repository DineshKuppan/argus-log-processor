package dev.dinesh.argus.suppression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SuppressorTest {

  private final Suppressor suppressor =
      new Suppressor(
          new LocalSuppressionStore(Duration.ofSeconds(10), 1_000), new MessageFingerprint(false));

  @Test
  void suppressesTheSameMessageForTheSameTenantOnly() {
    assertTrue(suppressor.check("acme", "disk full").allowed());
    assertFalse(suppressor.check("acme", "  disk full\n").allowed());
    assertTrue(suppressor.check("globex", "disk full").allowed());
    assertTrue(suppressor.check("acme", "disk full on /var").allowed());
  }

  @Test
  void keysStartWithTheTenant() {
    String key = suppressor.key("acme", "m");

    assertTrue(key.startsWith("acme:supp:"));
    assertEquals("acme:supp:".length() + 22, key.length());
  }

  @Test
  void rejectsInvalidTenantsAndMissingMessages() {
    assertThrows(IllegalArgumentException.class, () -> suppressor.check("a:b", "m"));
    assertThrows(IllegalArgumentException.class, () -> suppressor.check("", "m"));
    assertThrows(IllegalArgumentException.class, () -> suppressor.check(null, "m"));
    assertThrows(IllegalArgumentException.class, () -> suppressor.check("acme", null));
  }

  @Test
  void templateModeIgnoresIdsAndNumbers() {
    MessageFingerprint exact = new MessageFingerprint(false);
    MessageFingerprint template = new MessageFingerprint(true);

    assertNotEquals(exact.of("user 42 failed"), exact.of("user 43 failed"));
    assertEquals(template.of("user 42 failed"), template.of("user 43 failed"));
    assertEquals(
        "req <uuid> from <hex> took <num>ms (deadbeef)",
        MessageFingerprint.template(
            "req 123e4567-e89b-12d3-a456-426614174000 from 0x7f3a9c2b1d took 12.5ms (deadbeef)"));
  }
}
