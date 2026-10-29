package dev.dinesh.argus.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.config.ExportProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** The configured export sources ({@code P3_PLAN.md} D9), each visible to its own tenant only. */
@Slf4j
@Component
public class ExportSources {

  /** A configured source. */
  public record Named(String name, String tenantId, ExportSource source) {}

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private final Map<String, Named> byName;

  @Autowired
  public ExportSources(ExportProperties props, ObjectMapper mapper) {
    this(build(props, mapper));
  }

  public ExportSources(List<Named> sources) {
    this.byName =
        sources.stream().collect(Collectors.toUnmodifiableMap(Named::name, Function.identity()));
  }

  /** The tenant's source of that name; another tenant's source is reported as absent. */
  public Optional<Named> forTenant(String tenantId, String name) {
    return Optional.ofNullable(name == null ? null : byName.get(name))
        .filter(s -> s.tenantId().equals(tenantId));
  }

  private static List<Named> build(ExportProperties props, ObjectMapper mapper) {
    if (props.sources().isEmpty()) {
      return List.of();
    }
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return props.sources().stream()
        .map(
            s -> {
              ExportSource source =
                  switch (s.type()) {
                    case ELASTICSEARCH -> new ElasticsearchExportSource(s, http, mapper);
                    case CORALOGIX ->
                        throw new IllegalArgumentException(
                            "argus.export.sources "
                                + s.name()
                                + ": coralogix is not supported yet");
                  };
              log.info("Export source configured {}", s);
              return new Named(s.name(), s.tenant(), source);
            })
        .toList();
  }
}
