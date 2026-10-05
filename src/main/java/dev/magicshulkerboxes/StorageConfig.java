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
    public boolean allowPartialStacksForSpace = true;
    public boolean preferExistingBoxesBeforeMakingSpace = true;
    public boolean includeOffhand = false;
    public boolean matchItemComponents = false;
    public boolean schematicRefill = true;
    public boolean ipnRefill = true;
    public boolean craftRefill = true;
    public boolean enderChestRefill = false;
    public boolean refillFullStack = true;
    public boolean refillMakeSpace = true;
    public boolean refillFailureMessages = true;

    public enum MakeSpaceMode {
        DISABLED, MOVE_TO_BOX, DROP_AND_PICKUP
    }
}
