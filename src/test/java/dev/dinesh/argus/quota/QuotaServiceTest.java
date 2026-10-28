package dev.dinesh.argus.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.config.QuotaProperties;
import dev.dinesh.argus.config.QuotaProperties.Tenant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class QuotaServiceTest {

  private final AtomicLong nanos = new AtomicLong();

  @Test
  void eachTenantHasItsOwnBucketAndOverridesApply() {
    QuotaProperties props =
        new QuotaProperties(
            null, DataSize.ofBytes(100), 2.0, Map.of("big", new Tenant(DataSize.ofBytes(1000))));
    QuotaService quotas = new QuotaService(new LocalQuotaStore(nanos::get), props);

    assertTrue(quotas.takeIngestBytes("acme", 200).allowed());
    assertFalse(quotas.takeIngestBytes("acme", 1).allowed());
    assertTrue(quotas.takeIngestBytes("globex", 200).allowed(), "another tenant is unaffected");
    assertTrue(quotas.takeIngestBytes("big", 2000).allowed());
    assertThrows(IllegalArgumentException.class, () -> quotas.takeIngestBytes("a:b", 1));
  }

  @Test
  void defaultsAndValidation() {
    QuotaProperties defaults = new QuotaProperties(null, null, null, null);

    assertEquals(QuotaProperties.Store.LOCAL, defaults.store());
    assertEquals(2 * 1024 * 1024, defaults.ingestBytesPerSecond("any"));
    assertEquals(2.0, defaults.burstSeconds());
    assertThrows(
        IllegalArgumentException.class,
        () -> new QuotaProperties(null, DataSize.ofBytes(0), null, null));
    assertThrows(IllegalArgumentException.class, () -> new QuotaProperties(null, null, 0.0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QuotaProperties(null, null, null, Map.of("x", new Tenant(DataSize.ofBytes(-1)))));
  }
}
