package dev.magicshulkerboxes;

import dev.magicshulkerboxes.client.JunkSlotSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JunkSlotSessionTest {
    @Test void serverConfirmationOwnsThePublishedSelection() {
        var session = ready(0);
        session.choose(1L << 9); session.requesting(1);
        assertTrue(session.pending()); assertEquals(0, session.confirmed());
        assertTrue(session.receive(new JunkSlotsNetwork.State(1, JunkSlotsNetwork.SAVED, 1, 1L << 9)));
        assertEquals(1L << 9, session.confirmed()); assertFalse(session.dirty()); assertFalse(session.pending());
    }
    @Test void aNewerChoiceSurvivesAnEarlierSaveReply() {
        var session = ready(0);
        session.choose(1L << 9); session.requesting(1); session.choose((1L << 9) | (1L << 10));
        session.receive(new JunkSlotsNetwork.State(1, JunkSlotsNetwork.SAVED, 1, 1L << 9));
        assertTrue(session.dirty()); assertEquals((1L << 9) | (1L << 10), session.desired());
        assertEquals(1L << 9, session.confirmed());
    }
    @Test void staleOrFailedRepliesCannotOverwriteAnotherSelection() {
        var session = ready(1L << 9); session.choose(1L << 10); session.requesting(1);
        assertFalse(session.receive(new JunkSlotsNetwork.State(2, JunkSlotsNetwork.SAVED, 1, 1L << 11)));
        assertEquals(1L << 9, session.confirmed());
        session.receive(new JunkSlotsNetwork.State(1, JunkSlotsNetwork.STALE, 2, 1L << 12));
        assertEquals(1L << 12, session.desired()); assertFalse(session.dirty());
        session.choose(1L << 13); session.requesting(3);
        session.receive(new JunkSlotsNetwork.State(3, JunkSlotsNetwork.FAILED, -1, 0));
        assertFalse(session.known()); assertFalse(session.pending());
    }
    @Test void busyAndTimeoutSnapshotsReconcileWithoutReplayingASelectionBlindly() {
        var session = ready(0); session.choose(1L << 35); session.requesting(1);
        session.receive(new JunkSlotsNetwork.State(1, JunkSlotsNetwork.BUSY, 0, 0));
        assertTrue(session.dirty()); assertFalse(session.pending());
        session.requesting(2);
        session.receive(new JunkSlotsNetwork.State(2, JunkSlotsNetwork.SNAPSHOT, 1, 1L << 35));
        assertFalse(session.dirty()); assertEquals(1L << 35, session.confirmed());
        session.reset(); assertFalse(session.known()); assertEquals(0, session.desired());
    }
    private static JunkSlotSession ready(long mask) {
        var session = new JunkSlotSession();
        assertTrue(session.receive(new JunkSlotsNetwork.State(0, JunkSlotsNetwork.SNAPSHOT, 0, mask)));
        return session;
    }
}
