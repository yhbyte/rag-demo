# RAG Demo

A Retrieval-Augmented Generation (RAG) demo application built with Spring Boot and Spring AI. This project demonstrates how to build a local, privacy-focused Q&A system that answers questions based on your own documents using local LLMs.

## Features

- **Document Ingestion** — Feed text documents via REST API
- **Intelligent Chunking** — Automatic text splitting with configurable overlap
- **Vector Storage** — PostgreSQL with pgvector for efficient similarity search
- **Local LLMs** — Runs entirely on your machine using Ollama (no external API calls)
- **RAG Pipeline** — Retrieves relevant context before generating answers

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              REST API                                   │
│         POST /api/documents              POST /api/chat                 │
└────────────────┬─────────────────────────────────┬──────────────────────┘
                 │                                 │
                 ▼                                 ▼
┌─────────────────────────────┐    ┌─────────────────────────────────────┐
│      DocumentService        │    │           ChatService               │
│  • Chunk text               │    │  • QuestionAnswerAdvisor            │
│  • Embed via Ollama         │    │  • Vector search + LLM generation   │
│  • Store in PostgreSQL      │    │                                     │
└──────────────┬──────────────┘    └──────────────┬──────────────────────┘
               │                                  │
               ▼                                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                           Spring AI                                      │
│  ┌─────────────────┐  ┌─────────────────────┐  ┌──────────────────────┐  │
│  │   VectorStore   │  │   EmbeddingModel    │  │     ChatClient       │  │
│  │   (pgvector)    │  │  (nomic-embed-text) │  │     (llama3.2)       │  │
│  └────────┬────────┘  └──────────┬──────────┘  └───────────┬──────────┘  │
└───────────┼──────────────────────┼─────────────────────────┼─────────────┘
            │                      │                         │
            ▼                      └────────────┬────────────┘
     ┌──────────────┐                           ▼
     │  PostgreSQL  │                    ┌─────────────┐
     │  + pgvector  │                    │   Ollama    │
     └──────────────┘                    └─────────────┘
```

## Prerequisites

- **Java 21+**
- **Docker** (for PostgreSQL)
- **Ollama** — Install from [ollama.com](https://ollama.com)

### Pull Required Models

```bash
ollama pull nomic-embed-text   # Embedding model (768 dimensions)
ollama pull llama3.2           # Chat model
```

## Quick Start

### 1. Start PostgreSQL with pgvector

```bash
cd docker
docker-compose up -d
```

### 2. Run the Application

```bash
./gradlew bootRun
```

The API will be available at `http://localhost:8080`.

### 3. Ingest Documents

```bash
curl -X POST http://localhost:8080/api/documents \
  -H "Content-Type: application/json" \
  -d '{"content": "Spring Boot is an open-source Java framework used to create microservices. It provides a simpler and faster way to set up, configure, and run applications."}'
```

### 4. Ask Questions

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "What is Spring Boot?"}'
```

**Response:**
```json
{
  "answer": "Spring Boot is an open-source Java framework used to create microservices...",
  "sources": ["Spring Boot is an open-source Java framework..."]
}
```

## How It Works

### Document Ingestion Flow

```
User Text → Chunking → Embedding → Vector Storage
```

#### 1. Text Chunking

Large documents are split into smaller chunks (~500 tokens) with overlap to preserve context:

```
Input: "Spring Boot is a framework... (2000 tokens)"

Output:
├── Chunk 1: tokens 1-500
├── Chunk 2: tokens 400-900    ← 100 token overlap
└── Chunk 3: tokens 800-1300
```

#### 2. Embedding

Each chunk is converted to a 768-dimensional vector using `nomic-embed-text`:

```
"Spring Boot simplifies development"
        ↓
[0.023, -0.156, 0.892, ..., 0.045]  ← 768 floats
```

Vectors capture semantic meaning — similar concepts have similar vectors.

#### 3. Vector Storage

Vectors are stored in PostgreSQL with pgvector extension, indexed using HNSW for fast similarity search.

---

### Question Answering Flow (RAG)

```
Question → Embed → Search → Retrieve Context → Generate Answer
```

#### 1. Question Embedding

The question is converted to a vector using the same embedding model:

```
"What is Spring Boot?"
        ↓
