# PDF LLM Summarizer

With the help of a locally running LLM (using Ollama)\
You can extract text from a PDF\
And make Q&A Flashcards for spaced repitition, for example notes in Obsidian.\
And make a summary for each chapter (TBD).

# MacOS Parallellize
OLLAMA_NUM_PARALLEL=4 OLLAMA_MAX_LOADED_MODELS=1 ollama serve

Use 'launchctl setenv OLLAMA_NUM_PARALLEL 4' beforehand.
launchctl setenv OLLAMA_MAX_LOADED_MODELS 1
Stopping ollama on MacOS:
- ps -e | grep ollama (when you see hits continue)
- kill -9 processNumber
- ps -e | grep ollama (when you still see hits)
- pkill -f Ollama
- brew services list | grep ollama (should not display Started) otherwise run `brew services stop ollama`
