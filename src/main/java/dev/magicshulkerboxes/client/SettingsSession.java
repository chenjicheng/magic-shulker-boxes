package dev.magicshulkerboxes.client;

/** Connection and policy changes invalidate open editors and pending acknowledgements. */
public final class SettingsSession {
    public enum Mode { OFFLINE, UNSUPPORTED, LOCKED, ALLOWED }
    private Mode mode = Mode.OFFLINE;
    private long revision;
    private long connectionRevision;
    private int nextRequest;
    private Integer pending;
    public Mode mode() { return mode; }
    public long revision() { return revision; }
    public long connectionRevision() { return connectionRevision; }
    private void change(Mode mode) { this.mode = mode; revision++; pending = null; }
    public void connected() { connectionRevision++; change(Mode.UNSUPPORTED); }
    public void disconnected() { connectionRevision++; change(Mode.OFFLINE); }
    public void policy(boolean allowed) { change(allowed ? Mode.ALLOWED : Mode.LOCKED); }
    public boolean editable(long revision) {
        return this.revision == revision && (mode == Mode.OFFLINE || mode == Mode.ALLOWED) && pending == null;
    }
    public int beginSave(long revision) {
        if (!editable(revision) || mode != Mode.ALLOWED) throw new IllegalStateException("Settings session changed or save pending");
        pending = ++nextRequest;
        return pending;
    }
    public boolean acknowledge(int request) {
        if (pending == null || pending != request) return false;
        pending = null;
        return true;
    }
    public boolean pending() { return pending != null; }
}
