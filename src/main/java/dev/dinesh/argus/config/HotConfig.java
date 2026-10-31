package dev.dinesh.argus.config;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the hot search tier ({@code P5_PLAN.md}) when {@code argus.hot.store} names an engine. With
 * {@code none} (the default) nothing here is created, and search answers 404.
 */
@Configuration
@Conditional(HotConfig.Enabled.class)
public class HotConfig {

  /** {@code argus.hot.store} names an engine (not blank, not {@code none}). */
  static final class Enabled extends StoreCondition {
    Enabled() {
      super("argus.hot.store");
    }
  }
}
