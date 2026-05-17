package fr.iron8.feathermenu;

import net.minecraft.client.MinecraftClient;

import java.util.Locale;

/**
 * Détection Lunar Client : menu pause vanilla ; les chemins de log Lunar sont gérés dans {@code WorldHudOverlay}.
 * Un seul JAR ; Feather reste inchangé tant que le répertoire de jeu n’est pas Lunar.
 */
public final class LauncherCompat {
    private LauncherCompat() {
    }

    /** {@code true} si l’instance ressemble à un lancement Lunar (chemin de jeu ou arguments JVM). */
    public static boolean isLunarClient(MinecraftClient client) {
        if (client == null || client.runDirectory == null) {
            return false;
        }
        String run = client.runDirectory.getAbsolutePath().toLowerCase(Locale.ROOT);
        if (run.contains("lunarclient")) {
            return true;
        }
        try {
            String args = String.join(" ", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
            if (args.toLowerCase(Locale.ROOT).contains("lunarclient")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Menu Échap vanilla : pas d’écran pause Feather custom sous Lunar. */
    public static boolean useVanillaPauseMenu(MinecraftClient client) {
        return isLunarClient(client);
    }
}
