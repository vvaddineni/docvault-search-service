package com.docvault.service;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.models.*;
import com.azure.core.credential.AzureKeyCredential;
import com.docvault.dto.SearchRequestDto;
import com.docvault.dto.SearchResponseDto;
import com.docvault.dto.SearchResultItem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Azure AI Search integration service.
 *
 * Features:
 * - Full-text search across title, author, description, extractedText
 * - Semantic ranking (requires Azure AI Search Standard tier)
 * - Faceted filtering by department, storageTier, mimeType
 * - Autocomplete suggestions
 * - Date range filtering
 */
@Service
public class AzureSearchService {

    private static final Logger log = LoggerFactory.getLogger(AzureSearchService.class);

    @Value("${azure.search.endpoint}")  private String endpoint;
    @Value("${azure.search.api-key}")   private String apiKey;
    @Value("${azure.search.index}")     private String indexName;

    private SearchClient searchClient;

    @PostConstruct
    public void init() {
        searchClient = new SearchClientBuilder()
                .endpoint(endpoint)
                .credential(new AzureKeyCredential(apiKey))
                .indexName(indexName)
                .buildClient();
        log.info("[Search] SearchClient ready → {} / {}", endpoint, indexName);
    }

    // ── Full-text search ──────────────────────────────────────────────────
    public SearchResponseDto search(SearchRequestDto req) {
        SearchOptions opts = new SearchOptions()
                .setTop(req.getSize() != null ? req.getSize() : 20)
                .setSkip(req.getFrom() != null ? req.getFrom() : 0)
                .setIncludeTotalCount(true)
                .setHighlightFields("title", "description", "extractedText")
                .setHighlightPreTag("<mark>")
                .setHighlightPostTag("</mark>")
                .setFacets(
                    "department,count:10",
                    "storageTier,count:5",
                    "mimeType,count:10"
                )
                .setSearchFields("title", "author", "description", "tags", "extractedText", "department");

        // Filters
        List<String> filters = new ArrayList<>();
        if (req.getDepartment() != null) filters.add("department eq '" + escape(req.getDepartment()) + "'");
        if (req.getTier()       != null) filters.add("storageTier eq '"  + escape(req.getTier())       + "'");
        if (req.getDateFrom()   != null) filters.add("uploadedAt ge " + req.getDateFrom() + "T00:00:00Z");
        if (req.getDateTo()     != null) filters.add("uploadedAt le " + req.getDateTo()   + "T23:59:59Z");
        if (!filters.isEmpty()) opts.setFilter(String.join(" and ", filters));

        // Semantic ranking if enabled on the index
        try {
            opts.setQueryType(QueryType.SEMANTIC)
                .setSemanticConfigurationName("docvault-semantic");
        } catch (Exception ignored) {
            // Falls back to full-text if semantic config not provisioned
        }

        var results = searchClient.search(
                req.getQ() != null ? req.getQ() : "*", opts, null);

        List<SearchResultItem> items = new ArrayList<>();
        results.forEach(r -> {
            SearchResultItem item = new SearchResultItem();
            SearchDocument doc = r.getDocument(SearchDocument.class);
            item.setId((String) doc.get("id"));
            item.setTitle((String) doc.get("title"));
            item.setAuthor((String) doc.get("author"));
            item.setDepartment((String) doc.get("department"));
            item.setStorageTier((String) doc.get("storageTier"));
            item.setMimeType((String) doc.get("mimeType"));
            item.setFileSizeBytes(doc.get("fileSizeBytes") instanceof Number n ? n.longValue() : 0);
            item.setUploadedAt((String) doc.get("uploadedAt"));
            item.setScore(r.getScore() != null ? r.getScore() : 0.0);
            if (r.getHighlights() != null) {
                item.setHighlights(r.getHighlights());
            }
            items.add(item);
        });

        // Facets
        Map<String, List<Map<String, Object>>> facets = new LinkedHashMap<>();
        if (results.getFacets() != null) {
            results.getFacets().forEach((facet, values) -> {
                List<Map<String, Object>> fv = new ArrayList<>();
                values.forEach(v -> fv.add(Map.of("value", v.getValue(), "count", v.getCount())));
                facets.put(facet, fv);
            });
        }

        SearchResponseDto response = new SearchResponseDto();
        response.setResults(items);
        response.setCount(results.getTotalCount() != null ? results.getTotalCount() : (long) items.size());
        response.setFacets(facets);
        return response;
    }

    // ── Autocomplete suggestions ──────────────────────────────────────────
    public List<String> suggest(String prefix) {
        if (prefix == null || prefix.length() < 2) return List.of();
        try {
            SuggestOptions opts = new SuggestOptions()
                    .setTop(8).setHighlightPreTag("").setHighlightPostTag("");
            return searchClient.suggest(prefix, "docvault-suggester", opts, null)
                    .stream()
                    .map(r -> (String) r.getDocument(SearchDocument.class).get("title"))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("[Search] Suggest failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Index a document ──────────────────────────────────────────────────
    public void indexDocument(Map<String, Object> doc) {
        searchClient.uploadDocuments(List.of(doc));
        log.debug("[Search] Indexed document: {}", doc.get("id"));
    }

    // ── Delete from index ─────────────────────────────────────────────────
    public void deleteDocument(String id) {
        searchClient.deleteDocuments("id", List.of(id));
        log.debug("[Search] Deleted from index: {}", id);
    }

    private String escape(String value) {
        return value.replace("'", "''");
    }
}
