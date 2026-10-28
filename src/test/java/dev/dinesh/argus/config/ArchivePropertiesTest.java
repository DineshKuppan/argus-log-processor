package dev.dinesh.argus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ArchivePropertiesTest {

  private static ArchiveProperties withPrefix(String prefix) {
    return new ArchiveProperties(null, prefix, null, null, null, null, null, null, null, null);
  }

  @Test
  void defaultsToNoArchive() {
    ArchiveProperties p = withPrefix(null);

    assertEquals(ArchiveProperties.Store.NONE, p.store());
    assertEquals("", p.prefix());
    assertTrue(p.deleteAfterUpload());
    assertEquals(Duration.ofSeconds(10), p.scanInterval());
    assertEquals(64L * 1024 * 1024, p.rowGroupSize().toBytes());
    assertEquals(Duration.ofSeconds(1), p.retryInitial());
    assertEquals(Duration.ofMinutes(5), p.retryMax());
    assertEquals(ArchiveProperties.S3.Checksums.WHEN_SUPPORTED, p.s3().checksums());
  }

  @Test
  void normalizesThePrefixAndRejectsOnesThatMakeInvalidKeys() {
    assertEquals("argus/prod/", withPrefix("/argus/prod/").prefix());
    assertEquals("", withPrefix(" / ").prefix());
    assertThrows(IllegalArgumentException.class, () -> withPrefix("a//b"));
    assertThrows(IllegalArgumentException.class, () -> withPrefix("a/../b"));
  }

  @Test
  void rejectsAnS3StoreWithoutABucketAndNonPositiveDurations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArchiveProperties(
                ArchiveProperties.Store.S3, null, null, null, null, null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArchiveProperties(
                null, null, null, Duration.ZERO, null, null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArchiveProperties(
                null, null, null, null, null, null, Duration.ofMinutes(10), null, null, null));
  }
}
