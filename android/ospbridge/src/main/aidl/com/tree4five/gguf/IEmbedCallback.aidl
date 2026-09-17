package com.tree4five.gguf;

interface IEmbedCallback {
    /**
     * Called with the embedding of the requested text, quantized per token as
     * [float32 scale][int8 x dim] (little-endian scale). For embedText the
     * payload holds exactly one vector.
     */
    oneway void onEmbedding(in byte[] q8, int dim);

    oneway void onError(String message);
}
