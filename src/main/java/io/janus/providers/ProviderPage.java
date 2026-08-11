package io.janus.providers;

import java.util.List;

/** Stable, intentionally small page shape for the catalogue UI. */
public record ProviderPage(List<ProviderResponse> content, int page, int size, long totalElements, int totalPages) {}
