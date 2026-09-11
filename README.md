# PDF Summarizer

Converts PDF documents into Q&A flashcards for spaced repetition (e.g. Obsidian). The pipeline extracts text chapter by chapter, sends it to an LLM, and writes the resulting cards to output files.

## Supported LLM providers

| Provider | Config key | API key env var |
| --- | --- | --- |
| Ollama (local) | `ollama.enabled` | — |
| OpenAI | `openai.enabled` | `OPENAI_API_KEY` |
| Anthropic Claude | `claude.enabled` | `ANTHROPIC_API_KEY` |

Enable exactly one provider at a time in `src/main/resources/config.json`.

### Setting API keys

**macOS/Linux** — add to your shell profile (`~/.zshrc`, `~/.bashrc`, etc.) so it persists across sessions:

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
export OPENAI_API_KEY="sk-..."
```

Then reload the profile:

```bash
source ~/.zshrc
```

**Temporary** (current terminal session only):

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
```

## Build & run

```bash
# Build
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn package -DskipTests

# Run
java -jar target/pdfsummarizer-1.0-SNAPSHOT.jar <path-to-pdf> <output-directory>
```

## Pipeline modes

Controlled by `ollama.pipeline3StepsMode` in `config.json`.

**Single-stage** (`pipeline3StepsMode: false`) — one LLM call per page/batch, using `prompt.txt`.

**Three-stage** (`pipeline3StepsMode: true`) — three sequential LLM calls per batch:
1. Extract concepts (`prompt_step1_concepts.txt`)
2. Generate cards from concepts (`prompt_step2_cards.txt`)
3. Refine and deduplicate (`prompt_step3_refine.txt`)

Intermediate outputs are saved to `<output-directory>/debug/` for inspection.

## Key config options (`config.json`)

```jsonc
{
  "ollama": {
    "enabled": true,
    "servers": 2,               // number of Ollama instances to distribute load across
    "modelsPerServer": ["llama3.1:8b"],
    "concurrency": 2,           // parallel requests per server
    "localBatching": false,     // pack multiple pages into one LLM request
    "pipeline3StepsMode": true
  },
  "chunking": {
    "maxTokensPerChunk": 12000, // estimated as ceil(chars / 4)
    "minTokensPerChunk": 2000
  },
  "cards": {
    "maxCardsPerChunk": 20,
    "maxConceptsPerPage": 10    // three-stage only
  },
  "preview": {
    "enabled": false            // generate side-by-side preview PDF
  }
}
```

## Running multiple Ollama servers (macOS)

**Automatic** — edit `config.json`, then run the helper script:

```bash
src/main/java/nl/adgroot/pdfsummarizer/llm/RestartOllama.sh
```

This stops all running Ollama processes and opens new terminal windows with the configured number of servers.

**Manual** — the default server listens on port 11434. Each additional server needs a different port:

```bash
# Server 1 (default)
OLLAMA_NUM_PARALLEL=1 OLLAMA_MAX_LOADED_MODELS=1 ollama serve

# Server 2
OLLAMA_HOST=127.0.0.1:11435 OLLAMA_NUM_PARALLEL=1 OLLAMA_MAX_LOADED_MODELS=1 ollama serve
```

To stop Ollama before changing server parameters:

```bash
pkill -f ollama
brew services stop ollama   # if managed by Homebrew
```

## Tests

```bash
# All tests
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn test

# Single class
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn test -Dtest=ChapterProcessorTest
```