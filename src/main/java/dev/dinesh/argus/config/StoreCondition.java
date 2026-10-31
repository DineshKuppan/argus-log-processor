package dev.dinesh.argus.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when a store property names a store: set, not blank and not {@code none}. Blank must mean
 * none, since the properties records bind it to {@code NONE}.
 */
abstract class StoreCondition implements Condition {

  private final String property;

  StoreCondition(String property) {
    this.property = property;
  }

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String value = context.getEnvironment().getProperty(property, "none").strip();
    return !value.isEmpty() && !value.equalsIgnoreCase("none");
  }
}
