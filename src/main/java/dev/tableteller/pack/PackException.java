package dev.tableteller.pack;

/** A pack that cannot be loaded; the message names the source and the reason. */
public class PackException extends RuntimeException {

    public PackException(String source, String reason) {
        super(source + ": " + reason);
    }
}
