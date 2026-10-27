package dev.dinesh.argus.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.TenancyProperties;
import dev.dinesh.argus.source.AllowedPaths.OutsideAllowedRootsException;
import dev.dinesh.argus.tenant.ApiKeyTenantResolver.ApiKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TenantRootsTest {

  @TempDir Path shared;
  @TempDir Path acmeOnly;

  @Test
  void aKeysOwnRootsReplaceTheSharedOnes() throws IOException {
    Path sharedFile = Files.writeString(shared.resolve("s.log"), "x\n");
    Path acmeFile = Files.writeString(acmeOnly.resolve("a.log"), "x\n");
    TenantRoots roots =
        new TenantRoots(
            new IngestProperties(0, null, null, 0, 0, null, 0, List.of(shared), null, null),
            new TenancyProperties(
                null,
                List.of(
                    new ApiKey("acme", ApiKeyTenantResolver.sha256Hex("a"), List.of(acmeOnly)),
                    new ApiKey("globex", ApiKeyTenantResolver.sha256Hex("g"), null))));

    assertEquals(acmeFile.toRealPath(), roots.forTenant("acme").requireFile(acmeFile.toString()));
    assertThrows(
        OutsideAllowedRootsException.class,
        () -> roots.forTenant("acme").requireFile(sharedFile.toString()));
    assertEquals(
        sharedFile.toRealPath(), roots.forTenant("globex").requireFile(sharedFile.toString()));
    assertThrows(
        OutsideAllowedRootsException.class,
        () -> roots.forTenant("globex").requireFile(acmeFile.toString()));
  }
}
