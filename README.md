# PDF LLM Summarizer

With the help of a locally running LLM (using Ollama)\
You can extract text from a PDF\
And make Q&A Flashcards for spaced repitition, for example notes in Obsidian.\
And make a summary for each chapter (TBD).

# MacOS Parallellization

## automatic
Make your changes in the `config.json`
Run the bashscript `llm/RestartOllama.sh` to implement the changes. This script stops all Ollama processes, and starts new terminal windows with N servers running the 
configuration according to the config.json.

## manual
The default port is 11434.

To serve multiple servers you need to run 



`OLLAMA_HOST=127.0.0.1:11435 OLLAMA_NUM_PARALLEL=1 OLLAMA_MAX_LOADED_MODELS=2 ollama serve` for every additional server. The first host parameter is superfluous for the first server and need to be incremeted for every additional server. That is a bare minimum command:

`OLLAMA_NUM_PARALLEL=1 OLLAMA_MAX_LOADED_MODELS=1 ollama serve`

### make sure Ollama processes are stopped before changing parameters

Stopping ollama on MacOS:
- ps -e | grep ollama (when you see hits continue)
- kill -9 processNumber
- ps -e | grep ollama (when you still see hits)
- pkill -f Ollama
- brew services list | grep ollama (should not display Started) otherwise run `brew services stop ollama`
