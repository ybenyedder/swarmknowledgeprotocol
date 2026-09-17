package com.tree4five.gguf;

interface ILLMCallback {
    /**
     * Called for each generated token in real-time.
     */
    oneway void onTokenReceived(String token);

    /**
     * Called when the full generation is complete.
     */
    oneway void onGenerationComplete(String fullText);
}
