package fr.iron8.feathermenu.mixin;

import fr.iron8.feathermenu.FeatherPauseScreen;
import fr.iron8.feathermenu.LauncherCompat;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "openGameMenu", at = @At("HEAD"), cancellable = true)
    private void feather_world_menu$featherPauseInsteadOfVanilla(boolean pauseOnly, CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (LauncherCompat.useVanillaPauseMenu(client)) {
            return;
        }
        if (client.currentScreen != null) {
            return;
        }
        boolean showMenu;
        if (client.isIntegratedServerRunning() && client.getServer() != null && !client.getServer().isRemote()) {
            showMenu = !pauseOnly;
        } else {
            showMenu = true;
        }
        client.setScreen(new FeatherPauseScreen(showMenu));
        ci.cancel();
    }
}