[0.019, -0.148, 0.901, ..., 0.052]
```

#### 2. Similarity Search

PostgreSQL finds the most similar chunks using cosine distance:

```sql
SELECT content FROM vector_store
ORDER BY embedding <=> question_vector
LIMIT 5;
```

#### 3. Context-Augmented Generation

Retrieved chunks are injected into the prompt:

```
┌─────────────────────────────────────────┐
│ System: You are a helpful assistant...  │
│                                         │
│ Context:                                │
│ [Retrieved chunk 1]                     │
│ [Retrieved chunk 2]                     │
│ ...                                     │
├─────────────────────────────────────────┤
│ User: What is Spring Boot?              │
└─────────────────────────────────────────┘
```

#### 4. LLM Generation

`llama3.2` generates an answer using **only** the provided context, ensuring responses are grounded in your data.

---

### Why Two Models?

| Model | Purpose | Input → Output |
|-------|---------|----------------|
| `nomic-embed-text` | Understand meaning | Text → Vector |
| `llama3.2` | Generate language | Prompt → Answer |

## API Reference

### Ingest Document

```http
POST /api/documents
Content-Type: application/json

{
  "content": "Your document text here..."
}
```

**Response:**
```json
{
  "message": "Document ingested successfully",
  "chunksCreated": 3
}
```

### Ask Question

```http
POST /api/chat
Content-Type: application/json

{
  "question": "Your question here?"
}
```

**Response:**
```json
{
  "answer": "Generated answer based on your documents...",
  "sources": ["Relevant chunk 1...", "Relevant chunk 2..."]
}
```

## Configuration

All settings are in `application.properties`:

```properties
# Ollama
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.options.model=llama3.2
spring.ai.ollama.embedding.options.model=nomic-embed-text

# PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/ragdemo
spring.datasource.username=postgres
spring.datasource.password=postgres

# RAG Settings
rag.chunk-size=500      # Tokens per chunk
rag.chunk-overlap=100   # Overlap between chunks
rag.top-k=5             # Number of chunks to retrieve
```

## Project Structure

```
src/main/java/org/havryliuk/rag/ragdemo/
├── config/
│   ├── RagConfig.java          # ChatClient & splitter beans
│   └── RagProperties.java      # Configuration properties
├── controller/
│   ├── ChatController.java     # POST /api/chat
│   └── DocumentController.java # POST /api/documents
├── model/
│   ├── ChatRequest.java
│   ├── ChatResponse.java
│   ├── DocumentRequest.java
│   └── DocumentResponse.java
├── service/
│   ├── ChatService.java        # RAG query logic
│   └── DocumentService.java    # Document ingestion
└── exception/
    └── GlobalExceptionHandler.java

src/main/resources/
├── application.properties
└── prompts/
    └── rag-system-prompt.st    # System prompt template
```

## Tech Stack

| Component | Technology |
|-----------|------------|
| Framework | Spring Boot 3.4 |
| AI Integration | Spring AI 1.0.0-M4 |
| Vector Database | PostgreSQL + pgvector |
| Local LLM Runtime | Ollama |
| Embedding Model | nomic-embed-text |
| Chat Model | llama3.2 |
| Build Tool | Gradle |

## Development

### Build

```bash
./gradlew build
```

### Run Tests

```bash
./gradlew test
```

### Clean Build

```bash
./gradlew clean build
```

## Troubleshooting

### Ollama Connection Refused

Ensure Ollama is running:
```bash
ollama serve
```

### PostgreSQL Connection Failed

Check Docker container status:
```bash
docker ps
docker logs docker-db-1
```

### Out of Memory

Large models require significant RAM. Try a smaller model:
```properties
spring.ai.ollama.chat.options.model=llama3.2:1b
```

## Going to Production

This demo uses REST API for simplicity. For production systems, see **[PRODUCTION_READY.md](PRODUCTION_READY.md)** for:

- Async processing with message queues (Kafka, RabbitMQ)
- Event-driven ingestion patterns
- Batch processing for large datasets
- Change Data Capture (CDC) from databases
- Scaling and performance optimization

## License

MIT
