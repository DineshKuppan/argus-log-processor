package dev.dinesh.argus.export;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * A remote log system an export pages through ({@code P3_PLAN.md} D7), one page per call. A page's
 * lines are appended to a stream spool, and only then is the cursor that follows it saved, so a
 * source must return the same page again for the same cursor (as far as the remote system allows).
 *
 * <p>Failures: an {@link IOException} is transient (the job retries it); a {@link
 * PermanentExportException} is not (bad query, bad credentials).
 */
public interface ExportSource {

  /**
   * What to export.
   *
   * @param query the source's query language: an Elasticsearch query object, or DataPrime text;
   *     null for everything
   * @param from inclusive
   * @param to exclusive
   */
  record Query(JsonNode query, Instant from, Instant to) {}

  /**
   * Where the next page starts.
   *
   * @param position what decides the next page's contents; saved, and part of its page id
   * @param session remote state that only makes it cheaper or consistent (an Elasticsearch point in
   *     time), or null; a source must cope with it having expired
   */
  record Cursor(JsonNode position, String session) {}

  /**
   * One page.
   *
   * @param lines one line each (no {@code \n} inside), in the source's order
   * @param pageId the page's identity, the same whenever the same page is fetched again; part of
   *     its {@code Idempotency-Key}
   * @param next where the page after this one starts
   * @param last no page follows this one
   */
  record Page(List<String> lines, String pageId, Cursor next, boolean last) {}

  /**
   * Checks a request's query before a job starts.
   *
   * @throws IllegalArgumentException if the query isn't one this source can run
   */
  void validate(JsonNode query);

  /**
   * The settings that decide what an export reads (the URL, the index), for the export's identity.
   * Never credentials.
   */
  String describe();

  /**
   * Fetches the page at {@code cursor}, or the first page when it is null.
   *
   * @throws PermanentExportException if retrying can't help
   * @throws IOException if the source failed or could not be reached; retrying may help
   */
  Page fetch(Query query, Cursor cursor) throws IOException, InterruptedException;

  /** Frees remote state the cursor holds (best effort). */
  default void release(Cursor cursor) {}
}
