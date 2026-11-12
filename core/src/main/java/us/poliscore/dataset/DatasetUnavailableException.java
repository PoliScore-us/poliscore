package us.poliscore.dataset;

/** Upstream has not published a usable regular-session dataset yet. */
public class DatasetUnavailableException extends IllegalStateException {
    public DatasetUnavailableException(String message) { super(message); }
    public DatasetUnavailableException(String message, Throwable cause) { super(message, cause); }
}
