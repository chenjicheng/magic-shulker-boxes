package dev.magicshulkerboxes;

import dev.magicshulkerboxes.client.JunkSlotGesture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JunkSlotGestureTest {
    @Test void holdingAddsSeveralSlotsWithoutFlippingWhenRevisited() {
        var gesture = new JunkSlotGesture(); gesture.begin(0);
        assertTrue(gesture.visit(9)); assertTrue(gesture.visit(10));
        assertFalse(gesture.visit(9)); assertEquals((1L << 9) | (1L << 10), gesture.mask());
    }
    @Test void firstSelectedSlotChoosesEraseModeForTheEntireGesture() {
        var gesture = new JunkSlotGesture(); gesture.begin((1L << 9) | (1L << 10));
        assertTrue(gesture.visit(9)); assertFalse(gesture.visit(11)); assertTrue(gesture.visit(10));
        assertEquals(0, gesture.mask());
    }
    @Test void invalidSlotsDoNotChooseTheGestureModeAndNextGestureCanReverseIt() {
        var gesture = new JunkSlotGesture(); gesture.begin(0);
        assertFalse(gesture.visit(-1)); assertFalse(gesture.visit(36));
        assertTrue(gesture.visit(35)); assertEquals(1L << 35, gesture.mask());
        gesture.begin(gesture.mask()); assertTrue(gesture.visit(35)); assertEquals(0, gesture.mask());
    }
}
