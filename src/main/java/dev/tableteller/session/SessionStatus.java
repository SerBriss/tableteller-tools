package dev.tableteller.session;

/** Where a play session stands. Constants are the wire values and the values in {@code session_log.status}. */
public enum SessionStatus {
    open, ended, abandoned
}
