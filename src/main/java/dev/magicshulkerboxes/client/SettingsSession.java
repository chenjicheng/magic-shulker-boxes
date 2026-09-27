package dev.magicshulkerboxes.client;

import dev.magicshulkerboxes.EditorNetwork;

/** Connection and policy changes invalidate open editors and pending acknowledgements. */
public final class SettingsSession {
    public enum Mode { OFFLINE, UNSUPPORTED, LOCKED, ALLOWED }
    private Mode mode = Mode.OFFLINE;
    private long revision;
    private long connectionRevision;
    private int nextRequest;
    private Integer pending;
    private boolean recovering, timedOut;
    public Mode mode() { return mode; }
    public long revision() { return revision; }
    public long connectionRevision() { return connectionRevision; }
    private void change(Mode mode) { this.mode = mode; revision++; pending = null; recovering = timedOut = false; }
    public void connected() { connectionRevision++; change(Mode.UNSUPPORTED); }
    public void disconnected() { connectionRevision++; change(Mode.OFFLINE); }
    public void policy(boolean allowed) { change(allowed ? Mode.ALLOWED : Mode.LOCKED); }
    public boolean editable(long revision) {
        return this.revision == revision && (mode == Mode.OFFLINE || mode == Mode.ALLOWED) && pending == null;
    }
    public int beginSave(long revision) {
        if (!editable(revision) || mode != Mode.ALLOWED) throw new IllegalStateException("Settings session changed or save pending");
        pending = ++nextRequest;
        recovering = timedOut = false;
        return pending;
    }
    public int beginRefresh() {
        if ((mode != Mode.ALLOWED && mode != Mode.LOCKED) || pending != null) throw new IllegalStateException("Cannot refresh this session");
        revision++;
        pending = ++nextRequest;
        recovering = true;
        timedOut = false;
        return pending;
    }
    public boolean timeout(int request) {
        if (pending == null || pending != request || timedOut) return false;
        timedOut = recovering = true;
        return true;
    }
    public boolean acknowledge(int request) {
        if (pending == null || pending != request) return false;
        pending = null;
        recovering = timedOut = false;
        revision++;
        return true;
    }
    public boolean acknowledgeResult(int request, int status) {
        // Timeout sent a query with the same correlation ID. A late SAVE rejection must not
        // consume that query; its own failures use separate statuses and can finish recovery.
        if (recovering && status >= EditorNetwork.LOCKED && status <= EditorNetwork.FAILED) return false;
        return acknowledge(request);
    }
    public boolean pending() { return pending != null; }
    public boolean recovering() { return recovering; }
    public void preferencesChanged() { revision++; }
}
