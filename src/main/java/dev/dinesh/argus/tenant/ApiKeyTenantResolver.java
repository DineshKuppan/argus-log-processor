package dev.dinesh.argus.tenant;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the tenant from an API key sent as {@code X-Argus-Api-Key: <key>} or {@code
 * Authorization: Bearer <key>}. Only the SHA-256 of each key is configured, so configuration files
 * and dumps hold no usable secret. Thread-safe.
 */
public final class ApiKeyTenantResolver implements TenantResolver {

  public static final String API_KEY_HEADER = "X-Argus-Api-Key";
  private static final String BEARER = "Bearer ";
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  /** One configured key: its tenant and the lowercase hex SHA-256 of the key. */
  public record ApiKey(String tenant, String keySha256) {}

  private final Map<String, String> tenantByKeyHash = new HashMap<>();

  /**
   * @throws IllegalArgumentException for an invalid tenant id, a malformed hash or a duplicate key
   */
  public ApiKeyTenantResolver(List<ApiKey> keys) {
    for (ApiKey key : keys) {
      String tenant = TenantIds.requireValid(key.tenant());
      String hash = key.keySha256() == null ? "" : key.keySha256().toLowerCase(Locale.ROOT);
      if (!SHA256_HEX.matcher(hash).matches()) {
        throw new IllegalArgumentException(
            "API key for tenant " + tenant + " must be configured as a 64-character hex SHA-256");
      }
      if (tenantByKeyHash.putIfAbsent(hash, tenant) != null) {
        throw new IllegalArgumentException("The same API key is configured twice");
      }
    }
  }

  public int keyCount() {
    return tenantByKeyHash.size();
  }

  @Override
  public String resolve(HttpServletRequest request) {
    String key = presentedKey(request);
    if (key == null) {
      throw new UnauthenticatedException(
          "API key required (" + API_KEY_HEADER + " or Authorization: Bearer)");
    }
    String tenant = tenantByKeyHash.get(sha256Hex(key));
    if (tenant == null) {
      throw new UnauthenticatedException("Unknown API key");
    }
    return tenant;
  }

  private static String presentedKey(HttpServletRequest request) {
    String key = request.getHeader(API_KEY_HEADER);
    if (key == null) {
      String authorization = request.getHeader("Authorization");
      if (authorization != null
          && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
        key = authorization.substring(BEARER.length());
      }
    }
    return key == null || key.isBlank() ? null : key.strip();
  }

  public static String sha256Hex(String key) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
