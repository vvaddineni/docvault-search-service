package com.docvault.service;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.SearchDocument;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.indexes.models.*;
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
        AzureKeyCredential credential = new AzureKeyCredential(apiKey);

        // Create index if it doesn't exist
        SearchIndexClient indexClient = new SearchIndexClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildClient();
        ensureIndex(indexClient);

        searchClient = new SearchClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .indexName(indexName)
                .buildClient();
        log.info("[Search] SearchClient ready → {} / {}", endpoint, indexName);
    }

    private void ensureIndex(SearchIndexClient indexClient) {
        try {
            indexClient.getIndex(indexName);
            log.info("[Search] Index '{}' already exists", indexName);
        } catch (Exception e) {
            log.info("[Search] Creating index '{}'…", indexName);
            SearchIndex index = new SearchIndex(indexName, List.of(
                new SearchField("id",            SearchFieldDataType.STRING).setKey(true).setFilterable(true),
                new SearchField("title",         SearchFieldDataType.STRING).setSearchable(true).setFilterable(true).setSortable(true),
                new SearchField("author",        SearchFieldDataType.STRING).setSearchable(true).setFilterable(true),
                new SearchField("department",    SearchFieldDataType.STRING).setSearchable(true).setFilterable(true).setFacetable(true),
                new SearchField("description",   SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("extractedText", SearchFieldDataType.STRING).setSearchable(true),
                new SearchField("mimeType",      SearchFieldDataType.STRING).setFilterable(true).setFacetable(true),
                new SearchField("storageTier",   SearchFieldDataType.STRING).setFilterable(true).setFacetable(true),
                new SearchField("fileSizeBytes", SearchFieldDataType.INT64).setFilterable(true).setSortable(true),
                new SearchField("uploadedAt",    SearchFieldDataType.DATE_TIME_OFFSET).setFilterable(true).setSortable(true),
                new SearchField("tags",          SearchFieldDataType.collection(SearchFieldDataType.STRING)).setSearchable(true).setFilterable(true)
            ));
            index.setSuggesters(List.of(
                new SearchSuggester("docvault-suggester", List.of("title", "author"))
            ));
            indexClient.createIndex(index);
            log.info("[Search] Index '{}' created", indexName);
        }
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
            item.setScore(r.getScore());
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
                values.forEach(v -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("count", v.getCount());
                    if (v.getAdditionalProperties() != null) {
                        entry.putAll(v.getAdditionalProperties());
                    }
                    fv.add(entry);
                });
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
        searchClient.deleteDocuments(List.of(Map.of("id", id)));
        log.debug("[Search] Deleted from index: {}", id);
    }

    private String escape(String value) {
        return value.replace("'", "''");
    }
}
