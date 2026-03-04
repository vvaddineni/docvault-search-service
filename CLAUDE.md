# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Layout

This is a multi-repo project. The four services live in sibling directories:

```
docvault/
├── docvault-frontend/              # React SPA (port 3000)
├── docvault-bff/                   # Node.js BFF (port 4000)
├── documentservice/
│   └── docvault-document-service/  # Spring Boot (port 8080)
└── docvault-search-service/        # Spring Boot (this repo, port 8081)
```

## Commands

```bash
mvn test                            # run all tests
mvn test -Dtest=SearchServiceTest   # run a single test class
mvn package -DskipTests             # build JAR → target/*.jar
mvn spring-boot:run                 # run locally (requires Azure env vars below)
```

## Architecture

### Request Flow
```
Browser → React SPA (3000)
       → BFF /api/search (4000)
         → Search Service /v1/search (8081)   ← this repo
           → Azure AI Search (index: documents)

Document Service (8080) → POST /v1/search/index  [async, after upload]
                       → DELETE /v1/search/{id}  [on document delete]
```

### Source Layout
```
src/main/java/com/docvault/
├── controller/   SearchController.java   @RequestMapping("/v1/search")
├── service/      AzureSearchService.java (index management, search, suggest)
├── dto/          SearchRequestDto, SearchResponseDto, SearchResultItem
└── SearchServiceApplication.java
```

### API Endpoints
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/search` | Full-text search with semantic ranking and facets |
| `GET` | `/v1/search/suggest` | Autocomplete suggestions (min 2 chars) |
| `POST` | `/v1/search/index` | Index or re-index a document (called by Document Service) |
| `DELETE` | `/v1/search/{id}` | Remove document from index |

**Search query params**: `q` (query string), `department`, `tier`, `dateFrom`, `dateTo`, `from` (offset, default 0), `size` (page size, default 20)

Swagger UI: `http://localhost:8081/swagger-ui.html`

## Key Patterns

### Index Auto-Creation
`AzureSearchService.ensureIndex()` is annotated `@PostConstruct` and runs at startup. It creates the `documents` index with a suggester if it does not already exist. No manual index creation is needed.

The index fields include: `id`, `title`, `content`, `extractedText`, `department`, `tier`, `uploadedAt`, plus any other fields sent by Document Service in the index payload.

### Indexing Documents
Document Service calls `POST /v1/search/index` asynchronously after upload. The request body is a `Map<String, Object>` containing all document fields, including `extractedText` from Apache Tika.

```java
// SearchController
@PostMapping("/index")
public ResponseEntity<Void> indexDocument(@RequestBody Map<String, Object> document)
```

### Semantic Ranking
Search uses Azure AI Search's semantic ranking for relevance. Faceted results include `department` and `tier` buckets in the response.

### Suggester
Autocomplete is configured on the `title` and `content` fields. The `GET /v1/search/suggest?q=<prefix>` endpoint requires at least 2 characters.

## Configuration

### application.yml highlights
- Server port: **8081**
- Azure Search index name: `documents`
- Actuator endpoints: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`
- Logging: DEBUG for `com.docvault`, WARN for Azure SDK

### Required Environment Variables
| Variable | Default | Description |
|----------|---------|-------------|
| `AZURE_SEARCH_ENDPOINT` | `https://docvault-search.search.windows.net` | Azure AI Search endpoint |
| `AZURE_SEARCH_API_KEY` | — | Search service API key |

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`):
1. `mvn test` — must pass
2. Build + push Docker image → `ghcr.io/<owner>/docvault-search-service:latest` and `:<sha>`
3. Deploy to Azure Container App: `docvault-search-service` in `docvault-dev-rg`
4. Sets `SEARCH_SERVICE_URL` on the Document Service Container App so it can call back here

**Required GitHub Secrets**: `AZURE_CREDENTIALS`, `AZURE_RESOURCE_GROUP`, `GHCR_TOKEN`

## Docker

Multi-stage build: Maven 3.9 builder → Eclipse Temurin 21 JRE runtime.
- Non-root user: `spring`
- JVM flags: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`
- Healthcheck: `GET /actuator/health` (30s interval, 5s timeout)
- Exposes port **8081**
