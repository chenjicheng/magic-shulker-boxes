package dev.magicshulkerboxes;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

/** Proves the optional integration does not make IPN or Kotlin a client startup requirement. */
public class ClientSmokeGameTests implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (FabricLoader.getInstance().isModLoaded("inventoryprofilesnext")) throw new AssertionError("Smoke test needs a client without IPN");
        try (var world = context.worldBuilder().create()) {
            context.runOnClient(client -> {
                if (client.player == null || !ClientPlayNetworking.canSend(RestockNetwork.Request.ID)) {
                    throw new AssertionError("Client without IPN could not join the modded integrated server");
                }
            });
        }
    }
}
