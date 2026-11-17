package app.interline.domain;

/** The model output is not the JSON document of the contract (not JSON, or required members missing). */
public class InvalidEnhancementException extends RuntimeException {
    public InvalidEnhancementException(String message) { super(message); }
}
