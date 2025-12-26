# Production-Ready RAG: Data Ingestion Guide

This document describes best practices for building production-grade RAG (Retrieval-Augmented Generation) systems, focusing on data ingestion patterns used in enterprise environments.

## Table of Contents

- [Why REST API Isn't Enough](#why-rest-api-isnt-enough)
- [Production Ingestion Patterns](#production-ingestion-patterns)
- [Recommended Architecture](#recommended-architecture)
- [Implementation Best Practices](#implementation-best-practices)
- [Document Lifecycle Management](#document-lifecycle-management)
- [Scaling Considerations](#scaling-considerations)
- [Technology Choices](#technology-choices)

---

## Why REST API Isn't Enough

The demo application uses a simple REST endpoint for document ingestion:

```bash
POST /api/documents
{"content": "Your text here..."}
```

This works for demos and small-scale use, but fails in production for several reasons:

| Problem | Impact |
|---------|--------|
| **Synchronous processing** | API blocks while embedding (slow, timeouts) |
| **No retry logic** | Failed embeddings are lost |
| **No backpressure** | Sudden load spikes overwhelm the system |
| **No deduplication** | Same document embedded multiple times |
| **Manual triggering** | Doesn't scale with automated data pipelines |
| **Single document** | Inefficient for bulk operations |

### When REST API Is Appropriate

- Admin interfaces for manual uploads
- Small-scale applications (< 1000 documents)
- Testing and debugging
- Webhook receivers (with queue behind them)

---

## Production Ingestion Patterns

### Pattern 1: Batch Processing

**Best for**: Large document sets, scheduled synchronization, initial data loads

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│ Data Source │───▶│  Scheduler  │───▶│  Processor  │───▶│ Vector DB   │
│             │    │  (Cron)     │    │  (Batch)    │    │             │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

**How it works**:
1. Scheduler triggers at defined intervals (hourly, nightly)
2. Batch job fetches documents from source systems
3. Documents are chunked and embedded in batches
4. Results are written to vector database

**Example sources**: File systems, S3 buckets, databases, SharePoint, Confluence exports

**Tools**:
- Apache Airflow (workflow orchestration)
- Spring Batch (Java-native batch processing)
- AWS Glue (serverless ETL)
- Databricks (large-scale processing)

**Pseudocode**:
```java
@Scheduled(cron = "0 0 2 * * *")  // Daily at 2 AM
public void syncDocuments() {
    List<Document> documents = sourceSystem.fetchModifiedSince(lastSyncTime);

    for (List<Document> batch : partition(documents, 100)) {
        List<Document> chunks = chunker.split(batch);
        List<Embedding> embeddings = embeddingModel.embedBatch(chunks);
        vectorStore.upsertBatch(embeddings);
    }

    updateLastSyncTime();
}
```

---

### Pattern 2: Event-Driven (Message Queue)

**Best for**: Real-time updates, high throughput, reliable processing

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   Source    │───▶│   Message   │───▶│  Consumer   │───▶│ Vector DB   │
│   System    │    │   Queue     │    │  Workers    │    │             │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

**How it works**:
1. Source system publishes events when documents change
2. Events are queued in a message broker
3. Consumer workers process events asynchronously
4. Failed events are retried or sent to dead-letter queue

**Benefits**:
- Decouples producers from consumers
- Built-in retry and error handling
- Handles traffic spikes (backpressure)
- Horizontal scaling of workers

**Tools**:
- Apache Kafka (high throughput, event streaming)
- RabbitMQ (flexible routing, lightweight)
- AWS SQS (managed, serverless)
- Redis Streams (simple, fast)

**Pseudocode**:
```java
// Producer: CMS publishes document events
@EventListener
public void onDocumentSaved(DocumentSavedEvent event) {
    kafka.send("document-events", new DocumentMessage(
        event.getId(),
        event.getContent(),
        event.getOperation()  // CREATE, UPDATE, DELETE
    ));
}

// Consumer: Worker processes events
@KafkaListener(topics = "document-events", groupId = "embedding-workers")
public void processDocument(DocumentMessage message) {
    switch (message.operation()) {
        case CREATE, UPDATE -> upsertDocument(message);
        case DELETE -> deleteDocument(message.id());
    }
}

private void upsertDocument(DocumentMessage message) {
    List<Document> chunks = chunker.split(message.content());
    vectorStore.deleteBySourceId(message.id());  // Remove old chunks
    vectorStore.addAll(chunks, Map.of("sourceId", message.id()));
}
```

---

### Pattern 3: File Storage Triggers

**Best for**: User-uploaded files, document management systems

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  S3 Bucket  │───▶│   Trigger   │───▶│  Function   │───▶│ Vector DB   │
│  (Upload)   │    │  (S3 Event) │    │  (Lambda)   │    │             │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

**How it works**:
1. User uploads file to cloud storage (S3, Azure Blob, GCS)
2. Storage emits event on new/modified files
3. Serverless function triggered automatically
4. Function parses file, chunks, embeds, and stores

**Supported file types** (with appropriate parsers):
- PDF (Apache PDFBox, PyMuPDF)
- Word documents (Apache POI)
- HTML (Jsoup)
- Markdown
- Plain text

**Tools**:
- AWS Lambda + S3 Event Notifications
- Azure Functions + Blob Triggers
- Google Cloud Functions + Cloud Storage Triggers

**Pseudocode**:
```java
// AWS Lambda handler
public void handleS3Event(S3Event event) {
    for (S3EventRecord record : event.getRecords()) {
        String bucket = record.getS3().getBucket().getName();
        String key = record.getS3().getObject().getKey();

        InputStream file = s3.getObject(bucket, key);
        String content = documentParser.parse(file, getFileType(key));

        List<Document> chunks = chunker.split(content);
        chunks.forEach(c -> c.metadata().put("source", "s3://" + bucket + "/" + key));

        vectorStore.addAll(chunks);
    }
}
```

---

### Pattern 4: Change Data Capture (CDC)

**Best for**: Database as source of truth, real-time sync

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│ Source DB   │───▶│    CDC      │───▶│  Processor  │───▶│ Vector DB   │
│ (WAL/Binlog)│    │ (Debezium)  │    │             │    │             │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

**How it works**:
1. CDC tool monitors database transaction log (WAL, binlog)
2. Changes are captured as events (INSERT, UPDATE, DELETE)
3. Events are streamed to processing pipeline
4. Vector database stays in sync with source

**Benefits**:
- Zero impact on source database performance
- Captures all changes (no polling gaps)
- Maintains consistency between source and vectors

**Tools**:
- Debezium (open-source, Kafka-based)
- AWS DMS (managed service)
- Fivetran (SaaS)
- Airbyte (open-source)

**Example Debezium configuration**:
```json
{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "database.hostname": "source-db",
  "database.dbname": "products",
  "table.include.list": "public.articles",
  "transforms": "route",
  "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
  "transforms.route.regex": ".*",
  "transforms.route.replacement": "article-changes"
}
```

---

## Recommended Architecture

For production systems, combine patterns based on your data sources:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           DATA SOURCES                                  │
├─────────────┬─────────────┬─────────────┬─────────────┬────────────────┤
│ Confluence  │ SharePoint  │  S3/Blob    │  Databases  │  REST APIs     │
└──────┬──────┴──────┬──────┴──────┬──────┴──────┬──────┴───────┬────────┘
       │             │             │             │              │
       ▼             ▼             ▼             ▼              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        CONNECTOR LAYER                                  │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐ │
│  │ Scheduled │ │ Scheduled │ │  S3 Event │ │  Debezium │ │  Webhook  │ │
│  │   Sync    │ │   Sync    │ │  Trigger  │ │   CDC     │ │  Receiver │ │
│  └─────┬─────┘ └─────┬─────┘ └─────┬─────┘ └─────┬─────┘ └─────┬─────┘ │
└────────┼─────────────┼─────────────┼─────────────┼─────────────┼───────┘
         │             │             │             │             │
         └─────────────┴─────────────┴──────┬──────┴─────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        MESSAGE QUEUE                                    │
│                     (Kafka / SQS / RabbitMQ)                            │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Topic: document-events                                          │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐   │   │
│  │  │ Event 1 │ │ Event 2 │ │ Event 3 │ │ Event 4 │ │ Event 5 │   │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      PROCESSING WORKERS                                 │
│                    (Horizontally Scalable)                              │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Worker Pool                                                      │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐            │  │
│  │  │ Worker 1 │ │ Worker 2 │ │ Worker 3 │ │ Worker N │            │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘            │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│  Pipeline Steps:                                                        │
│  1. Parse document (PDF, DOCX, HTML → plain text)                       │
│  2. Clean and normalize text                                            │
│  3. Split into chunks (with overlap)                                    │
│  4. Generate embeddings (batched API calls)                             │
│  5. Enrich with metadata                                                │
│  6. Upsert to vector database                                           │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        VECTOR DATABASE                                  │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Documents Table                                                 │   │
│  │  ┌────────────┬───────────────┬────────────┬──────────────────┐ │   │
│  │  │     ID     │   Embedding   │  Content   │     Metadata     │ │   │
│  │  ├────────────┼───────────────┼────────────┼──────────────────┤ │   │
│  │  │ uuid-1     │ [0.1, 0.2...] │ "chunk..." │ {source, date}   │ │   │
│  │  │ uuid-2     │ [0.3, 0.1...] │ "chunk..." │ {source, date}   │ │   │
│  │  └────────────┴───────────────┴────────────┴──────────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Options: pgvector │ Pinecone │ Weaviate │ Milvus │ Qdrant             │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Implementation Best Practices

### 1. Asynchronous Processing

Never embed documents synchronously in API handlers.

```java
// ❌ Bad: Synchronous embedding blocks the request
@PostMapping("/documents")
public Response ingest(DocumentRequest request) {
    vectorStore.add(embed(request.content()));  // Blocks for seconds
    return Response.ok();
}

// ✅ Good: Queue for async processing
@PostMapping("/documents")
public Response ingest(DocumentRequest request) {
    String jobId = UUID.randomUUID().toString();
    messageQueue.send(new IngestionJob(jobId, request.content()));
    return Response.accepted(jobId);  // Returns immediately
}

// Separate worker processes the queue
@KafkaListener(topics = "ingestion-jobs")
public void process(IngestionJob job) {
    try {
        List<Document> chunks = chunker.split(job.content());
        vectorStore.addAll(chunks);
        statusStore.markComplete(job.id());
    } catch (Exception e) {
        statusStore.markFailed(job.id(), e.getMessage());
        throw e;  // Triggers retry
    }
}
```

### 2. Idempotency and Deduplication

Prevent duplicate embeddings for the same content.

```java
public class DeduplicatingIngestionService {

    public void ingest(Document document) {
        String contentHash = sha256(document.content());

        // Check if already processed
        if (vectorStore.existsByHash(contentHash)) {
            log.info("Document already exists, skipping: {}", contentHash);
            return;
        }

        List<Document> chunks = chunker.split(document);
        chunks.forEach(c -> c.metadata().put("contentHash", contentHash));

        vectorStore.addAll(chunks);
    }

    private String sha256(String content) {
        return Hashing.sha256()
            .hashString(content, StandardCharsets.UTF_8)
            .toString();
    }
}
```

### 3. Incremental Updates

Only re-embed documents that have changed.

```java
public class IncrementalSyncService {

    public void sync(String sourceId, String newContent) {
        String newHash = sha256(newContent);

        Optional<DocumentMetadata> existing = vectorStore.findMetadataBySourceId(sourceId);

        if (existing.isPresent() && existing.get().hash().equals(newHash)) {
            log.debug("Content unchanged, skipping: {}", sourceId);
            return;
        }

        // Delete old chunks for this source document
        vectorStore.deleteBySourceId(sourceId);

        // Add new chunks
        List<Document> chunks = chunker.split(newContent);
        chunks.forEach(c -> {
            c.metadata().put("sourceId", sourceId);
            c.metadata().put("contentHash", newHash);
            c.metadata().put("updatedAt", Instant.now().toString());
        });

        vectorStore.addAll(chunks);
    }
}
```

### 4. Batch Embedding

Embedding APIs are more efficient with batches.

```java
public class BatchEmbeddingService {

    private static final int BATCH_SIZE = 100;

    public void embedAndStore(List<Document> documents) {
        // Partition into batches
        List<List<Document>> batches = Lists.partition(documents, BATCH_SIZE);

        for (List<Document> batch : batches) {
            // Single API call for entire batch
            List<float[]> embeddings = embeddingModel.embed(
                batch.stream().map(Document::content).toList()
            );

            // Store with embeddings
            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).setEmbedding(embeddings.get(i));
            }

            vectorStore.addAll(batch);
        }
    }
}
```

### 5. Error Handling and Dead Letter Queue

Handle failures gracefully with retry and DLQ.

```java
@Configuration
public class KafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, IngestionJob>
            kafkaListenerContainerFactory() {

        ConcurrentKafkaListenerContainerFactory<String, IngestionJob> factory =
            new ConcurrentKafkaListenerContainerFactory<>();

        // Retry 3 times with exponential backoff
        factory.setCommonErrorHandler(new DefaultErrorHandler(
            new DeadLetterPublishingRecoverer(kafkaTemplate),
            new ExponentialBackOff(1000L, 2.0)  // 1s, 2s, 4s
        ));

        return factory;
    }
}

// Monitor dead letter queue for manual intervention
@KafkaListener(topics = "ingestion-jobs.DLT")
public void handleFailedJobs(IngestionJob job) {
    log.error("Job permanently failed: {}", job.id());
    alertingService.notify("Ingestion job failed", job);
}
```

---

## Document Lifecycle Management

### Metadata Schema

Store rich metadata with each chunk for filtering and management.

```java
public record DocumentMetadata(
    String id,              // Unique chunk ID
    String sourceId,        // Original document ID
    String sourceType,      // "confluence", "s3", "database"
    String sourceUrl,       // Link to original document
    String contentHash,     // For deduplication
    String tenantId,        // Multi-tenancy support
    Instant createdAt,      // When first ingested
    Instant updatedAt,      // Last update time
    Instant expiresAt,      // TTL for auto-cleanup (optional)
    Map<String, String> custom  // Domain-specific metadata
) {}
```

### Cleanup Strategies

```java
@Scheduled(cron = "0 0 3 * * *")  // Daily at 3 AM
public void cleanupExpiredDocuments() {
    vectorStore.deleteByFilter("expiresAt < " + Instant.now());
}

@Scheduled(cron = "0 0 4 * * SUN")  // Weekly on Sunday at 4 AM
public void cleanupOrphanedChunks() {
    // Find chunks whose source documents no longer exist
    List<String> orphanedIds = vectorStore.findOrphanedChunks();
    vectorStore.deleteByIds(orphanedIds);
}
```

### Versioning

Track document versions for audit and rollback.

```java
public void ingestWithVersioning(Document document, int version) {
    // Keep previous versions (optional)
    vectorStore.updateMetadata(
        document.sourceId(),
        Map.of("currentVersion", false)
    );

    List<Document> chunks = chunker.split(document);
    chunks.forEach(c -> {
        c.metadata().put("sourceId", document.sourceId());
        c.metadata().put("version", version);
        c.metadata().put("currentVersion", true);
    });

    vectorStore.addAll(chunks);
}

// Query only current versions
public List<Document> search(String query) {
    return vectorStore.similaritySearch(
        SearchRequest.query(query)
            .withTopK(5)
            .withFilterExpression("currentVersion == true")
    );
}
```

---

## Scaling Considerations

### Horizontal Scaling

```
                    ┌─────────────┐
                    │   Queue     │
                    │  (Kafka)    │
                    └──────┬──────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
           ▼               ▼               ▼
    ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
    │  Worker 1   │ │  Worker 2   │ │  Worker N   │
    │  (Pod)      │ │  (Pod)      │ │  (Pod)      │
    └─────────────┘ └─────────────┘ └─────────────┘
           │               │               │
           └───────────────┼───────────────┘
                           │
                           ▼
                    ┌─────────────┐
                    │ Vector DB   │
                    │ (Cluster)   │
                    └─────────────┘
```

**Worker scaling**: Add more consumer pods when queue depth grows
**Vector DB scaling**: Use managed services (Pinecone, Weaviate Cloud) or clustered deployments

### Performance Benchmarks

| Operation | Typical Latency | Throughput |
|-----------|-----------------|------------|
| Embedding (single) | 50-200ms | 5-20/sec |
| Embedding (batch 100) | 500-2000ms | 50-200/sec |
| Vector insert | 1-10ms | 1000+/sec |
| Similarity search | 10-50ms | 100+/sec |

### Cost Optimization

1. **Batch embeddings**: Reduce API calls
2. **Cache embeddings**: Don't re-embed unchanged content
3. **Compress vectors**: Use quantization for large datasets
4. **Tiered storage**: Move old vectors to cheaper storage

---

## Technology Choices

### Message Queues

| Technology | Best For | Considerations |
|------------|----------|----------------|
| **Apache Kafka** | High throughput, event streaming | Complex setup, needs Zookeeper/KRaft |
| **RabbitMQ** | Flexible routing, moderate scale | Easier setup, lower throughput |
| **AWS SQS** | Serverless, AWS ecosystem | Managed, pay-per-use |
| **Redis Streams** | Simple use cases, low latency | Limited durability |

### Vector Databases

| Technology | Best For | Considerations |
|------------|----------|----------------|
| **pgvector** | Existing PostgreSQL, simplicity | Limited scale, good enough for most |
| **Pinecone** | Managed, large scale | SaaS, vendor lock-in |
| **Weaviate** | Hybrid search, GraphQL | Open-source, self-hosted option |
| **Milvus** | Large scale, on-premise | Complex operations |
| **Qdrant** | Performance, Rust-based | Growing ecosystem |

### Orchestration

| Technology | Best For | Considerations |
|------------|----------|----------------|
| **Apache Airflow** | Complex DAGs, scheduling | Python-based, powerful |
| **Spring Batch** | Java ecosystem, transactions | Native Spring integration |
| **AWS Step Functions** | Serverless, AWS | Managed, visual workflow |
| **Temporal** | Long-running workflows | Durable execution |

---

## Checklist: Production Readiness

Before going to production, ensure:

- [ ] **Async processing**: No synchronous embedding in API handlers
- [ ] **Message queue**: Decoupled ingestion with retry logic
- [ ] **Deduplication**: Content hashing to prevent duplicates
- [ ] **Incremental updates**: Only re-embed changed documents
- [ ] **Batch operations**: Efficient embedding API usage
- [ ] **Error handling**: Dead letter queue for failed jobs
- [ ] **Monitoring**: Metrics for queue depth, latency, errors
- [ ] **Metadata**: Source tracking, timestamps, tenant isolation
- [ ] **Cleanup**: TTL or scheduled cleanup for stale data
- [ ] **Scaling plan**: Horizontal scaling strategy defined
- [ ] **Backup**: Vector database backup and recovery tested

---

## Next Steps

To evolve this demo into a production system:

1. **Add Kafka/RabbitMQ** for async processing
2. **Implement connectors** for your data sources
3. **Add monitoring** (Prometheus, Grafana)
4. **Implement multi-tenancy** if needed
5. **Set up CI/CD** for worker deployments
6. **Load test** with realistic data volumes

---

## References

- [Spring AI Documentation](https://docs.spring.io/spring-ai/reference/)
- [pgvector GitHub](https://github.com/pgvector/pgvector)
- [Ollama Documentation](https://ollama.com)
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [RAG Best Practices (Anthropic)](https://docs.anthropic.com/claude/docs/retrieval-augmented-generation)
