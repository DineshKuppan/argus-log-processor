package dev.dinesh.argus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ExportPropertiesTest {

  private static ExportProperties bind(Map<String, String> properties) {
    return new Binder(new MapConfigurationPropertySource(properties))
        .bindOrCreate("argus.export", ExportProperties.class);
  }

  @Test
  void bindsSourcesWithDefaults() {
    ExportProperties props =
        bind(
            Map.of(
                "argus.export.sources[0].name", "elk",
                "argus.export.sources[0].tenant", "acme",
                "argus.export.sources[0].type", "elasticsearch",
                "argus.export.sources[0].url", "https://es.internal:9200",
                "argus.export.sources[0].index", "logs-*",
                "argus.export.sources[0].api-key", "secret-key",
                "argus.export.sources[1].name", "cx",
                "argus.export.sources[1].tenant", "acme",
                "argus.export.sources[1].type", "coralogix",
                "argus.export.sources[1].url", "https://api.eu2.coralogix.com"));

    ExportProperties.Source elk = props.sources().get(0);
    assertEquals(ExportProperties.Type.ELASTICSEARCH, elk.type());
    assertEquals("@timestamp", elk.timestampField());
    assertEquals(5_000, elk.pageSize());
    assertEquals(Duration.ofMinutes(10), elk.keepAlive());
    ExportProperties.Source cx = props.sources().get(1);
    assertEquals(ExportProperties.Type.CORALOGIX, cx.type());
    assertEquals("frequent_search", cx.tier());
    assertEquals(Duration.ofHours(1), cx.initialWindow());
    assertTrue(bind(Map.of()).sources().isEmpty());
  }

  @Test
  void credentialsNeverAppearInToString() {
    ExportProperties props =
        bind(
            Map.of(
                "argus.export.sources[0].name", "elk",
                "argus.export.sources[0].tenant", "acme",
                "argus.export.sources[0].type", "elasticsearch",
                "argus.export.sources[0].url", "https://es.internal:9200",
                "argus.export.sources[0].index", "logs-*",
                "argus.export.sources[0].username", "argus",
                "argus.export.sources[0].password", "hunter2",
                "argus.export.sources[0].api-key", "secret-key"));

    String text = props.toString() + props.sources().get(0);
    assertFalse(text.contains("hunter2"), text);
    assertFalse(text.contains("secret-key"), text);
  }

  @Test
  void invalidSourcesFailAtStartup() {
    Map<String, String> base =
        Map.of(
            "argus.export.sources[0].name", "elk",
            "argus.export.sources[0].tenant", "acme",
            "argus.export.sources[0].type", "elasticsearch",
            "argus.export.sources[0].url", "https://es.internal:9200");
    assertThrows(BindException.class, () -> bind(base), "elasticsearch needs an index");
    assertThrows(
        BindException.class,
        () ->
            bind(
                Map.of(
                    "argus.export.sources[0].name", "cx",
                    "argus.export.sources[0].tenant", "acme",
                    "argus.export.sources[0].type", "coralogix",
                    "argus.export.sources[0].url", "https://api.eu2.coralogix.com",
                    "argus.export.sources[0].tier", "hot")));
    assertThrows(
        BindException.class,
        () ->
            bind(
                Map.of(
                    "argus.export.sources[0].name", "bad name",
                    "argus.export.sources[0].tenant", "acme",
                    "argus.export.sources[0].type", "coralogix",
                    "argus.export.sources[0].url", "https://api.eu2.coralogix.com")));
    assertThrows(
        BindException.class,
        () ->
            bind(
                Map.of(
                    "argus.export.sources[0].name", "cx",
                    "argus.export.sources[0].tenant", "acme",
                    "argus.export.sources[0].type", "coralogix",
                    "argus.export.sources[0].url", "https://api.eu2.coralogix.com",
                    "argus.export.sources[1].name", "cx",
                    "argus.export.sources[1].tenant", "acme",
                    "argus.export.sources[1].type", "coralogix",
                    "argus.export.sources[1].url", "https://api.eu2.coralogix.com")),
        "names are unique");
  }
}
