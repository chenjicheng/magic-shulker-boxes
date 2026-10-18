package dev.magicshulkerboxes;

public class StorageConfig {
    // Pickup storage and schematic refilling are independent personal choices.
    public boolean pickupStorageEnabled = false;
    public boolean preferEmptyBoxesOverInventory = false;
    public boolean useMatchingBoxes = true;
    public boolean useEmptyBoxes = true;
    public boolean splitStackedBoxes = true;
    public MakeSpaceMode makeSpaceMode = MakeSpaceMode.MOVE_TO_BOX;
    public boolean useHotbarForSpace = false;
    public boolean protectHotbarSlot1 = true;
    public boolean protectHotbarSlot2 = true;
    public boolean protectHotbarSlot3 = true;
    public boolean protectHotbarSlot4 = true;
    public boolean protectHotbarSlot5 = true;
    public boolean protectHotbarSlot6 = true;
    public boolean protectHotbarSlot7 = true;
    public boolean protectHotbarSlot8 = true;
    public boolean protectHotbarSlot9 = true;
    public boolean allowPartialStacksForSpace = true;
    public boolean preferExistingBoxesBeforeMakingSpace = true;
    public boolean includeOffhand = false;
    public boolean matchItemComponents = false;
    public boolean schematicRefill = true;
    public boolean ipnRefill = true;
    public boolean craftRefill = true;
    public boolean enderChestRefill = false;
    public int refillAmount = 64;
    public boolean refillMakeSpace = true;
    public boolean refillFailureMessages = true;
    public boolean spaceFailureMessages = true;

    public enum MakeSpaceMode {
        DISABLED, MOVE_TO_BOX, DROP_AND_PICKUP
    }
}
