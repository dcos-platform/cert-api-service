package com.dcos.platform.certapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Generic paginated response wrapper. Carries page metadata alongside the requested content.
 *
 * @param <T> the type of content items
 */
@Schema(description = "Generic paginated response")
public record PageResponse<T>(
        @Schema(description = "Page content items") List<T> content,
        @Schema(description = "Current page number (0-indexed)") int page,
        @Schema(description = "Number of items in this page") int size,
        @Schema(description = "Total number of items across all pages") long totalElements,
        @Schema(description = "Total number of pages") long totalPages,
        @Schema(description = "Whether this is the first page") boolean first,
        @Schema(description = "Whether this is the last page") boolean last) {}
