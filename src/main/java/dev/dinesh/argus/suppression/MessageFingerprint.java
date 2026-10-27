package dev.dinesh.argus.suppression;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The part of a suppression key that identifies a message: a hash of the trimmed message. In
 * template mode, UUIDs, hex ids and numbers are replaced first, so {@code user 42 failed} and
 * {@code user 43 failed} count as the same message. Thread-safe.
 */
public final class MessageFingerprint {

  // 128 of SHA-256's 256 bits: 22 base64url characters, and no realistic collisions.
  private static final int HASH_BYTES = 16;

  private static final Pattern UUID =
      Pattern.compile(
          "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
  // At least 8 hex characters including both a digit and a letter, so plain words and numbers don't
  // match (numbers are handled by NUMBER).
  private static final Pattern HEX_ID =
      Pattern.compile("\\b(?:0x)?(?=[0-9a-fA-F]*[0-9])(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{8,}\\b");
  private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

  private final boolean template;

  public MessageFingerprint(boolean template) {
    this.template = template;
  }

  public String of(String message) {
    String normalized = message.strip();
    if (template) {
      normalized = template(normalized);
    }
    byte[] digest = sha256().digest(normalized.getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(Arrays.copyOf(digest, HASH_BYTES));
  }

  static String template(String message) {
    String s = UUID.matcher(message).replaceAll("<uuid>");
    s = HEX_ID.matcher(s).replaceAll("<hex>");
    return NUMBER.matcher(s).replaceAll("<num>");
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
