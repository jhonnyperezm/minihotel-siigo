package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Envoltura de paginación que Siigo usa en los listados (customers, invoices, products, users).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaginatedResponse<T>(
        Pagination pagination,
        List<T> results
) {

    public List<T> resultsOrEmpty() {
        return results == null ? List.of() : results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pagination(
            Integer page,
            @JsonProperty("page_size") Integer pageSize,
            @JsonProperty("total_results") Integer totalResults
    ) {
    }
}
