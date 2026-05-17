package fr.iron8.feathermenu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;

/**
 * Variante du menu Échap vanilla qui ne dessine pas de voile noir ni de flou :
 * le HUD Feather (carte + sous-menus) reste lisible derrière les boutons
 * (Retour au jeu, Options, Quitter, …).
 */
public class FeatherGameMenuScreen extends GameMenuScreen {
    public FeatherGameMenuScreen(boolean showMenu) {
        super(showMenu);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        /* Pas de panorama / flou / assombrissement : le HUD reste visible. */
    }
}
