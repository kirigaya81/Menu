package fr.iron8.feathermenu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Remplace le menu Échap vanilla : pas de flou ni voile noir, le HUD Feather reste lisible.
 * Affiche uniquement un bouton qui ouvre le {@link FeatherGameMenuScreen} (équivalent transparent
 * du menu Minecraft : Quitter le monde, Options, …). N'ouvre ni la carte HUD ni le sous-menu
 * paramètres : ils gardent leur état précédent.
 */
public class FeatherPauseScreen extends Screen {
    private final boolean gameMenuShowMenu;

    public FeatherPauseScreen(boolean gameMenuShowMenu) {
        super(Text.translatable("feather_world_menu.pause.title"));
        this.gameMenuShowMenu = gameMenuShowMenu;
    }

    @Override
    protected void init() {
        super.init();
        int w = 220;
        int btnH = 20;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - btnH / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("feather_world_menu.pause.vanilla_menu"), b -> {
            if (client != null) {
                WorldHudOverlay.setHideHudForMinecraftMenu(true);
                client.setScreen(new FeatherGameMenuScreen(gameMenuShowMenu));
            }
        }).dimensions(x, y, w, btnH).build());
    }

    @Override
    public boolean shouldPause() {
        return client != null && client.isIntegratedServerRunning();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        /* Pas de panorama / flou / assombrissement : le monde et le HUD restent visibles. */
    }

}
