package fr.iron8.feathermenu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class FeatherWorldMenuClient implements ClientModInitializer {
    private static KeyBinding toggleHudKey;
    private static KeyBinding toggleVisibleKey;
    private static KeyBinding toggleSectionsKey;
    private static KeyBinding closeSectionsKey;
    private static KeyBinding prestigePauseKey;
    /** Menu Minecraft (quitter, options…) — pas l’écran pause Feather transparent. */
    private static boolean wasMinecraftGameMenuOpen = false;
    private static int toggleHudKeyCode = GLFW.GLFW_KEY_H;
    private static int toggleVisibleKeyCode = GLFW.GLFW_KEY_F6;
    private static int toggleSectionsKeyCode = GLFW.GLFW_KEY_ESCAPE;
    private static int closeSectionsKeyCode = GLFW.GLFW_KEY_P;
    private static int prestigePauseKeyCode = GLFW.GLFW_KEY_INSERT;

    @Override
    public void onInitializeClient() {
        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.feather_world_menu.toggle_hud",
                InputUtil.Type.KEYSYM,
                toggleHudKeyCode,
                "category.feather_world_menu.main"
        ));
        toggleVisibleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.feather_world_menu.toggle_visible",
                InputUtil.Type.KEYSYM,
                toggleVisibleKeyCode,
                "category.feather_world_menu.main"
        ));
        toggleSectionsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.feather_world_menu.toggle_sections",
                InputUtil.Type.KEYSYM,
                toggleSectionsKeyCode,
                "category.feather_world_menu.main"
        ));
        closeSectionsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.feather_world_menu.close_sections",
                InputUtil.Type.KEYSYM,
                closeSectionsKeyCode,
                "category.feather_world_menu.main"
        ));
        prestigePauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.feather_world_menu.prestige_pause",
                InputUtil.Type.KEYSYM,
                prestigePauseKeyCode,
                "category.feather_world_menu.main"
        ));

        WorldHudOverlay.register();

        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                WorldHudOverlay.onClientGameMessage(message.getString()));
        ClientSendMessageEvents.CHAT.register(message -> WorldHudOverlay.onReconnectRestoredAfkSendAttempt());
        ClientSendMessageEvents.COMMAND.register(message -> WorldHudOverlay.onReconnectRestoredAfkSendAttempt());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            WorldHudOverlay.clientTick(client);
            while (toggleHudKey.wasPressed()) {
                WorldHudOverlay.toggleEditor(client);
            }
            while (toggleVisibleKey.wasPressed()) {
                WorldHudOverlay.togglePanelVisible();
            }
            while (toggleSectionsKey.wasPressed()) {
                WorldHudOverlay.toggleSectionsPanel();
            }
            while (closeSectionsKey.wasPressed()) {
                WorldHudOverlay.closeSectionsPanelIfOpen();
            }
            while (prestigePauseKey.wasPressed()) {
                WorldHudOverlay.togglePrestigePause();
            }

            boolean minecraftMenuOpen = WorldHudOverlay.isMinecraftGameMenuScreenOpen(client);
            if (!minecraftMenuOpen && wasMinecraftGameMenuOpen) {
                WorldHudOverlay.onGameMenuClosed(client);
            }
            wasMinecraftGameMenuOpen = minecraftMenuOpen;
        });
    }

    public static int getToggleHudKeyCode() {
        return toggleHudKeyCode;
    }

    public static int getToggleVisibleKeyCode() {
        return toggleVisibleKeyCode;
    }

    public static int getToggleSectionsKeyCode() {
        return toggleSectionsKeyCode;
    }

    public static int getCloseSectionsKeyCode() {
        return closeSectionsKeyCode;
    }

    public static int getPrestigePauseKeyCode() {
        return prestigePauseKeyCode;
    }

    public static void setToggleHudKeyCode(int code) {
        setKey(toggleHudKey, code);
    }

    public static void setToggleVisibleKeyCode(int code) {
        setKey(toggleVisibleKey, code);
    }

    public static void setToggleSectionsKeyCode(int code) {
        setKey(toggleSectionsKey, code);
    }

    public static void setCloseSectionsKeyCode(int code) {
        setKey(closeSectionsKey, code);
    }

    public static void setPrestigePauseKeyCode(int code) {
        setKey(prestigePauseKey, code);
    }

    private static void setKey(KeyBinding binding, int code) {
        if (binding == null || code < 0 || code > GLFW.GLFW_KEY_LAST) {
            return;
        }
        if (binding == toggleHudKey) {
            toggleHudKeyCode = code;
        } else if (binding == toggleVisibleKey) {
            toggleVisibleKeyCode = code;
        } else if (binding == toggleSectionsKey) {
            toggleSectionsKeyCode = code;
        } else if (binding == closeSectionsKey) {
            closeSectionsKeyCode = code;
        } else if (binding == prestigePauseKey) {
            prestigePauseKeyCode = code;
        }
        binding.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(code));
        KeyBinding.updateKeysByCode();
    }
}
