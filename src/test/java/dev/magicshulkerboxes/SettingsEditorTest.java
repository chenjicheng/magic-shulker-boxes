package dev.magicshulkerboxes;

import dev.magicshulkerboxes.client.SettingsDraft;
import dev.magicshulkerboxes.client.SettingsSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsEditorTest {
    @Test void lateSaveRejectionDoesNotDiscardTheRecoverySnapshot() {
        var session = new SettingsSession(); session.connected(); session.policy(true);
        int request = session.beginSave(session.revision());
        session.timeout(request);
        assertFalse(session.acknowledgeResult(request, EditorNetwork.BUSY));
        assertTrue(session.pending(), "A late save rejection is not the result of the recovery query");
        assertTrue(session.acknowledgeResult(request, EditorNetwork.SNAPSHOT));
        assertFalse(session.acknowledgeResult(request, EditorNetwork.SAVED));
        int retry = session.beginRefresh();
        assertTrue(session.acknowledgeResult(retry, EditorNetwork.QUERY_BUSY), "A query's own rejection ends that attempt");
        assertFalse(session.pending());
        retry = session.beginRefresh();
        assertTrue(session.acknowledgeResult(retry, EditorNetwork.QUERY_FAILED));
        assertFalse(session.pending(), "Failed queries allow a subsequent recovery attempt");
    }

    @Test void timeoutKeepsLateConfirmationAndLocksFurtherEditsUntilReconciled() {
        var session = new SettingsSession(); session.connected(); session.policy(true);
        int request = session.beginSave(session.revision());
        assertTrue(session.timeout(request));
        assertFalse(session.timeout(request), "Only one recovery query per deadline");
        assertTrue(session.pending());
        assertTrue(session.recovering());
        assertFalse(session.editable(session.revision()));
        assertTrue(session.acknowledge(request), "A late save or snapshot can still reconcile the session");
        int refresh = session.beginRefresh();
        session.disconnected(); session.connected(); session.policy(true);
        int current = session.beginRefresh();
        assertNotEquals(refresh, current);
        assertFalse(session.acknowledge(refresh), "Old connection cannot overwrite the new one");
        assertTrue(session.acknowledge(current));
    }

    @Test
    void localHostMayKeepEditingAfterItsOwnPolicyUpdateButNotAfterReconnect() {
        var session = new SettingsSession();
        session.connected();
        long connection = session.connectionRevision();
        session.policy(false);
        session.policy(true);
        assertEquals(connection, session.connectionRevision(), "Policy does not revoke the integrated host editor");
        session.disconnected();
        assertNotEquals(connection, session.connectionRevision());
    }
    @Test
    void editsAreDetachedAndInheritanceRemovesOnlyTheSelectedOverride() throws Exception {
        var original = ConfigFile.parsePreferences("{\"pickupStorageEnabled\":false,\"useMixedBoxes\":true,\"makeSpaceMode\":\"DROP_AND_PICKUP\"}");
        var draft = new SettingsDraft(original);
        assertEquals(SettingsDraft.Toggle.OFF, draft.toggle("pickupStorageEnabled"));
        assertEquals(SettingsDraft.Space.DROP_AND_PICKUP, draft.space());
        draft.toggle("pickupStorageEnabled", SettingsDraft.Toggle.INHERIT);
        draft.toggle("useHotbarForSpace", SettingsDraft.Toggle.ON);
        draft.space(SettingsDraft.Space.INHERIT);
        assertFalse(draft.values().has("pickupStorageEnabled"));
        assertFalse(draft.values().has("makeSpaceMode"));
        assertTrue(draft.values().get("useMixedBoxes").getAsBoolean());
        assertTrue(draft.values().get("useHotbarForSpace").getAsBoolean());
        assertEquals(3, original.size(), "Cancel never changes the source preferences");
        draft.values().remove("useMixedBoxes");
        assertTrue(draft.values().has("useMixedBoxes"), "Snapshot cannot mutate the draft");
    }

    @Test
    void connectedEditorsRequireCurrentServerPermissionAndCannotSurviveReconnect() {
        var session = new SettingsSession();
        long offline = session.revision();
        assertTrue(session.editable(offline));
        session.connected();
        assertEquals(SettingsSession.Mode.UNSUPPORTED, session.mode());
        assertFalse(session.editable(offline));
        session.policy(true);
        long allowed = session.revision();
        assertTrue(session.editable(allowed));
        session.policy(false);
        assertFalse(session.editable(allowed));
        assertThrows(IllegalStateException.class, () -> session.beginSave(allowed));
        session.disconnected();
        assertTrue(session.editable(session.revision()));
        assertFalse(session.editable(allowed));
    }

    @Test
    void onlyCurrentAcknowledgementMayPersistAndRepeatedSavesCannotRace() {
        var session = new SettingsSession();
        session.connected();
        session.policy(true);
        int request = session.beginSave(session.revision());
        assertTrue(session.pending());
        assertThrows(IllegalStateException.class, () -> session.beginSave(session.revision()));
        assertFalse(session.acknowledge(request + 1));
        assertTrue(session.pending());
        assertTrue(session.acknowledge(request));
        assertFalse(session.acknowledge(request));
        int cancelled = session.beginSave(session.revision());
        session.policy(false);
        assertFalse(session.acknowledge(cancelled), "Policy revocation invalidates pending save");
        session.disconnected();
        session.connected();
        session.policy(true);
        assertNotEquals(cancelled, session.beginSave(session.revision()));
        assertFalse(session.acknowledge(cancelled), "Old connection cannot overwrite local preferences");
    }

    @Test
    void allSettingsHaveBilingualDescriptionsAndSelectionLabels() {
        for (String key : ConfigFile.optionNames()) {
            assertTrue(Messages.keys("zh_cn").contains("description." + key), key);
        }
        for (var value : SettingsDraft.Toggle.values()) {
            assertTrue(Messages.keys("en_us").contains("gui.toggle." + value));
        }
        for (var value : SettingsDraft.Space.values()) {
            assertTrue(Messages.keys("en_us").contains("gui.space." + value));
        }
    }
}
