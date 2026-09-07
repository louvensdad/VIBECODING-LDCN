package com.vibecode.model.domain;

import java.util.Set;

/** A concrete model offered by a provider. */
public record ModelDescriptor(
    ProviderId provider,
    String modelId,
    String displayName,
    Set<ModelCapability> capabilities,
    int contextWindowTokens) {

  public ModelDescriptor {
    capabilities = Set.copyOf(capabilities);
  }

  public boolean supports(ModelCapability capability) {
    return capabilities.contains(capability);
  }
}
