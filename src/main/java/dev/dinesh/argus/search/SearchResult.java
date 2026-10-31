package dev.dinesh.argus.search;

import java.util.List;

/**
 * One page of a search.
 *
 * @param next the cursor for the next page; null on the last page
 * @param partial the engine timed out and these are the hits it had found
 */
public record SearchResult(List<SearchHit> hits, String next, boolean partial, long tookMs) {}
