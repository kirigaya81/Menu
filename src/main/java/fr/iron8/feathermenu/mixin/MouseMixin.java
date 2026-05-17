package fr.iron8.feathermenu.mixin;

import fr.iron8.feathermenu.WorldHudOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    /**
     * Quand la molette survole la carte HUD ou le panneau paramètres avec débordement vertical,
     * on applique le scroll ici et on annule la suite pour ne pas faire défiler l’écran derrière.
     */
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void featherWorldMenu$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (vertical == 0.0 && horizontal == 0.0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null || client.getWindow().getHandle() != window) {
            return;
        }
        if (WorldHudOverlay.tryConsumeMouseScroll(client, horizontal, vertical)) {
            ci.cancel();
        }
    }

    /**
     * Même idée que la molette : un drag sur l’entête de la carte / du panneau paramètres ne doit pas
     * déclencher une attaque ni activer les cases d’un inventaire derrière un menu transparent.
     */
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void featherWorldMenu$onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null || client.getWindow().getHandle() != window) {
            return;
        }
        if (WorldHudOverlay.tryConsumeMouseButton(client, button, action)) {
            ci.cancel();
        }
    }
}
