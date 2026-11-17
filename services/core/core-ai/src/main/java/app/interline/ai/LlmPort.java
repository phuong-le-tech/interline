package app.interline.ai;

/**
 * One chat completion. The production implementation wraps a Spring AI ChatModel built per provider row;
 * tests supply a stub. The API key is fetched per call by the implementation and is never part of a request.
 */
public interface LlmPort {
    LlmReply complete(LlmRequest request);
}
