package com.vibecode.model.domain;

import java.util.List;

/**
 * The single seam between VibeCode and any external model.
 *
 * <p>Every provider is replaceable, and none of them owns project state: a provider takes a request
 * and returns text. Nothing is implemented in this phase — no vendor SDK is even a dependency yet.
 */
public interface ModelProvider {

  ProviderId provider();

  List<ModelDescriptor> availableModels();

  ModelResponse execute(ModelRequest request);
}
