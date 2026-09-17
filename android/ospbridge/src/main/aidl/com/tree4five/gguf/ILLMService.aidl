package com.tree4five.gguf;

import com.tree4five.gguf.ILLMCallback;
import com.tree4five.gguf.IEmbedCallback;

interface ILLMService {
    /**
     * Starts text generation and streams the output to the provided callback.
     */
    oneway void generateTextStream(String prompt, ILLMCallback callback);
    oneway void stopGeneration();

    /**
     * Retrieves the current version of the LLM Provider application.
     */
    String getVersion();

    // ---- Embeddings pipeline (v1.1.0). Appended at the END of the interface
    // so callers compiled against v1.0.x keep working unchanged. ----

    /**
     * Returns the dimension of the model's token-embedding space, or -1 when
     * embeddings are unavailable (caller must fall back to text).
     */
    int getEmbeddingDim();

    /**
     * Reserves a slot for an embedding input of `count` vectors of `dim`
     * floats. Returns a positive slot id, or -1 when the request is invalid
     * (dimension mismatch, no model, too many vectors for the context).
     */
    int beginEmbeddingInput(int count, int dim);

    /**
     * Uploads quantized vectors into a slot. `q8` holds (chunkCount) vectors,
     * each packed as [float32 scale][int8 x dim] little-endian, beginning at
     * token index `startToken` of the slot. Chunks may arrive in any order and
     * in several calls (Binder limit is ~512 KB per transaction).
     */
    oneway void setEmbeddingChunk(int handle, int startToken, in byte[] q8);

    /**
     * Generates from the embeddings uploaded in the slot (soft prompt), with
     * the text `followupPrompt` decoded after the latent prefix (may be
     * empty). Streams tokens to `callback` like generateTextStream. The slot
     * is kept until releaseEmbeddings or LRU eviction (8 slots).
     */
    oneway void generateFromEmbeddings(int handle, int count, String followupPrompt, int nPredict, float temperature, ILLMCallback callback);

    oneway void releaseEmbeddings(int handle);

    /**
     * Embeds `text` (mean of token-embedding rows, no forward pass) and
     * returns one packed vector [float32 scale][int8 x dim] via `callback`.
     */
    oneway void embedText(String text, IEmbedCallback callback);

    // ---- Context reporting (v1.1.4). Appended at the END of the interface
    // so callers compiled against earlier versions keep working unchanged. ----

    /**
     * Returns the context window (in tokens) the engine runs the loaded model
     * with: the value read from the GGUF metadata (train context) capped by
     * the runtime window this engine allocates, or -1 when no model is loaded.
     */
    int getContextLength();
}
