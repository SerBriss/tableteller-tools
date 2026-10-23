package dev.tableteller.lists;

/** The two oracle lists, threads and characters. Constants are the tool's wire values; the database stores the singular form. */
public enum ListName {
    threads("thread", ListStatus.open, ListStatus.closed),
    characters("character", ListStatus.active, ListStatus.removed);

    private final String singular;
    private final ListStatus liveStatus;
    private final ListStatus endedStatus;

    ListName(String singular, ListStatus liveStatus, ListStatus endedStatus) {
        this.singular = singular;
        this.liveStatus = liveStatus;
        this.endedStatus = endedStatus;
    }

    /** The value in {@code list_entry.list}. */
    public String dbValue() {
        return singular;
    }

    public String singular() {
        return singular;
    }

    /** The status of an entry random events can pick: open threads, active characters. */
    public ListStatus liveStatus() {
        return liveStatus;
    }

    public ListStatus endedStatus() {
        return endedStatus;
    }

    public boolean allows(ListStatus status) {
        return status == liveStatus || status == endedStatus;
    }
}
