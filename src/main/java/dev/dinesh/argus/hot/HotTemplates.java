package dev.dinesh.argus.hot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.engine.SearchEngineClient.Engine;
import dev.dinesh.argus.engine.SearchEngineClient.Flavor;
import dev.dinesh.argus.engine.SearchEngineClient.Response;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

/**
 * The composable index template every hot-tier index gets ({@code P5_PLAN.md} D4, D5): settings and
 * a strict mapping, with {@code attributes} / {@code resource} as {@code flattened} (Elasticsearch)
 * or {@code flat_object} (OpenSearch 2.7+). Installed at startup and again when an older Argus
 * installed it; it applies to indices created afterwards (the next day's).
 */
@Slf4j
public final class HotTemplates {

  /** Raise when the mapping or settings change, so a newer Argus replaces the template. */
  static final int VERSION = 1;

  private static final String META_KEY = "argus_template_version";

  private final SearchEngineClient client;
  private final HotIndices indices;
  private final HotProperties props;
  private final ObjectMapper mapper;

  public HotTemplates(SearchEngineClient client, HotIndices indices, HotProperties props) {
    this.client = client;
    this.indices = indices;
    this.props = props;
    this.mapper = client.mapper();
  }

  /** Fails for an engine too old for the mapping, or one that isn't the configured store. */
  public static void requireSupported(Engine engine, HotProperties.Store store) {
    Flavor expected =
        store == HotProperties.Store.OPENSEARCH ? Flavor.OPENSEARCH : Flavor.ELASTICSEARCH;
    if (engine.flavor() != expected) {
      throw new IllegalStateException(
          "argus.hot.store="
              + store
              + " but the cluster is "
              + engine.flavor()
              + " "
              + engine.version());
    }
    boolean supported =
        engine.flavor() == Flavor.OPENSEARCH
            ? engine.major() > 2 || (engine.major() == 2 && engine.minor() >= 7)
            : engine.major() > 7 || (engine.major() == 7 && engine.minor() >= 17);
    if (!supported) {
      throw new IllegalStateException(
          "The hot tier needs OpenSearch 2.7+ (flat_object) or Elasticsearch 7.17+; the cluster is "
              + engine.flavor()
              + " "
              + engine.version());
    }
  }

  /** Installs the template unless one of this version or newer is there. */
  public void install() throws IOException, InterruptedException {
    String path = "/_index_template/" + indices.prefix();
    Response existing = client.get(path);
    if (existing.ok()) {
      int installed =
          mapper
              .readTree(existing.body())
              .path("index_templates")
              .path(0)
              .path("index_template")
              .path("_meta")
              .path(META_KEY)
              .asInt(0);
      if (installed >= VERSION) {
        return;
      }
    } else if (existing.status() != 404) {
      throw new IOException(
          "Reading the index template answered "
              + existing.status()
              + ": "
              + existing.excerpt(300));
    }
    Response put = client.json("PUT", path, template(client.flavor()));
    if (!put.ok()) {
      throw new IOException(
          "Installing the index template answered " + put.status() + ": " + put.excerpt(500));
    }
    log.info(
        "Hot tier: index template {} v{} installed for {}",
        indices.prefix(),
        VERSION,
        indices.pattern());
  }

  ObjectNode template(Flavor flavor) {
    ObjectNode root = mapper.createObjectNode();
    root.putArray("index_patterns").add(indices.pattern());
    root.put("priority", 200);
    root.putObject("_meta").put(META_KEY, VERSION);
    ObjectNode template = root.putObject("template");
    template
        .putObject("settings")
        .put("index.codec", "best_compression")
        .put("index.number_of_shards", props.shards())
        .put("index.number_of_replicas", props.replicas())
        .put("index.refresh_interval", props.refreshInterval().toMillis() + "ms");
    template.set("mappings", mapping(flavor));
    return root;
  }

  /** {@code P5_PLAN.md} D5's table. */
  ObjectNode mapping(Flavor flavor) {
    ObjectNode mappings = mapper.createObjectNode();
    mappings.put("dynamic", "strict");
    ObjectNode p = mappings.putObject("properties");
    p.putObject(HotDocument.TIMESTAMP).put("type", "date");
    p.putObject(HotDocument.OBSERVED).put("type", "date");
    p.putObject(HotDocument.EVENT_TIME_MISSING).put("type", "boolean");
    keyword(p, HotDocument.TENANT, true);
    keyword(p, HotDocument.EVENT_ID, true);
    keyword(p, HotDocument.SEVERITY_TEXT, true);
    p.putObject(HotDocument.SEVERITY_NUMBER).put("type", "byte");
    p.putObject(HotDocument.BODY).put("type", "text").put("norms", false);
    keyword(p, HotDocument.TRACE_ID, false);
    keyword(p, HotDocument.SPAN_ID, false);
    keyword(p, HotDocument.SERVICE, true);
    String flat = flavor == Flavor.OPENSEARCH ? "flat_object" : "flattened";
    p.putObject(HotDocument.RESOURCE).put("type", flat);
    p.putObject(HotDocument.ATTRIBUTES).put("type", flat);
    keyword(p, HotDocument.SOURCE_ID, true);
    p.putObject(HotDocument.SOURCE_GENERATION).put("type", "integer").put("index", false);
    p.putObject(HotDocument.SOURCE_OFFSET).put("type", "long").put("index", false);
    keyword(p, HotDocument.SOURCE_FORMAT, true);
    return mappings;
  }

  /** Keywords from Argus or the producer: longer values are kept in the source, not indexed. */
  private static void keyword(ObjectNode properties, String field, boolean docValues) {
    ObjectNode f = properties.putObject(field).put("type", "keyword").put("ignore_above", 1024);
    if (!docValues) {
      f.put("doc_values", false);
    }
  }
}
