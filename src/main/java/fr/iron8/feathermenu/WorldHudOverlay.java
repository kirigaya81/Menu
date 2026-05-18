package fr.iron8.feathermenu;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.number.BlankNumberFormat;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WorldHudOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("FeatherWorldMenu");

    private static final Pattern LEGACY_FORMATTING_CODES = Pattern.compile("§.");
    /** Mots de zone dans une ligne du sidebar (texte normalisé ASCII minuscule). */
    private static final Pattern SCOREBOARD_ZONE_LAC = Pattern.compile("\\blac\\b");
    private static final Pattern SCOREBOARD_ZONE_MINE = Pattern.compile("\\bmine\\b");
    private static final Pattern SCOREBOARD_ZONE_FERME = Pattern.compile("\\bferme\\b");
    private static final Pattern SCOREBOARD_ZONE_CHAMP = Pattern.compile("\\bchamp\\b");
    /** Temps restant type « 9m30s » sur une ligne du sidebar (normalisée). */
    private static final Pattern SCOREBOARD_COUNTDOWN_MS = Pattern.compile("\\b(\\d{1,3})\\s*m\\s*(\\d{1,2})\\s*s\\b");
    /** Temps restant type « 9:30 » (minutes:secondes). */
    private static final Pattern SCOREBOARD_COUNTDOWN_COLON = Pattern.compile("\\b(\\d{1,3}):(\\d{2})\\b");
    /** latest.log (1.21) : horloge + fil d’exécution ; tolère espace avant « : ». */
    private static final Pattern LOG_LINE_PREFIX = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2}\\] \\[[^\\]]+\\]\\s*:\\s*");
    private static final Pattern CHAT_TAG_PREFIX = Pattern.compile("^\\[CHAT\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1b\\[[0-9;]*m");
    /**
     * Recap reward : uniquement si la ligne contient « | » puis « + » (espaces optionnels, {@code |+} collé ok).
     * Pipes Unicode : {@code │} {@code ┃}.
     */
    private static final Pattern REWARD_PIPE_PLUS = Pattern.compile("[|│┃]\\s*\\+");
    /** Indication à côté de la poignée de redimensionnement (coin bas-droit). */
    private static final String DRAG_RESIZE_HINT = "drag/size";
    private static final int RESIZE_HANDLE_INSET = 10;
    private static final int RESIZE_HANDLE_SIZE = 6;
    /** Empreinte LRU par destination : hors AFK → Reward uniquement ; en AFK → AFK uniquement. */
    private static final int MAX_SEEN_RECAP_LINE_FINGERPRINTS = 4096;
    private static final ArrayDeque<String> seenRewardLineOrder = new ArrayDeque<>();
    private static final HashSet<String> seenRewardLineFingerprints = new HashSet<>();
    private static final ArrayDeque<String> seenAfkRewardLineOrder = new ArrayDeque<>();
    private static final HashSet<String> seenAfkRewardLineFingerprints = new HashSet<>();
    /** Anti-doublon pour les lignes log qui correspondent exactement à une entrée Lac du menu Options. */
    private static final ArrayDeque<String> seenEnchantOptionLogLineOrder = new ArrayDeque<>();
    private static final HashSet<String> seenEnchantOptionLogLineFingerprints = new HashSet<>();
    /** Anti-doublon prestige (évite de relancer le chrono si Lunar change de fichier log). */
    private static final ArrayDeque<String> seenPrestigeLogLineOrder = new ArrayDeque<>();
    private static final HashSet<String> seenPrestigeLogLineFingerprints = new HashSet<>();
    /** État AFK (logs) : masque la section Reward à l’affichage et route les recaps vers AFK. */
    private static boolean afkRewardLoggingActive = false;
    private static final String AFK_LOG_ON_EXACT = "Vous êtes maintenant AFK";
    private static final String AFK_LOG_OFF_EXACT = "Vous n'êtes plus AFK";
    /**
     * Détection tolérante (accents/apostrophes/bruit autour) des bascules AFK.
     * Exemple accepté : "Vous etes maintenant AFK", "Vous n’etes plus AFK", etc.
     */
    private static final Pattern AFK_ON_PATTERN = Pattern.compile("\\bvous\\s+etes\\s+maintenant\\s+afk\\b");
    private static final Pattern AFK_OFF_PATTERN = Pattern.compile("\\bvous\\s+n\\s*['’]?\\s*etes\\s+plus\\s+afk\\b");
    /**
     * Phrase serveur dans {@code latest.log} : démarre le chrono Prestige ; si le chrono tourne déjà,
     * la durée courante va sur « Dernier » puis le chrono repart à 0.
     * Détection volontairement tolérante après normalisation ASCII/lowercase :
     * préfixe {@code LostEra >>} optionnel, accents optionnels, séparateur {@code >>}/{@code »}/{@code ›}.
     */
    private static final Pattern PRESTIGE_LOG_PHRASE_NORM = Pattern.compile(
            "(?:\\blostera\\b\\s*(?:>>|\u00BB|\u203A)\\s*)?\\btu\\s+es\\s+passe\\s+prestige\\b");
    /**
     * Première ouverture (sans fichier layout) : largeur par défaut un peu sous {@link #REWARD_STACKED_BREAKPOINT}
     * pour une carte plus étroite ; la largeur minimale réelle reste {@link #minMainCardWidth}.
     */
    private static final int DEFAULT_CARD_WIDTH = 260;
    private static final int DEFAULT_CARD_HEIGHT = 200;
    private static final int MIN_CARD_WIDTH = 52;
    private static final int MIN_CARD_HEIGHT = 180;
    /** Event double / farm : mise en page compacte sous cette largeur. */
    private static final int NARROW_CARD_BREAKPOINT = 170;
    /** Reward : nom + lignes « /h » et « session » dessous si la carte est plus étroite que ce seuil. */
    private static final int REWARD_STACKED_BREAKPOINT = 280;

    /** Zones du menu plus étroites (marges internes réduites). */
    private static final int CARD_INSET = 5;
    private static final int CARD_TEXT_X = CARD_INSET + 2;
    private static final int SESSION_HEADER_H = 22;
    private static final int SESSION_LINE_AFTER_HEADER = 22;
    /** Marge sous le bord haut de la carte (zone pour saisir le menu) avant la section Session. */
    private static final int CARD_BODY_TOP_INSET = 8;
    /** Écart vertical entre deux sections empilées (Session, Prestige, …). */
    private static final int CARD_SECTION_STACK_GAP = 4;
    /** Décalage vertical du texte « Session » dans l’encadré session. */
    private static final int SESSION_TEXT_DY = 5;
    /** Espace sous chaque bloc (Prestige, Reward, …) avant la section suivante. */
    private static final int SECTION_BLOCK_TAIL_PAD = 2;
    /** Barre de défilement à droite du contenu (carte + panneau paramètres). */
    private static final int SCROLLBAR_TRACK_W = 4;
    private static final int SCROLLBAR_PAD = 1;
    /** Sensibilité molette → pixels (signe géré par l’appelant). */
    private static final double SCROLL_PIXELS_PER_WHEEL_UNIT = 14.0;

    private static long startTimeMs = System.currentTimeMillis();
    private static int posX = 6;
    private static int posY = 18;
    private static int cardWidth = DEFAULT_CARD_WIDTH;
    private static int cardHeight = DEFAULT_CARD_HEIGHT;
    private static boolean editMode = false;
    private static boolean dragging = false;
    private static boolean resizing = false;
    private static boolean mouseDownLastFrame = false;
    private static boolean mainCardFooterBtnMouseDownLast = false;
    private static boolean mainCardEditerBtnRectValid = false;
    private static int mainCardEditerBtnLeft;
    private static int mainCardEditerBtnTop;
    private static int mainCardEditerBtnRight;
    private static int mainCardEditerBtnBottom;
    private static boolean mainCardFermerSaveBtnRectValid = false;
    private static int mainCardFermerSaveBtnLeft;
    private static int mainCardFermerSaveBtnTop;
    private static int mainCardFermerSaveBtnRight;
    private static int mainCardFermerSaveBtnBottom;
    private static boolean mainCardSettingsBtnRectValid = false;
    private static int mainCardSettingsBtnLeft;
    private static int mainCardSettingsBtnTop;
    private static int mainCardSettingsBtnRight;
    private static int mainCardSettingsBtnBottom;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static int resizeStartMouseX = 0;
    private static int resizeStartMouseY = 0;
    private static int resizeStartWidth = DEFAULT_CARD_WIDTH;
    private static int resizeStartHeight = DEFAULT_CARD_HEIGHT;
    private static long logReadOffset = 0L;
    /** Une fois par session : confirme le chemin du journal pour le support / partage du mod. */
    private static boolean latestLogSourceAnnounced;
    /** Évite de répéter l’avertissement si {@code latest.log} manque encore. */
    private static boolean latestLogMissingWarned;
    /** Évite de répéter l’avertissement si la lecture échoue (verrouillage temporaire, etc.). */
    private static boolean latestLogReadErrorWarned;
    /** Dernière résolution GUI (échelle) pour réappliquer positions si la fenêtre change. */
    private static int lastHudScaledW = -1;
    private static int lastHudScaledH = -1;
    /** Limite les ouvertures de {@code latest.log} (évite ~60 Hz quand le HUD est affiché). */
    private static long lastLogPollWallMs = -999_000L;
    /** Fichier log choisi pour le HUD (run Minecraft ou Lunar multiver) ; invalidé en sortie de monde. */
    private static Path cachedHudLatestLogPath = null;
    /** Fichier associé à {@link #logReadOffset} ; changement = reprise en fin de fichier sans relire l’historique. */
    private static Path logReadOffsetPath = null;
    private static final long LOG_POLL_INTERVAL_MS = 40L;
    private static final long LOG_PATH_REFRESH_INTERVAL_MS = 1_500L;
    private static final int MAX_LOG_LINES_PER_POLL = 2_000;
    private static final int MAX_LOG_LINES_PER_POLL_LUNAR = 8_000;
    private static long lastLogPathRefreshWallMs = -999_000L;
    private static long eventEndMs = 0L;
    private static long nextEventEndMs = 0L;
    private static String eventZone = "--";
    private static long doubleEventEndMs = 0L;
    private static long doubleProchainEndMs = 0L;
    private static boolean doubleMoneyActive = false;
    private static boolean doubleExpActive = false;
    private static boolean doubleGensDropActive = false;
    /** Si {@code false} (ex. log « Fin de l'événement double »), la fin du prochain event ne relance pas un nouveau 5 min. */
    private static boolean doubleEventRestartAfterProchain = true;
    private static boolean layoutLoaded = false;
    /** Dernière exécution du scan du sidebar (zones + concours farm, 1× / s). */
    private static long lastSidebarPollTick = Long.MIN_VALUE;
    private static boolean wasInGame = false;
    /** Détecte transfert / déco vers un autre serveur quand l’événement Fabric DISCONNECT ne suffit pas. */
    private static ClientPlayNetworkHandler lastPlayNetworkHandler = null;
    /**
     * Si on quitte le monde encore considéré AFK côté client, on réapplique l’état à la prochaine entrée en jeu
     * (reconnexion sans rejouer « maintenant AFK » dans la portion lue du log), sans limite de temps :
     * l’AFK reste actif jusqu’à une action locale (mouvement, touche, message ou commande envoyé).
     */
    private static boolean reapplyAfkLoggingOnNextWorldEntry = false;
    /**
     * Après reconnexion : attend le log « Vous êtes maintenant AFK » ; sinon envoie {@code /afk} après le délai max.
     */
    private static boolean reconnectAfkCommandWatchActive = false;
    private static int reconnectAfkCommandWatchTicks = 0;
    /** Attente minimale pour laisser le temps au log d’arriver (ticks client). */
    private static final int RECONNECT_AFK_COMMAND_MIN_WAIT_TICKS = 40;
    /** Délai max avant d’envoyer {@code /afk} si le log AFK n’apparaît pas (ticks client). */
    private static final int RECONNECT_AFK_COMMAND_MAX_WAIT_TICKS = 100;
    /** À chaque entrée monde / serveur : sonde le log (fin de fichier + nouvelles lignes). */
    private static boolean afkWorldEntryDetectionPending = false;
    private static boolean afkWorldEntryProbeActive = false;
    private static boolean afkWorldEntryProbeResolved = false;
    private static int afkWorldEntryProbeTicks = 0;
    private static final int AFK_WORLD_ENTRY_PROBE_MAX_TICKS = 120;
    private static final int AFK_WORLD_ENTRY_LOG_TAIL_MAX_LINES = 128;
    private static final int AFK_WORLD_ENTRY_LOG_TAIL_BYTES = 96_000;
    /** Ignore le prochain envoi chat/commande déclenché par {@link #sendReconnectAfkCommand(MinecraftClient)}. */
    private static boolean suppressReconnectAfkSendClear = false;
    /** {@code true} seulement après {@link #restoreAfkLoggingStateAfterReconnect()} : l’activité locale sort de l’AFK côté HUD. */
    private static boolean afkAwaitingLocalActivityClearAfterReconnect = false;
    private static Vec3d reconnectAfkActivityBaseline = null;
    private static final double RECONNECT_AFK_MOVE_EPS_SQ = 0.12 * 0.12;
    private static final double RECONNECT_AFK_VERT_EPS = 0.22;
    private static boolean panelVisible = true;
    /** Masque tous les panneaux du mod uniquement sur l’écran « Menu Minecraft (quitter, options…) ». */
    private static boolean hideHudForMinecraftMenu = false;
    /** État du panneau paramètres avant ouverture du menu Minecraft (restauré à la sortie). */
    private static boolean sectionsPanelOpenBeforeMinecraftMenu = false;
    private static boolean savedSectionsPanelForMinecraftMenu = false;

    /** Panneau [U] : visibilité par section + position / taille. */
    private static boolean sectionsPanelOpen = false;
    private static int sectionsPosX = 120;
    private static int sectionsPosY = 18;
    private static int sectionsWidth = 252;
    private static int sectionsHeight = 246;
    /** +1 ligne Prestige (clear/pause) si Prestige activé ; +1 ligne « Touches » ; Reward / AFK : +cacher +clear si activés. */
    private static final int MIN_SECTIONS_PANEL_H = 242;
    /** Padding horizontal interne ajouté autour du texte des boutons du sous-menu (1 px de chaque côté). */
    private static final int SECTIONS_BTN_TEXT_PAD = 2;
    private static final int SECTIONS_PRESTIGE_BTN_GAP = 2;
    private static final int SECTIONS_HEADER_H = 16;
    /** Bas du panneau paramètres : carré resize + « drag/size » — hors interaction des cases / lignes. */
    private static final int SECTIONS_PANEL_FOOTER_H = 22;
    /** Carré bleu de redimensionnement (visuel + zone de clic). */
    private static final int SECTIONS_RESIZE_VIS_SIZE = 10;
    private static final int SECTIONS_RESIZE_VIS_INSET = 5;
    /** Petite croix rouge sur les sections flottantes : remet la section dans le menu. */
    private static final int FLOAT_CLOSE_BTN_SIZE = 10;
    private static final int FLOAT_CLOSE_BTN_INSET = 4;
    private static final int SECTIONS_ROW_H = 14;
    /** Lignes 0–6 : sections HUD ; ligne {@value #SECTIONS_LOG_ROW_INDEX} : Logs ; ligne {@value #SECTIONS_ENCHANT_ROW_INDEX} : Enchant ; ligne {@value #SECTIONS_AUTOFISH_ROW_INDEX} : AutoFish. */
    private static final int SECTIONS_SECTION_ROWS = 10;
    private static final int SECTIONS_LOG_ROW_INDEX = 7;
    private static final int SECTIONS_ENCHANT_ROW_INDEX = 8;
    private static final int SECTIONS_AUTOFISH_ROW_INDEX = 9;
    /** Clés i18n des lignes « section » du panneau paramètres (ordre 0–9). */
    private static final String[] SECTION_PANEL_ROW_KEYS = {
            "feather_world_menu.section.session",
            "feather_world_menu.section.prestige",
            "feather_world_menu.section.reward",
            "feather_world_menu.section.afk",
            "feather_world_menu.section.farm",
            "feather_world_menu.section.double_event",
            "feather_world_menu.section.potions",
            "feather_world_menu.section.logs",
            "feather_world_menu.section.enchant",
            "feather_world_menu.section.autofish",
    };
    /** Libellés des lignes « Touches » (identiques au dessin du panneau). */
    private static final String[] SECTIONS_KEY_BINDING_LABELS = {
            "Masque HUD", "Pause Prestige",
    };
    /** Clés i18n des infobulles « i » (ordre identique à {@link #SECTION_PANEL_ROW_KEYS}). */
    private static final String[] SECTION_PANEL_ROW_INFO_KEYS = {
            "feather_world_menu.section.session.info",
            "feather_world_menu.section.prestige.info",
            "feather_world_menu.section.reward.info",
            "feather_world_menu.section.afk.info",
            "feather_world_menu.section.farm.info",
            "feather_world_menu.section.double_event.info",
            "feather_world_menu.section.potions.info",
            "feather_world_menu.section.logs.info",
            "feather_world_menu.section.enchant.info",
            "feather_world_menu.section.autofish.info",
    };
    private static final int SECTIONS_INFO_BADGE_GAP = 4;
    /** Glyphe infobulle sections (sous-menu paramètres). */
    private static final String SECTIONS_INFO_BADGE_GLYPH = "🛈";
    /** Hit-tests infobulle : rempli pendant le dessin du sous-menu paramètres (repère contenu + scroll). */
    private static final int[] sectionsInfoBadgeHitLeft = new int[SECTIONS_SECTION_ROWS];
    private static final int[] sectionsInfoBadgeHitTop = new int[SECTIONS_SECTION_ROWS];
    private static final int[] sectionsInfoBadgeHitWidth = new int[SECTIONS_SECTION_ROWS];
    private static final int[] sectionsInfoBadgeHitHeight = new int[SECTIONS_SECTION_ROWS];
    private static final boolean[] sectionsInfoBadgeHitValid = new boolean[SECTIONS_SECTION_ROWS];
    /** Badge infobulle ligne Informations (hors indices sections). */
    private static boolean sectionsInformationsBadgeHitValid;
    private static int sectionsInformationsBadgeHitLeft;
    private static int sectionsInformationsBadgeHitTop;
    private static int sectionsInformationsBadgeHitWidth;
    private static int sectionsInformationsBadgeHitHeight;

    private static int sectionsInfoBadgeWidth(MinecraftClient client) {
        return client.textRenderer.getWidth(Text.literal(SECTIONS_INFO_BADGE_GLYPH));
    }

    /** Ordonnée du glyphe infobulle dans une ligne de hauteur {@link #SECTIONS_ROW_H} (centrage vertical). */
    private static int sectionsInfoBadgeTopInRow(MinecraftClient client, int rowY) {
        int fh = client.textRenderer.fontHeight;
        return rowY + (SECTIONS_ROW_H - fh) / 2;
    }

    /** Abscisse du badge d’info : même ancrage que {@code labelX} dans les lignes du sous-menu. */
    private static int sectionsInfoBadgeLeftX(int sx) {
        int box = 8;
        return sx + CARD_INSET + box + 4;
    }

    private static int sectionsInfoBadgeTrailingReserve(MinecraftClient client, int sectionIdx) {
        return sectionsPanelSectionRowInfoTooltip(sectionIdx).isBlank()
                ? 0
                : SECTIONS_INFO_BADGE_GAP + sectionsInfoBadgeWidth(client);
    }

    private static void clearSectionsPanelInfoBadgeHits() {
        Arrays.fill(sectionsInfoBadgeHitValid, false);
        sectionsInformationsBadgeHitValid = false;
    }

    private static void registerSectionsPanelInfoBadgeHit(int sectionIdx, int badgeLeft, int badgeTop, int badgeW, int badgeH) {
        if (sectionIdx < 0 || sectionIdx >= SECTIONS_SECTION_ROWS) {
            return;
        }
        sectionsInfoBadgeHitLeft[sectionIdx] = badgeLeft;
        sectionsInfoBadgeHitTop[sectionIdx] = badgeTop;
        sectionsInfoBadgeHitWidth[sectionIdx] = badgeW;
        sectionsInfoBadgeHitHeight[sectionIdx] = badgeH;
        sectionsInfoBadgeHitValid[sectionIdx] = true;
    }

    /**
     * Titre de ligne sous-menu (tronqué) puis badge infobulle à droite du texte.
     */
    private static void drawSectionsSubmenuRowTitleWithInfoBadge(DrawContext context, MinecraftClient client,
            int sx, int ry, Text rowTitle, int scrollTrackLeft, int sectionIdx) {
        int labelX = sectionsInfoBadgeLeftX(sx);
        int usableRight = scrollTrackLeft - 4;
        int badgeRes = sectionsInfoBadgeTrailingReserve(client, sectionIdx);
        int maxTitle = Math.max(8, usableRight - labelX - badgeRes);
        String titleShown = client.textRenderer.trimToWidth(rowTitle.getString(), maxTitle);
        context.drawText(client.textRenderer, Text.literal(titleShown), labelX, ry + 2, 0xFFECEFF1, false);
        int tw = client.textRenderer.getWidth(Text.literal(titleShown));
        if (badgeRes > 0) {
            int bx = labelX + tw + SECTIONS_INFO_BADGE_GAP;
            int by = sectionsInfoBadgeTopInRow(client, ry);
            int badgeW = sectionsInfoBadgeWidth(client);
            int badgeH = client.textRenderer.fontHeight;
            drawSectionsInfoBadge(context, client, bx, by);
            registerSectionsPanelInfoBadgeHit(sectionIdx, bx, by, badgeW, badgeH);
        }
    }
    private static final int SECTIONS_KEY_LABEL_BTN_GAP = 4;
    /** Écart entre les boutons « /10min », « /h » et « all ». */
    private static final int SECTIONS_REWARD_RESET_BTN_GAP = 2;
    /** Texte préfixe avant les boutons clear, et écart avec le premier bouton. */
    private static final String SECTIONS_REWARD_CLEAR_PREFIX = "clear :";
    private static final int SECTIONS_REWARD_CLEAR_PREFIX_GAP = 4;
    /** Préfixe de la sous-ligne sous Reward / AFK : boutons /10min et /h pour cacher la ligne correspondante. */
    private static final String SECTIONS_REWARD_CACHER_PREFIX = "cacher :";
    /** Sous-menu : branche « ↳ Money » sous la ligne Reward / AFK, avant « cacher : ». */
    private static final String SECTIONS_REWARD_MONEY_BRANCH = "\u21B3 ";
    private static boolean sectionsDragging = false;
    private static boolean sectionsResizing = false;
    private static boolean sectionsMouseDownLast = false;
    private static int sectionsDragOffX = 0;
    private static int sectionsDragOffY = 0;
    private static int sectionsResizeStartMouseX = 0;
    private static int sectionsResizeStartMouseY = 0;
    private static int sectionsResizeStartW = 252;
    private static int sectionsResizeStartH = 218;
    /**
     * Panneau « Logs » (titre seul, corps vide) : ouvert par la case « Logs » du sous-menu.
     * La case n’est pas persistée au lancement ({@link #logsMenuEnabled} repasse à {@code false}).
     */
    private static boolean logsMenuEnabled = false;
    private static int logsPosX = 72;
    private static int logsPosY = 120;
    private static int logsWidth = 480;
    private static int logsHeight = 200;
    private static boolean logsDragging = false;
    private static boolean logsResizing = false;
    private static boolean logsMouseDownLast = false;
    private static int logsDragOffX = 0;
    private static int logsDragOffY = 0;
    private static int logsResizeStartMouseX = 0;
    private static int logsResizeStartMouseY = 0;
    private static int logsResizeStartW = 480;
    private static int logsResizeStartH = 200;
    private static final int LOGS_HEADER_H = 28;
    private static final int LOGS_PANEL_FOOTER_H = 22;
    private static final int LOGS_RESIZE_VIS_SIZE = 10;
    private static final int LOGS_RESIZE_VIS_INSET = 5;
    /** Largeur mini du panneau Logs (réductible ; archives en scroll horizontal si besoin). */
    private static final int MIN_LOGS_PANEL_W = 110;
    private static final int MIN_LOGS_PANEL_H = 72;
    /** Sessions terminées (déco / fermeture du jeu) : plus ancienne à gauche, max 5 puis FIFO. */
    private static final int MAX_LOG_SESSION_ARCHIVE = 5;
    /** Durée minimale de session HUD pour qu’une entrée soit enregistrée dans les Logs. */
    private static final long MIN_SESSION_MS_FOR_LOG_ARCHIVE = 20L * 60L * 1000L;
    /** Largeur minimum d’une colonne d’archive (Reward | AFK) ; si tout ne tient pas → scroll horizontal. */
    /** Largeur mini d’une colonne d’archive (Reward | AFK + libellés / valeurs / unités lisibles). */
    private static final int LOGS_ARCHIVE_MIN_COL_W = 232;
    private static final ArrayDeque<LogSessionArchiveEntry> logSessionArchive = new ArrayDeque<>();
    /** Défilement horizontal des colonnes d’archives dans le panneau Logs. */
    private static int logsScrollHorizPx = 0;
    /**
     * Début fixe d’une ligne serveur dans {@code latest.log} (la suite peut varier). Même pipeline que le HUD
     * (dont chemins Lunar via {@link #resolveLatestLogPath}).
     */
    private static final String AUTOFISH_LOG_TRIGGER_PREFIX = "Requested creation of existing team 'fish_team";
    private static boolean autoFishLogTriggerEnabled = false;
    /** Second clic « utiliser » reporté au tick suivant (comportement proche d’un double-clic). */
  /** Second « utiliser » canne : délai après le premier (ms). */
    private static final long AUTOFISH_SECOND_USE_DELAY_MS = 200L;
    private static long autoFishSecondUseDueAtMs = 0L;

    /** Case « Informations » sous-menu (persistée, même aspect que AutoFish / autres sections). */
    private static boolean informationsRowEnabled = false;

    /** Panneau « Enchant » : ouvert par la case « Enchant » du sous-menu. */
    private static boolean enchantMenuEnabled = false;
    private static int enchantPosX = 94;
    private static int enchantPosY = 152;
    private static int enchantWidth = 190;
    private static int enchantHeight = 150;
    private static boolean enchantDragging = false;
    private static boolean enchantResizing = false;
    private static boolean enchantMouseDownLast = false;
    private static int enchantDragOffX = 0;
    private static int enchantDragOffY = 0;
    private static int enchantResizeStartMouseX = 0;
    private static int enchantResizeStartMouseY = 0;
    private static int enchantResizeStartW = 190;
    private static int enchantResizeStartH = 150;
    private static int enchantScrollPx = 0;
    private static final int ENCHANT_HEADER_H = 28;
    private static final int ENCHANT_PANEL_FOOTER_H = 22;
    private static final int ENCHANT_RESIZE_VIS_SIZE = 10;
    private static final int ENCHANT_RESIZE_VIS_INSET = 5;
    private static final int MIN_ENCHANT_PANEL_W = 120;
    private static final int MIN_ENCHANT_PANEL_H = 72;
    /** Panneau « Options » lié à Enchant : ouvert par le bouton « options » de la sous-ligne. */
    /** Affiche les montants cumulés après « +N » dans le panneau Enchant (bouton « cumuls »). */
    private static boolean enchantShowCumulativeSums = true;
    /** {@code false} = tri Enchant par procs (+N) ; {@code true} = tri par cumuls. */
    private static boolean enchantPanelSortByCumuls = false;
    private static boolean enchantOptionsMenuEnabled = false;
    private static int enchantOptionsPosX = 132;
    private static int enchantOptionsPosY = 190;
    private static int enchantOptionsWidth = 190;
    private static int enchantOptionsHeight = 150;
    private static boolean enchantOptionsDragging = false;
    private static boolean enchantOptionsResizing = false;
    private static boolean enchantOptionsMouseDownLast = false;
    private static int enchantOptionsDragOffX = 0;
    private static int enchantOptionsDragOffY = 0;
    private static int enchantOptionsResizeStartMouseX = 0;
    private static int enchantOptionsResizeStartMouseY = 0;
    private static int enchantOptionsResizeStartW = 190;
    private static int enchantOptionsResizeStartH = 150;
    private static int enchantOptionsScrollPx = 0;
    private static final int ENCHANT_OPTIONS_HEADER_H = 28;
    private static final int ENCHANT_OPTIONS_PANEL_FOOTER_H = 22;
    private static final int ENCHANT_OPTIONS_RESIZE_VIS_SIZE = 10;
    private static final int ENCHANT_OPTIONS_RESIZE_VIS_INSET = 5;
    private static final int MIN_ENCHANT_OPTIONS_PANEL_W = 120;
    private static final int MIN_ENCHANT_OPTIONS_PANEL_H = 72;
    private static final int ENCHANT_OPTIONS_COL_GAP_X = 4;
    private static final int ENCHANT_OPTIONS_BTN_GAP_Y = 3;
    private static final int ENCHANT_OPTIONS_BTN_TEXT_PAD_X = 4;
    private static final int ENCHANT_OPTIONS_BTN_H = SECTIONS_ROW_H + 1;
    private static final int ENCHANT_OPTIONS_COL_MIN_W = 108;
    private static final int ENCHANT_PANEL_COL_GAP_X = 6;
    private static final int ENCHANT_PANEL_COL_MIN_W = 84;
    private static final int ENCHANT_PANEL_ENTRY_GAP_Y = 2;
    /** Espace entre le nom d’enchant et la ligne procs / cumuls. */
    private static final int ENCHANT_PANEL_ENTRY_STATS_GAP = 1;
    private static final float ENCHANT_PANEL_TEXT_SCALE = 0.85f;
    private static final String ENCHANT_OPTIONS_LAC_TITLE = "Lac";
    private static final String ENCHANT_OPTIONS_FARM_TITLE = "Ferme";
    private static final String[] ENCHANT_LAC_OPTION_LABELS = {
            "expérience",
            "trésor",
            "filet",
            "onde rotative",
            "poisson béni",
            "tsunami",
            "roulette",
            "dragon aqueux",
            "prise spéciale",
            "mégalodon",
            "rage berserker",
            "élévation",
            "épéé élémentaire",
            "poséidon",
            "sablier magique",
            "antiquitaire",
            "braquage",
            "générateur de lostcoins"
    };
    private static final String[] ENCHANT_MINE_OPTION_LABELS = {
            "expérience",
            "trésor",
            "boomeur",
            "thor",
            "liens du néant",
            "stomp",
            "golem antique",
            "tacos épicé",
            "roulette",
            "foreuse",
            "paradis des nains",
            "pluie de lances",
            "sablier magique",
            "trou noir",
            "apocalypse",
            "générateur de lostcoins"
    };
    private static final String[] ENCHANT_FARM_OPTION_LABELS = {
            "expérience",
            "boomeur",
            "trésor",
            "laser",
            "avalanche",
            "flash",
            "thor",
            "ferme en folie",
            "pyrobarbare",
            "roulette",
            "multi-orbes",
            "asteroide",
            "clone",
            "engrais magique",
            "ange vengeur",
            "rage berserker",
            "ambidextre",
            "essaim mielleux",
            "warden",
            "kung-fu panda",
            "apocalypse",
            "braquage",
            "peste noire",
            "générateur de lostcoins"
    };
    private static final boolean[] enchantLacOptionEnabled = new boolean[ENCHANT_LAC_OPTION_LABELS.length];
    /** Compteurs d’occurrences exactes lues dans les logs pour les boutons Lac du menu Options. */
    private static final int[] enchantLacOptionLogCounts = new int[ENCHANT_LAC_OPTION_LABELS.length];
    /** Cumul des montants détectés après le mot-clé (ex. « exp ») ; remis à zéro à la fermeture du jeu uniquement. */
    private static final double[] enchantLacOptionLogSums = new double[ENCHANT_LAC_OPTION_LABELS.length];
    private static final Map<String, Double>[] enchantLacOptionLogSumsByType = newEnchantPerTypeSumMaps(ENCHANT_LAC_OPTION_LABELS.length);
    private static final boolean[] enchantMineOptionEnabled = new boolean[ENCHANT_MINE_OPTION_LABELS.length];
    private static final int[] enchantMineOptionLogCounts = new int[ENCHANT_MINE_OPTION_LABELS.length];
    private static final double[] enchantMineOptionLogSums = new double[ENCHANT_MINE_OPTION_LABELS.length];
    private static final Map<String, Double>[] enchantMineOptionLogSumsByType = newEnchantPerTypeSumMaps(ENCHANT_MINE_OPTION_LABELS.length);
    private static final boolean[] enchantFarmOptionEnabled = new boolean[ENCHANT_FARM_OPTION_LABELS.length];
    private static final int[] enchantFarmOptionLogCounts = new int[ENCHANT_FARM_OPTION_LABELS.length];
    private static final double[] enchantFarmOptionLogSums = new double[ENCHANT_FARM_OPTION_LABELS.length];
    private static final Map<String, Double>[] enchantFarmOptionLogSumsByType = newEnchantPerTypeSumMaps(ENCHANT_FARM_OPTION_LABELS.length);
    private record EnchantOptionSet(String configKey, String title, String[] labels, boolean[] enabled, int[] logCounts,
            double[] logSums, Map<String, Double>[] logSumsByType) {
    }
    private record EnchantCumulValue(double amount, String typeWord) {
    }
    private record EnchantOptionMatch(EnchantOptionSet optionSet, int optionIndex, String matchedLabel, int contentStart) {
    }
    private static final EnchantOptionSet ENCHANT_LAC_OPTIONS = new EnchantOptionSet(
            "lac", ENCHANT_OPTIONS_LAC_TITLE, ENCHANT_LAC_OPTION_LABELS, enchantLacOptionEnabled, enchantLacOptionLogCounts,
            enchantLacOptionLogSums, enchantLacOptionLogSumsByType);
    private static final EnchantOptionSet ENCHANT_MINE_OPTIONS = new EnchantOptionSet(
            "mine", "Mine", ENCHANT_MINE_OPTION_LABELS, enchantMineOptionEnabled, enchantMineOptionLogCounts,
            enchantMineOptionLogSums, enchantMineOptionLogSumsByType);
    private static final EnchantOptionSet ENCHANT_FARM_OPTIONS = new EnchantOptionSet(
            "farm", ENCHANT_OPTIONS_FARM_TITLE, ENCHANT_FARM_OPTION_LABELS, enchantFarmOptionEnabled, enchantFarmOptionLogCounts,
            enchantFarmOptionLogSums, enchantFarmOptionLogSumsByType);

    @SuppressWarnings("unchecked")
    private static Map<String, Double>[] newEnchantPerTypeSumMaps(int size) {
        Map<String, Double>[] maps = (Map<String, Double>[]) new Map[size];
        for (int i = 0; i < size; i++) {
            maps[i] = new HashMap<>();
        }
        return maps;
    }
    private static final List<EnchantOptionSet> ENCHANT_OPTION_SETS = List.of(
            ENCHANT_LAC_OPTIONS, ENCHANT_MINE_OPTIONS, ENCHANT_FARM_OPTIONS);
    private static final int SECTIONS_KEY_HEADER_GAP = 6;
    /** Espace au-dessus de la ligne « Informations » (sous les sections, avant « Touches »). */
    private static final int SECTIONS_INFORMATIONS_HEADER_GAP = 4;
    private static final int SECTIONS_KEY_ROW_H = 12;
    private static final int KEY_CAPTURE_NONE = -1;
    private static int captureBindingIndex = KEY_CAPTURE_NONE;
    /** Défilement vertical du corps de la carte (sections dockées). */
    private static int mainCardScrollPx = 0;
    /** Défilement vertical du panneau paramètres (sous le titre fixe). */
    private static int sectionsScrollPx = 0;

    private static RewardDisplayZone rewardDisplayZone = RewardDisplayZone.CHAMP;
    /** Zone CHAMP/MINE/LAC affichée pour la section AFK (indépendante de Reward). */
    private static RewardDisplayZone afkDisplayZone = RewardDisplayZone.CHAMP;

    private static boolean showHudSession = true;
    private static boolean showHudPrestige = false;
    private static boolean showHudAfk = false;
    /** État de {@link #showHudAfk} mémorisé à l’entrée AFK pour le restaurer à la sortie. */
    private static boolean showHudAfkSavedBeforeAfk = false;
    /** Vrai tant qu’on a auto-affiché la section AFK suite à un passage en AFK. */
    private static boolean afkAutoShownActive = false;
    private static boolean showHudReward = true;
    /** Cache la ligne « /10min » dans la section Reward du HUD (case « cacher : /10min »). */
    private static boolean hideRewardTenMin = false;
    /** Cache la ligne « /h » dans la section Reward du HUD (case « cacher : /h »). */
    private static boolean hideRewardHourly = false;
    /** Cache la ligne « /10min » dans la section AFK du HUD. */
    private static boolean hideAfkTenMin = false;
    /** Cache la ligne « /h » dans la section AFK du HUD. */
    private static boolean hideAfkHourly = false;
    /** Si {@code true}, n’affiche pas la ligne « Money » dans la section Reward du HUD. */
    private static boolean hideRewardMoneyRow = false;
    /** Si {@code true}, n’affiche pas la ligne « Money » dans la section AFK du HUD. */
    private static boolean hideAfkMoneyRow = false;
    private static boolean showHudFarm = false;
    private static boolean showHudDouble = false;
    private static boolean showHudPotions = false;
    /** Sections 0–6 : détachables du menu principal (mode édition [H]). */
    private static final int HUD_SECTION_COUNT = 7;
    private static final int MIN_FLOAT_SECTION_PAD_BOTTOM = 0;
    private static final int MAX_FLOAT_SECTION_PAD_BOTTOM = 160;
    private static final boolean[] hudSectionFloating = new boolean[HUD_SECTION_COUNT];
    private static final int[] hudSectionFloatX = new int[HUD_SECTION_COUNT];
    private static final int[] hudSectionFloatY = new int[HUD_SECTION_COUNT];
    /** Largeur d’une section flottante (mode édition) ; 0 = reprendre {@link #cardWidth} au détachement. */
    private static final int[] hudSectionFloatW = new int[HUD_SECTION_COUNT];
    /** Espace vide sous le contenu (hauteur totale = contenu + pad). */
    private static final int[] hudSectionFloatPadBottom = new int[HUD_SECTION_COUNT];
    private static final int[] hudDockHitTop = new int[HUD_SECTION_COUNT];
    private static final int[] hudDockHitBottom = new int[HUD_SECTION_COUNT];
    private static int hudSectionDragIndex = -1;
    private static int hudSectionDragOffX = 0;
    private static int hudSectionDragOffY = 0;
    private static boolean hudSectionDragStartedDocked = false;
    private static int hudFloatResizeIndex = -1;
    private static int hudFloatResizeStartMouseX = 0;
    private static int hudFloatResizeStartMouseY = 0;
    private static int hudFloatResizeStartW = 0;
    private static int hudFloatResizeStartTotalH = 0;
    private static final LinkedHashMap<String, StatLine> STATS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, RewardTracker> REWARDS = new LinkedHashMap<>();
    /** Totaux AFK : mêmes clés que {@link #REWARDS}, incrémentés seulement pendant la période AFK. */
    private static final LinkedHashMap<String, RewardTracker> AFK_REWARDS = new LinkedHashMap<>();
    /**
     * Money / Exp : totaux séparés par monde (zone scoreboard) pour la section Reward hors AFK.
     * Les autres ressources restent globales dans {@link #REWARDS}.
     */
    private static final EnumMap<RewardDisplayZone, RewardTracker> rewardMoneyByZone = new EnumMap<>(RewardDisplayZone.class);
    private static final EnumMap<RewardDisplayZone, RewardTracker> rewardExpByZone = new EnumMap<>(RewardDisplayZone.class);
    /** Même logique Money / Exp par zone pour la section AFK. */
    private static final EnumMap<RewardDisplayZone, RewardTracker> afkMoneyByZone = new EnumMap<>(RewardDisplayZone.class);
    private static final EnumMap<RewardDisplayZone, RewardTracker> afkExpByZone = new EnumMap<>(RewardDisplayZone.class);
    /**
     * Chrono farm Reward : une session par zone scoreboard (Champ/Ferme, Mine, Lac).
     * Premier recap : affichage à {@value #REWARD_FARM_FIRST_RECAP_OFFSET_MS} ms (1 min 5 s).
     * Sans recap pendant {@value #REWARD_FARM_SILENCE_MS} ms : le chrono s’arrête sur le cumul au dernier recap ;
     * au recap suivant on rajoute {@value #REWARD_FARM_RESUME_AFTER_SILENCE_BONUS_MS} ms au cumul puis le temps coule à nouveau.
     * Remise à zéro : déconnexion ({@link #clearSessionTracking}).
     */
    private static final long REWARD_FARM_FIRST_RECAP_OFFSET_MS = 65_000L;
    /** Fenêtre après dernier recap Reward ; à l’arrêt on retire ces {@value #REWARD_FARM_SILENCE_MS} ms du cumul affiché. */
    private static final long REWARD_FARM_SILENCE_MS = 80_000L;
    /** Après pause (&gt; silence), bonus Reward ajouté au cumul au recap suivant (65 s). */
    private static final long REWARD_FARM_RESUME_AFTER_SILENCE_BONUS_MS = 65_000L;
    private static final EnumMap<RewardDisplayZone, RewardFarmSessionState> rewardFarmSessionByZone = new EnumMap<>(RewardDisplayZone.class);
    /**
     * Chrono farm AFK : même principe que Reward ; après silence, un recap rajoute {@value #AFK_FARM_RESUME_AFTER_SILENCE_BONUS_MS} ms (1 min 30 s).
     * Lac {@value #AFK_FARM_SILENCE_LAC_MS} ms, Champ et Mine {@value #AFK_FARM_SILENCE_FIELD_MS} ms.
     * Premier recap AFK : {@value #AFK_FARM_FIRST_RECAP_OFFSET_MS} ms (1 min 30 s).
     * Remise à zéro : déconnexion ({@link #clearSessionTracking}) uniquement ; sortie AFK = pause affichage ({@link #pauseAfkFarmSessionsAtLeave}).
     */
    private static final long AFK_FARM_FIRST_RECAP_OFFSET_MS = 90_000L;
    /** Monde Lac AFK : silence puis arrêt en retranchant 1 min 40 au pic affiché (= cumul dernier recap). */
    private static final long AFK_FARM_SILENCE_LAC_MS = 100_000L;
    /** Mondes Champ/Ferme et Mine AFK : silence puis arrêt en retranchant 80 s au pic (= cumul dernier recap). */
    private static final long AFK_FARM_SILENCE_FIELD_MS = 80_000L;
    /** Après pause (&gt; silence), bonus AFK ajouté au cumul au recap suivant (1 min 30 s). */
    private static final long AFK_FARM_RESUME_AFTER_SILENCE_BONUS_MS = 90_000L;
    private static final EnumMap<RewardDisplayZone, RewardFarmSessionState> afkFarmSessionByZone = new EnumMap<>(RewardDisplayZone.class);
    private static final LinkedHashMap<String, PotionSlot> POTION_SLOTS = new LinkedHashMap<>();

    private static final class RewardFarmSessionState {
        /** Origine du segment courant (recalée après pause &gt; silence : cumul figé + bonus Reward ou AFK). */
        long sessionStartMs;
        /** Horodatage du dernier recap parsé pour cette zone. */
        long lastRecapMs;
    }

    private static boolean prestigeRunActive = false;
    private static long prestigeStartMs = 0L;
    /** Dernière durée entre deux lignes {@code LostEra » Tu es passé Prestige} (ms) ; {@code -1} si aucune. */
    private static long prestigeDernierDurationMs = -1L;
    /** Si {@code true}, le chrono « Actuel » est figé sur {@link #prestigeFrozenElapsedMs}. */
    private static boolean prestigePaused = false;
    private static long prestigeFrozenElapsedMs = 0L;
    /** Reprendre le chrono prestige à la reconnexion si la déco avait lieu en AFK. */
    private static boolean prestigeAutoResumeOnAfkReconnect = false;

    static {
        STATS.put("money", new StatLine("Money", 0xFFFFCF33));
        STATS.put("gemmes", new StatLine("Gemmes", 0xFFB56AFF));
        STATS.put("orbes", new StatLine("Orbes", 0xFF69E7FF));
        STATS.put("perles", new StatLine("Perles", 0xFFFF8DE9));
        STATS.put("exp", new StatLine("Exp", 0xFFE5E5E5));
        STATS.put("blocs", new StatLine("Blocs", 0xFFFFD35A));
        STATS.put("poissons", new StatLine("Poissons", 0xFF6DE1FF));
        STATS.put("cultures", new StatLine("Cultures", 0xFF8BC34A));
        for (Map.Entry<String, StatLine> e : STATS.entrySet()) {
            StatLine s = e.getValue();
            REWARDS.put(e.getKey(), new RewardTracker(s.label(), s.color()));
            AFK_REWARDS.put(e.getKey(), new RewardTracker(s.label(), s.color()));
        }
        StatLine moneyStat = STATS.get("money");
        StatLine expStat = STATS.get("exp");
        for (RewardDisplayZone z : RewardDisplayZone.values()) {
            rewardMoneyByZone.put(z, new RewardTracker(moneyStat.label(), moneyStat.color()));
            rewardExpByZone.put(z, new RewardTracker(expStat.label(), expStat.color()));
            afkMoneyByZone.put(z, new RewardTracker(moneyStat.label(), moneyStat.color()));
            afkExpByZone.put(z, new RewardTracker(expStat.label(), expStat.color()));
        }

        POTION_SLOTS.put("money", new PotionSlot("Money"));
        POTION_SLOTS.put("perles", new PotionSlot("Perles"));
        POTION_SLOTS.put("gemmes", new PotionSlot("Gemmes"));
        POTION_SLOTS.put("level", new PotionSlot("Levels"));
        POTION_SLOTS.put("orbes", new PotionSlot("Orbes"));
        POTION_SLOTS.put("proc", new PotionSlot("Proc"));
    }

    private WorldHudOverlay() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(WorldHudOverlay::render);
        ClientLifecycleEvents.CLIENT_STOPPING.register(WorldHudOverlay::onClientStopping);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onPlayConnectionDisconnect(client));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onPlayConnectionJoin(client));
    }

    /**
     * À appeler chaque tick client : session + lecture de {@code latest.log} (récompenses, AFK, etc.).
     * Indépendant du rendu HUD pour ne pas rater de lignes quand le HUD ne dessine pas (menu, F1, …).
     */
    public static void clientTick(MinecraftClient client) {
        if (client == null) {
            return;
        }
        ensureLayoutLoaded(client);
        syncModHudVisibilityAfterPauseMenus(client);
        detectPlayConnectionChange(client);
        resetForNewSessionIfNeeded(client);
        if (afkWorldEntryDetectionPending) {
            beginAfkDetectionForWorldEntry(client);
        }
        tryRestorePendingAfkState(client);
        tickAutoFishScheduledSecondUse(client);
        updateEventsFromLogs(client);
        tickAfkWorldEntryProbe(client);
        tickReconnectAfkCommand(client);
        tryClearReconnectRestoredAfkOnLocalActivity(client);
    }

    /**
     * Molette : priorité au panneau paramètres puis à la carte HUD si la souris est dans la zone défilable.
     *
     * @return {@code true} si l’événement ne doit pas être propagé (évite de faire défiler l’écran derrière).
     */
    public static boolean tryConsumeMouseScroll(MinecraftClient client, double horizontal, double vertical) {
        if (vertical == 0.0 && horizontal == 0.0) {
            return false;
        }
        if (client.getWindow() == null) {
            return false;
        }
        if (hideHudForMinecraftMenu) {
            return false;
        }
        double scaledW = client.getWindow().getScaledWidth();
        double scaledH = client.getWindow().getScaledHeight();
        double fw = client.getWindow().getWidth();
        double fhWin = client.getWindow().getHeight();
        int mouseX = (int) (client.mouse.getX() * scaledW / fw);
        int mouseY = (int) (client.mouse.getY() * scaledH / fhWin);

        int step = (int) Math.round(-vertical * SCROLL_PIXELS_PER_WHEEL_UNIT);
        if (step == 0) {
            step = vertical > 0 ? 1 : -1;
        }

        if (enchantOptionsMenuEnabled && (client.currentScreen != null || editMode)) {
            int ox = enchantOptionsPosX;
            int oy = enchantOptionsPosY;
            int or = ox + enchantOptionsWidth;
            int ob = oy + enchantOptionsHeight;
            int innerTop = oy + ENCHANT_OPTIONS_HEADER_H + 2;
            int innerBottom = ob - ENCHANT_OPTIONS_PANEL_FOOTER_H;
            if (mouseX >= ox + 1 && mouseX < or - 1 && mouseY >= innerTop && mouseY < innerBottom) {
                int maxScroll = computeEnchantOptionsPanelScrollMax(client);
                if (maxScroll > 0) {
                    enchantOptionsScrollPx = clamp(enchantOptionsScrollPx + step, 0, maxScroll);
                    return true;
                }
            }
        }

        if (enchantMenuEnabled && (client.currentScreen != null || editMode)) {
            int ex = enchantPosX;
            int ey = enchantPosY;
            int er = ex + enchantWidth;
            int eb = ey + enchantHeight;
            int innerTop = ey + ENCHANT_HEADER_H + 2;
            int innerLeft = ex + CARD_INSET;
            int scrollTrackLeft = enchantPanelScrollTrackLeft(ex, enchantWidth);
            int contentTop = enchantPanelScrollContentTop(client, innerTop, innerLeft, scrollTrackLeft);
            int innerBottom = eb - ENCHANT_PANEL_FOOTER_H;
            if (mouseX >= ex + 1 && mouseX < scrollTrackLeft && mouseY >= contentTop && mouseY < innerBottom) {
                int maxScroll = computeEnchantPanelScrollMax(client);
                if (maxScroll > 0) {
                    enchantScrollPx = clamp(enchantScrollPx + step, 0, maxScroll);
                    return true;
                }
            }
        }

        if (logsMenuEnabled && (client.currentScreen != null || editMode)) {
            int lx = logsPosX;
            int ly = logsPosY;
            int lw = logsWidth;
            int lh = logsHeight;
            int lr = lx + lw;
            int lb = ly + lh;
            int innerTop = ly + LOGS_HEADER_H + 2;
            int innerBottom = lb - LOGS_PANEL_FOOTER_H;
            int innerW = Math.max(0, lw - 2 * CARD_INSET);
            if (mouseX >= lx + 1 && mouseX < lr - 1 && mouseY >= innerTop && mouseY < innerBottom) {
                int gap = 2;
                int maxLh = logsArchiveHScrollMax(innerW, logSessionArchive.size(), gap);
                if (maxLh > 0) {
                    int deltaY = (int) Math.round(-vertical * SCROLL_PIXELS_PER_WHEEL_UNIT);
                    int deltaX = (int) Math.round(-horizontal * SCROLL_PIXELS_PER_WHEEL_UNIT);
                    int horizStep = deltaY + deltaX;
                    if (horizStep == 0 && (vertical != 0.0 || horizontal != 0.0)) {
                        horizStep = vertical > 0.0 || horizontal > 0.0 ? 1 : -1;
                    }
                    logsScrollHorizPx = clamp(logsScrollHorizPx + horizStep, 0, maxLh);
                    return true;
                }
            }
        }

        if (sectionsPanelOpen) {
            int sx = sectionsPosX;
            int sy = sectionsPosY;
            int swp = sectionsWidth;
            int shp = sectionsHeight;
            int innerTop = sy + SECTIONS_HEADER_H + 2;
            int innerBottom = sy + shp - SECTIONS_PANEL_FOOTER_H;
            int sbRight = sx + swp - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
            if (mouseX >= sx + CARD_INSET && mouseX < sbRight
                    && mouseY >= innerTop && mouseY < innerBottom) {
                int maxS = computeSectionsPanelScrollMax(client, sy, shp);
                if (maxS > 0) {
                    sectionsScrollPx = clamp(sectionsScrollPx + step, 0, maxS);
                    return true;
                }
            }
        }

        if (panelVisible && (client.currentScreen != null || editMode)) {
            int bottom = posY + cardHeight;
            int right = posX + cardWidth;
            int vpTop = mainCardScrollViewportTop(posY);
            int vpBottom = bottom - mainCardFooterReservePx(client);
            int maxM = mainCardScrollMax(client, posY, bottom, cardWidth);
            int cardSbRight = right - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
            if (maxM > 0 && mouseX >= posX + CARD_INSET && mouseX < cardSbRight
                    && mouseY >= vpTop && mouseY < vpBottom) {
                mainCardScrollPx = clamp(mainCardScrollPx + step, 0, maxM);
                return true;
            }
        }
        return false;
    }

    /**
     * Clic gauche : bloque l’attaque / les GUI derrière tant que la souris est sur la carte HUD en édition,
     * sur le panneau paramètres, ou pendant un drag / resize (même si le curseur sort de la zone).
     * Les interactions Feather lisent l’état GLFW dans le rendu ; elles ne passent pas par ce chemin.
     *
     * @return {@code true} pour annuler la suite de {@link net.minecraft.client.Mouse#onMouseButton}.
     */
    public static boolean tryConsumeMouseButton(MinecraftClient client, int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (client == null || client.getWindow() == null) {
            return false;
        }
        if (hideHudForMinecraftMenu) {
            return false;
        }
        if (sectionsDragging || sectionsResizing || logsDragging || logsResizing
                || enchantDragging || enchantResizing || enchantOptionsDragging || enchantOptionsResizing
                || dragging || resizing
                || hudSectionDragIndex >= 0 || hudFloatResizeIndex >= 0) {
            return true;
        }
        double scaledW = client.getWindow().getScaledWidth();
        double scaledH = client.getWindow().getScaledHeight();
        double fw = client.getWindow().getWidth();
        double fhWin = client.getWindow().getHeight();
        int mouseX = (int) (client.mouse.getX() * scaledW / fw);
        int mouseY = (int) (client.mouse.getY() * scaledH / fhWin);

        if (enchantOptionsMenuEnabled && (client.currentScreen != null || editMode)
                && mouseInsideEnchantOptionsPanelBounds(mouseX, mouseY)) {
            return true;
        }
        if (enchantMenuEnabled && (client.currentScreen != null || editMode)
                && mouseInsideEnchantPanelBounds(mouseX, mouseY)) {
            return true;
        }
        if (logsMenuEnabled && (client.currentScreen != null || editMode)
                && mouseInsideLogsPanelBounds(mouseX, mouseY)) {
            return true;
        }
        if (sectionsPanelOpen && mouseInsideSectionsPanelBounds(mouseX, mouseY)) {
            return true;
        }
        if (panelVisible && !shouldHideHudForF1Only(client)
                && (mainCardParametresButtonHit(client, mouseX, mouseY) || mainCardEditerButtonHit(client, mouseX, mouseY)
                        || mainCardFermerSaveButtonHit(client, mouseX, mouseY))) {
            return true;
        }
        if (panelVisible && editMode && client.currentScreen != null && mouseInsideHudEditCaptureZone(client, mouseX, mouseY)) {
            return true;
        }
        return false;
    }

    private static boolean mouseInsideSectionsPanelBounds(int mouseX, int mouseY) {
        return mouseX >= sectionsPosX && mouseX <= sectionsPosX + sectionsWidth
                && mouseY >= sectionsPosY && mouseY <= sectionsPosY + sectionsHeight;
    }

    /**
     * Zones où le HUD en édition ne doit pas « voler » les clics aux panneaux dessinés par-dessus
     * (barre de drag / contenu), pour ne pas attraper une section flottante derrière.
     */
    private static boolean mouseOverHudSectionBlockingOverlays(int mouseX, int mouseY) {
        if (enchantOptionsMenuEnabled && mouseInsideEnchantOptionsPanelBounds(mouseX, mouseY)) {
            return true;
        }
        if (enchantMenuEnabled && mouseInsideEnchantPanelBounds(mouseX, mouseY)) {
            return true;
        }
        if (logsMenuEnabled && mouseInsideLogsPanelBounds(mouseX, mouseY)) {
            return true;
        }
        return sectionsPanelOpen && mouseInsideSectionsPanelBounds(mouseX, mouseY);
    }

    private static boolean mouseInsideEnchantPanelBounds(int mouseX, int mouseY) {
        return mouseX >= enchantPosX && mouseX <= enchantPosX + enchantWidth
                && mouseY >= enchantPosY && mouseY <= enchantPosY + enchantHeight;
    }

    private static boolean mouseInsideEnchantOptionsPanelBounds(int mouseX, int mouseY) {
        return mouseX >= enchantOptionsPosX && mouseX <= enchantOptionsPosX + enchantOptionsWidth
                && mouseY >= enchantOptionsPosY && mouseY <= enchantOptionsPosY + enchantOptionsHeight;
    }

    private static boolean mouseInsideLogsPanelBounds(int mouseX, int mouseY) {
        return mouseX >= logsPosX && mouseX <= logsPosX + logsWidth
                && mouseY >= logsPosY && mouseY <= logsPosY + logsHeight;
    }

    /**
     * La souris est sur un panneau (Logs, Paramètres) ou une section HUD flottante : la carte principale
     * ne doit pas prendre un drag d’en-tête / resize dans cette zone.
     */
    private static boolean mouseBlocksMainHudCardChrome(MinecraftClient client, int mouseX, int mouseY) {
        if (mouseOverHudSectionBlockingOverlays(mouseX, mouseY)) {
            return true;
        }
        if (!panelVisible || !editMode) {
            return false;
        }
        for (int i = 0; i < HUD_SECTION_COUNT; i++) {
            if (!hudSectionShown(i) || !hudSectionFloating[i]) {
                continue;
            }
            int fph = getHudFloatPanelHeight(client, i);
            if (fph <= 0) {
                continue;
            }
            int fw = hudSectionDisplayWidth(client, i);
            int fx = hudSectionFloatX[i];
            int fy = hudSectionFloatY[i];
            if (mouseX >= fx && mouseX <= fx + fw && mouseY >= fy && mouseY <= fy + fph) {
                return true;
            }
        }
        return false;
    }

    /** Texte {@link #DRAG_RESIZE_HINT} juste à gauche du carré de resize (ne passe pas avant {@code minTextX}). */
    private static void drawDragResizeHintLeftOfSquare(DrawContext context, MinecraftClient client,
            int squareLeft, int squareTop, int squareSize, int minTextX, int color) {
        int fh = client.textRenderer.fontHeight;
        int textY = squareTop + (squareSize - fh) / 2 + 1;
        int gap = 4;
        int maxW = Math.max(12, squareLeft - gap - minTextX);
        String shown = client.textRenderer.trimToWidth(DRAG_RESIZE_HINT, maxW);
        int tw = client.textRenderer.getWidth(Text.literal(shown));
        int textX = Math.max(minTextX, squareLeft - gap - tw);
        context.drawText(client.textRenderer, Text.literal(shown), textX, textY, color, false);
    }

    private static boolean mouseInsideHudEditCaptureZone(MinecraftClient client, int mouseX, int mouseY) {
        if (mouseX >= posX && mouseX <= posX + cardWidth && mouseY >= posY && mouseY <= posY + cardHeight) {
            return true;
        }
        for (int i = 0; i < HUD_SECTION_COUNT; i++) {
            if (!hudSectionShown(i) || !hudSectionFloating[i]) {
                continue;
            }
            int fph = getHudFloatPanelHeight(client, i);
            if (fph <= 0) {
                continue;
            }
            int fw = hudSectionDisplayWidth(client, i);
            int fx = hudSectionFloatX[i];
            int fy = hudSectionFloatY[i];
            if (mouseX >= fx && mouseX <= fx + fw && mouseY >= fy && mouseY <= fy + fph) {
                return true;
            }
        }
        return false;
    }

    private static boolean mouseInsideMainCardScrollViewport(MinecraftClient client, int mouseX, int mouseY) {
        int vpTop = mainCardScrollViewportTop(posY);
        int vpBottom = posY + cardHeight - mainCardFooterReservePx(client);
        int scissorRight = posX + cardWidth - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
        return mouseX >= posX && mouseX < scissorRight && mouseY >= vpTop && mouseY < vpBottom;
    }

    private static boolean mouseInsideSectionsScrollableViewport(int mouseX, int mouseY, int sx, int sy, int sw, int sh) {
        int innerTop = sy + SECTIONS_HEADER_H + 2;
        int innerBottom = sy + sh - SECTIONS_PANEL_FOOTER_H;
        int scrollTrackLeft = sx + sw - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
        return mouseX >= sx + 1 && mouseX < scrollTrackLeft && mouseY >= innerTop && mouseY < innerBottom;
    }

    private static boolean mouseInsideEnchantOptionsScrollableViewport(int mouseX, int mouseY, int ox, int oy, int ow, int oh) {
        int innerTop = oy + ENCHANT_OPTIONS_HEADER_H + 2;
        int innerBottom = oy + oh - ENCHANT_OPTIONS_PANEL_FOOTER_H;
        int scrollTrackLeft = ox + ow - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
        return mouseX >= ox + 1 && mouseX < scrollTrackLeft && mouseY >= innerTop && mouseY < innerBottom;
    }

    /**
     * Chat ou commande envoyé(e) : sortie AFK « locale » après reconnexion
     * ({@link #afkAwaitingLocalActivityClearAfterReconnect}).
     */
    public static void onReconnectRestoredAfkSendAttempt() {
        if (suppressReconnectAfkSendClear) {
            suppressReconnectAfkSendClear = false;
            return;
        }
        if (afkAwaitingLocalActivityClearAfterReconnect && afkRewardLoggingActive) {
            clearAfkLoggingClientStateAsIfLeft();
        }
    }

    /**
     * Messages affichés en jeu (chat / barre d’action) : boosts potion uniquement (pas depuis {@code latest.log}).
     * Les rewards restent lus via {@code latest.log} uniquement.
     */
    public static void onClientGameMessage(String messageText) {
        if (messageText == null || messageText.isBlank()) {
            return;
        }
        String plain = LEGACY_FORMATTING_CODES.matcher(messageText).replaceAll("");
        plain = ANSI_ESCAPE.matcher(plain).replaceAll("");
        applyPotionBoostFromPlainText(plain.trim());
    }

    public static void toggleEditor(MinecraftClient client) {
        editMode = !editMode;
        if (!editMode) {
            mainCardScrollPx = 0;
            hudSectionDragIndex = -1;
            hudFloatResizeIndex = -1;
            persistAllMenuPositions(client);
        }
    }

    public static void togglePanelVisible() {
        panelVisible = !panelVisible;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null) {
            persistAllMenuPositions(c);
        }
        if (!panelVisible) {
            if (editMode && c != null) {
                editMode = false;
            } else {
                editMode = false;
            }
            dragging = false;
            resizing = false;
            hudSectionDragIndex = -1;
            hudFloatResizeIndex = -1;
        }
    }

    /**
     * {@code hudHidden} (F1) masque le HUD en jeu sans GUI. Certains clients (ex. Feather) le positionnent aussi
     * à l’ouverture du menu Échap : dans ce cas il faut quand même dessiner notre carte / panneau.
     */
    private static boolean shouldHideHudForF1Only(MinecraftClient client) {
        return client.options.hudHidden && client.currentScreen == null && client.world != null;
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        if (hideHudForMinecraftMenu) {
            cancelHudPointerInteractionsOnly();
            return;
        }
        ensureLayoutLoaded(client);
        syncHudLayoutToViewport(client);
        if (sectionsPanelOpen) {
            fitSectionsPanelToContent(client);
        }
        updateFromSidebarScoreboard(client);
        boolean hideForMinecraftMenu = hideHudForMinecraftMenu;
        if (!hideForMinecraftMenu) {
            if (panelVisible) {
                int mMax = mainCardScrollMax(client, posY, posY + cardHeight, cardWidth);
                mainCardScrollPx = mMax <= 0 ? 0 : clamp(mainCardScrollPx, 0, mMax);
            }
            if (sectionsPanelOpen) {
                int sMax = computeSectionsPanelScrollMax(client, sectionsPosY, sectionsHeight);
                sectionsScrollPx = sMax <= 0 ? 0 : clamp(sectionsScrollPx, 0, sMax);
            }
            if (logsMenuEnabled) {
                int innerW = Math.max(0, logsWidth - 2 * CARD_INSET);
                int gap = 2;
                int maxLh = logsArchiveHScrollMax(innerW, logSessionArchive.size(), gap);
                logsScrollHorizPx = maxLh <= 0 ? 0 : clamp(logsScrollHorizPx, 0, maxLh);
            }
            if (enchantMenuEnabled) {
                int maxEnchantScroll = computeEnchantPanelScrollMax(client);
                enchantScrollPx = maxEnchantScroll <= 0 ? 0 : clamp(enchantScrollPx, 0, maxEnchantScroll);
            }
            if (enchantOptionsMenuEnabled) {
                int maxOptionsScroll = computeEnchantOptionsPanelScrollMax(client);
                enchantOptionsScrollPx = maxOptionsScroll <= 0 ? 0 : clamp(enchantOptionsScrollPx, 0, maxOptionsScroll);
            }
        }
        if (enchantOptionsMenuEnabled && !hideForMinecraftMenu && (client.currentScreen != null || editMode)) {
            handleEnchantOptionsPanelInteraction(client);
        }
        if (enchantMenuEnabled && !hideForMinecraftMenu && (client.currentScreen != null || editMode)) {
            handleEnchantPanelInteraction(client);
        }
        if (logsMenuEnabled && !hideForMinecraftMenu && (client.currentScreen != null || editMode)) {
            handleLogsPanelInteraction(client);
        }
        if (sectionsPanelOpen && !hideForMinecraftMenu) {
            handleSectionsPanelInteraction(client);
        }
        if (panelVisible && !hideForMinecraftMenu) {
            handleEditInteraction(client);
            handleMainCardFooterButtonsInteraction(client);
        }
        updateEventChain();
        updateDoubleEventTimers();
        if (hideForMinecraftMenu) {
            logsDragging = false;
            logsResizing = false;
            enchantDragging = false;
            enchantResizing = false;
            enchantOptionsDragging = false;
            enchantOptionsResizing = false;
            return;
        }
        boolean f1HideHud = shouldHideHudForF1Only(client);
        if (panelVisible && !f1HideHud) {
            drawCard(context, client, posX, posY);
        }
        if (sectionsPanelOpen) {
            drawSectionsPanel(context, client);
        }
        if (logsMenuEnabled) {
            drawLogsPanel(context, client);
        }
        if (enchantMenuEnabled) {
            drawEnchantPanel(context, client);
        }
        if (enchantOptionsMenuEnabled) {
            drawEnchantOptionsPanel(context, client);
        }
    }

    public static void toggleSectionsPanel() {
        sectionsPanelOpen = !sectionsPanelOpen;
        if (!sectionsPanelOpen) {
            sectionsScrollPx = 0;
            captureBindingIndex = KEY_CAPTURE_NONE;
            sectionsDragging = false;
            sectionsResizing = false;
            MinecraftClient c = MinecraftClient.getInstance();
            if (c != null) {
                saveLayout(c);
            }
        }
    }

    public static void closeSectionsPanelIfOpen() {
        if (sectionsPanelOpen) {
            toggleSectionsPanel();
        }
    }

    private static void fitSectionsPanelToContent(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        int minW = minSectionsPanelWidth(client);
        if (sectionsWidth < minW) {
            sectionsWidth = minW;
        }
        int sw = client.getWindow().getScaledWidth();
        sectionsPosX = clamp(sectionsPosX, 2, Math.max(2, sw - sectionsWidth - 2));
    }

    /** Activé par le bouton « Menu Minecraft (quitter, options…) » sur {@link FeatherPauseScreen}. */
    public static void setHideHudForMinecraftMenu(boolean hide) {
        if (hideHudForMinecraftMenu == hide) {
            return;
        }
        hideHudForMinecraftMenu = hide;
        if (hide) {
            sectionsPanelOpenBeforeMinecraftMenu = sectionsPanelOpen;
            savedSectionsPanelForMinecraftMenu = true;
            cancelHudPointerInteractionsOnly();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && editMode) {
                editMode = false;
                saveLayout(client);
            }
        } else {
            restoreModPanelsAfterMinecraftMenu(MinecraftClient.getInstance());
        }
    }

    /** Écran ouvert via le bouton « Menu Minecraft (quitter, options…) ». */
    public static boolean isMinecraftGameMenuScreenOpen(MinecraftClient client) {
        return client != null && client.currentScreen instanceof FeatherGameMenuScreen;
    }

    /**
     * Réaffiche tous les panneaux quand on revient au menu Feather (bouton visible) ou en jeu ;
     * reste masqué sur le menu Minecraft et ses sous-écrans (Options, etc.).
     */
    private static void syncModHudVisibilityAfterPauseMenus(MinecraftClient client) {
        if (client == null || !hideHudForMinecraftMenu) {
            return;
        }
        Screen screen = client.currentScreen;
        if (screen instanceof FeatherGameMenuScreen) {
            return;
        }
        if (screen == null || screen instanceof FeatherPauseScreen) {
            onGameMenuClosed(client);
        }
    }

    private static void cancelHudPointerInteractionsOnly() {
        dragging = false;
        resizing = false;
        hudSectionDragIndex = -1;
        hudFloatResizeIndex = -1;
        sectionsDragging = false;
        sectionsResizing = false;
        logsDragging = false;
        logsResizing = false;
        enchantDragging = false;
        enchantResizing = false;
        enchantOptionsDragging = false;
        enchantOptionsResizing = false;
        enchantMouseDownLast = false;
        enchantOptionsMouseDownLast = false;
        sectionsMouseDownLast = false;
        mouseDownLastFrame = false;
        mainCardFooterBtnMouseDownLast = false;
        captureBindingIndex = KEY_CAPTURE_NONE;
    }

    private static void restoreModPanelsAfterMinecraftMenu(MinecraftClient client) {
        if (savedSectionsPanelForMinecraftMenu) {
            sectionsPanelOpen = sectionsPanelOpenBeforeMinecraftMenu;
            savedSectionsPanelForMinecraftMenu = false;
        }
    }

    public static void onGameMenuClosed(MinecraftClient client) {
        hideHudForMinecraftMenu = false;
        restoreModPanelsAfterMinecraftMenu(client);
    }

    /**
     * Largeur minimale du panneau paramètres : titre, lignes sections (cases, libellés,
     * sous-ligne Prestige « ↳ » + clear/pause si Prestige activé, sous-lignes Reward / AFK si activés) et bloc « Touches » jusqu’au bord droit des cases de raccourcis.
     */
    private static int minSectionsPanelWidth(MinecraftClient client) {
        var tr = client.textRenderer;
        int minW = CARD_TEXT_X + tr.getWidth(Text.translatable("feather_world_menu.submenu_title")) + CARD_INSET;
        int sectionLeft = CARD_INSET + 8 + 4;
        int rewardResetBtnW = sectionsRewardResetBtnW(client);
        int rewardClearHourlyBtnW = sectionsRewardClearHourlyBtnW(client);
        int rewardClearTenMinBtnW = sectionsRewardClearTenMinBtnW(client);
        for (int i = 0; i < SECTIONS_SECTION_ROWS; i++) {
            int lw = tr.getWidth(Text.translatable(SECTION_PANEL_ROW_KEYS[i]));
            int rowW = sectionLeft + lw + CARD_INSET;
            minW = Math.max(minW, rowW);
        }
        int branchW = tr.getWidth(Text.literal(SECTIONS_REWARD_MONEY_BRANCH));
        int cacherPrefixW = tr.getWidth(Text.literal(SECTIONS_REWARD_CACHER_PREFIX));
        int clearPrefixW = tr.getWidth(Text.literal(SECTIONS_REWARD_CLEAR_PREFIX));
        int moneyBtnW = sectionsMoneyBtnW(client);
        int cacherMoneyRowW = CARD_INSET + branchW + 2 + cacherPrefixW + SECTIONS_REWARD_CLEAR_PREFIX_GAP
                + rewardClearTenMinBtnW + SECTIONS_REWARD_RESET_BTN_GAP
                + rewardClearHourlyBtnW + 4 + moneyBtnW + CARD_INSET;
        int prestigeSubRowW = CARD_INSET + branchW + 2
                + prestigeSubRowClearBtnW(client) + SECTIONS_PRESTIGE_BTN_GAP
                + prestigeSubRowPauseBtnW(client) + CARD_INSET;
        int clearSubRowW = CARD_INSET + branchW + 2 + clearPrefixW + SECTIONS_REWARD_CLEAR_PREFIX_GAP
                + rewardClearTenMinBtnW + SECTIONS_REWARD_RESET_BTN_GAP
                + rewardClearHourlyBtnW + SECTIONS_REWARD_RESET_BTN_GAP
                + rewardResetBtnW + CARD_INSET;
        int enchantOptionsSubRowW = CARD_INSET + branchW + 2 + sectionsEnchantOptionsBtnW(client) + CARD_INSET;
        minW = Math.max(minW, cacherMoneyRowW);
        minW = Math.max(minW, prestigeSubRowW);
        minW = Math.max(minW, clearSubRowW);
        minW = Math.max(minW, enchantOptionsSubRowW);
        int informationsRowW = CARD_INSET + 8 + 4 + tr.getWidth(Text.translatable("feather_world_menu.section.informations"))
                + SECTIONS_INFO_BADGE_GAP + sectionsInfoBadgeWidth(client) + CARD_INSET;
        minW = Math.max(minW, informationsRowW);
        int maxKeyLab = 0;
        for (String s : SECTIONS_KEY_BINDING_LABELS) {
            maxKeyLab = Math.max(maxKeyLab, tr.getWidth(Text.literal(s)));
        }
        minW = Math.max(minW,
                CARD_TEXT_X + maxKeyLab + SECTIONS_KEY_LABEL_BTN_GAP + sectionsKeyBtnW(client) + CARD_INSET);
        return minW + SCROLLBAR_TRACK_W + SCROLLBAR_PAD;
    }

    /**
     * Largeur minimale de la carte HUD : pied (boutons hors / en édition) + voie scrollbar.
     * Hors édition : Editer au-dessus de Paramètres. En édition : Fermer/Save puis Paramètres en dessous.
     */
    private static int minMainCardWidth(MinecraftClient client) {
        var tr = client.textRenderer;
        int wParamsBtn = mainCardParametresButtonWidth(client);
        int wEditerBtn = mainCardEditerButtonWidth(client);
        int wFermerSaveBtn = mainCardFermerSaveButtonWidth(client);
        int wFooterRowNormal = Math.max(wEditerBtn, wParamsBtn);
        int wFooterRowEditTop = wFermerSaveBtn;
        int longestFoot = Math.max(wFooterRowNormal, Math.max(wFooterRowEditTop, wParamsBtn));
        int forFooter = (CARD_INSET - 1) + SCROLLBAR_TRACK_W + SCROLLBAR_PAD + Math.min(longestFoot, 120);
        int dragW = tr.getWidth(Text.literal(DRAG_RESIZE_HINT));
        int wFooterEditBlock = Math.max(wFooterRowEditTop, wParamsBtn);
        int forEditHint = CARD_INSET + wFooterEditBlock + dragW + RESIZE_HANDLE_INSET - 2;
        return Math.max(MIN_CARD_WIDTH, Math.max(forFooter, forEditHint));
    }

    private static void ensureLayoutLoaded(MinecraftClient client) {
        if (layoutLoaded) {
            return;
        }
        layoutLoaded = true;
        Path path = getLayoutPath(client);
        if (!Files.exists(path)) {
            sectionsWidth = minSectionsPanelWidth(client);
            logSessionArchive.clear();
            hudViewportBaseline(client);
            return;
        }
        Properties p = new Properties();
        try {
            p.load(Files.newBufferedReader(path, StandardCharsets.UTF_8));
            posX = Integer.parseInt(p.getProperty("x", String.valueOf(posX)));
            posY = Integer.parseInt(p.getProperty("y", String.valueOf(posY)));
            cardWidth = Integer.parseInt(p.getProperty("width", String.valueOf(cardWidth)));
            cardWidth = Math.max(minMainCardWidth(client), cardWidth);
            cardHeight = Integer.parseInt(p.getProperty("height", String.valueOf(cardHeight)));
            sectionsPosX = Integer.parseInt(p.getProperty("sections.x", String.valueOf(sectionsPosX)));
            sectionsPosY = Integer.parseInt(p.getProperty("sections.y", String.valueOf(sectionsPosY)));
            sectionsWidth = Integer.parseInt(p.getProperty("sections.width", String.valueOf(sectionsWidth)));
            sectionsHeight = Integer.parseInt(p.getProperty("sections.height", String.valueOf(sectionsHeight)));
            sectionsHeight = Math.max(MIN_SECTIONS_PANEL_H, sectionsHeight);
            logsPosX = Integer.parseInt(p.getProperty("logs.x", "72"));
            logsPosY = Integer.parseInt(p.getProperty("logs.y", "120"));
            logsWidth = Integer.parseInt(p.getProperty("logs.width", "480"));
            logsHeight = Integer.parseInt(p.getProperty("logs.height", "200"));
            logsWidth = Math.max(MIN_LOGS_PANEL_W, logsWidth);
            logsHeight = Math.max(MIN_LOGS_PANEL_H, logsHeight);
            enchantPosX = Integer.parseInt(p.getProperty("enchant.x", String.valueOf(enchantPosX)));
            enchantPosY = Integer.parseInt(p.getProperty("enchant.y", String.valueOf(enchantPosY)));
            enchantWidth = Integer.parseInt(p.getProperty("enchant.width", String.valueOf(enchantWidth)));
            enchantHeight = Integer.parseInt(p.getProperty("enchant.height", String.valueOf(enchantHeight)));
            enchantWidth = Math.max(minEnchantPanelWidth(client), enchantWidth);
            enchantHeight = Math.max(MIN_ENCHANT_PANEL_H, enchantHeight);
            enchantOptionsPosX = Integer.parseInt(p.getProperty("enchant.options.x", String.valueOf(enchantOptionsPosX)));
            enchantOptionsPosY = Integer.parseInt(p.getProperty("enchant.options.y", String.valueOf(enchantOptionsPosY)));
            enchantOptionsWidth = Integer.parseInt(p.getProperty("enchant.options.width", String.valueOf(enchantOptionsWidth)));
            enchantOptionsHeight = Integer.parseInt(p.getProperty("enchant.options.height", String.valueOf(enchantOptionsHeight)));
            enchantOptionsWidth = Math.max(MIN_ENCHANT_OPTIONS_PANEL_W, enchantOptionsWidth);
            enchantOptionsHeight = Math.max(MIN_ENCHANT_OPTIONS_PANEL_H, enchantOptionsHeight);
            loadEnchantOptionStates(p);
            enchantShowCumulativeSums = Boolean.parseBoolean(p.getProperty("enchant.show_cumulative_sums", "true"));
            enchantPanelSortByCumuls = Boolean.parseBoolean(p.getProperty("enchant.panel_sort_by_cumuls", "false"));
            showHudSession = Boolean.parseBoolean(p.getProperty("section.session", "true"));
            showHudPrestige = Boolean.parseBoolean(p.getProperty("section.prestige", "false"));
            showHudAfk = Boolean.parseBoolean(p.getProperty("section.afk", "false"));
            showHudReward = Boolean.parseBoolean(p.getProperty("section.reward", "true"));
            hideRewardTenMin = Boolean.parseBoolean(p.getProperty("section.reward.hide_10min", "false"));
            hideRewardHourly = Boolean.parseBoolean(p.getProperty("section.reward.hide_hour", "false"));
            hideAfkTenMin = Boolean.parseBoolean(p.getProperty("section.afk.hide_10min", "false"));
            hideAfkHourly = Boolean.parseBoolean(p.getProperty("section.afk.hide_hour", "false"));
            hideRewardMoneyRow = Boolean.parseBoolean(p.getProperty("section.reward.hide_money_row", "false"));
            hideAfkMoneyRow = Boolean.parseBoolean(p.getProperty("section.afk.hide_money_row", "false"));
            showHudFarm = Boolean.parseBoolean(p.getProperty("section.farm", "false"));
            showHudDouble = Boolean.parseBoolean(p.getProperty("section.double", "false"));
            showHudPotions = Boolean.parseBoolean(p.getProperty("section.potions", "false"));
            rewardDisplayZone = RewardDisplayZone.fromConfig(p.getProperty("section.reward_zone"));
            afkDisplayZone = RewardDisplayZone.fromConfig(p.getProperty("section.afk_zone"));
            autoFishLogTriggerEnabled = Boolean.parseBoolean(p.getProperty("feature.autofish_log_trigger", "false"));
            informationsRowEnabled = Boolean.parseBoolean(p.getProperty("feature.informations_row", "false"));
            panelVisible = Boolean.parseBoolean(p.getProperty("layout.panel_visible", "true"));
            enchantMenuEnabled = Boolean.parseBoolean(p.getProperty("layout.enchant_menu", "false"));
            enchantOptionsMenuEnabled = Boolean.parseBoolean(p.getProperty("layout.enchant_options_menu", "false"));
            logsMenuEnabled = Boolean.parseBoolean(p.getProperty("layout.logs_menu", "false"));
            FeatherWorldMenuClient.setToggleHudKeyCode(Integer.parseInt(
                    p.getProperty("key.edit", String.valueOf(FeatherWorldMenuClient.getToggleHudKeyCode()))));
            FeatherWorldMenuClient.setToggleVisibleKeyCode(Integer.parseInt(
                    p.getProperty("key.hide", String.valueOf(FeatherWorldMenuClient.getToggleVisibleKeyCode()))));
            FeatherWorldMenuClient.setToggleSectionsKeyCode(Integer.parseInt(
                    p.getProperty("key.params", String.valueOf(FeatherWorldMenuClient.getToggleSectionsKeyCode()))));
            FeatherWorldMenuClient.setCloseSectionsKeyCode(Integer.parseInt(
                    p.getProperty("key.params_close", String.valueOf(FeatherWorldMenuClient.getCloseSectionsKeyCode()))));
            FeatherWorldMenuClient.setPrestigePauseKeyCode(Integer.parseInt(
                    p.getProperty("key.prestige_pause", String.valueOf(FeatherWorldMenuClient.getPrestigePauseKeyCode()))));
            for (int i = 0; i < HUD_SECTION_COUNT; i++) {
                hudSectionFloating[i] = Boolean.parseBoolean(p.getProperty("section." + i + ".floating", "false"));
                hudSectionFloatX[i] = Integer.parseInt(p.getProperty("section." + i + ".fx", "48"));
                hudSectionFloatY[i] = Integer.parseInt(p.getProperty("section." + i + ".fy", String.valueOf(32 + i * 24)));
                hudSectionFloatW[i] = Integer.parseInt(p.getProperty("section." + i + ".fw", "0"));
                hudSectionFloatPadBottom[i] = Integer.parseInt(p.getProperty("section." + i + ".fpad", "0"));
            }
            for (int i = 0; i < HUD_SECTION_COUNT; i++) {
                if (hudSectionFloating[i] && hudSectionFloatW[i] < MIN_CARD_WIDTH) {
                    hudSectionFloatW[i] = cardWidth;
                }
                hudSectionFloatPadBottom[i] = clamp(hudSectionFloatPadBottom[i], MIN_FLOAT_SECTION_PAD_BOTTOM, MAX_FLOAT_SECTION_PAD_BOTTOM);
            }
            sectionsWidth = Math.max(minSectionsPanelWidth(client), sectionsWidth);
            reloadLogSessionArchiveFrom(p);
            loadPotionSlotsFrom(p);
            loadPrestigeStateFrom(p);
            reapplyAfkLoggingOnNextWorldEntry = Boolean.parseBoolean(p.getProperty("session.reapply_afk", "false"));
            prestigeAutoResumeOnAfkReconnect = Boolean.parseBoolean(p.getProperty("prestige.resume_on_afk_reconnect", "false"));
        } catch (Exception ignored) {
        }
        hudViewportBaseline(client);
    }

    /** Après chargement du fichier : pas de mise à l’échelle au premier rendu. */
    private static void hudViewportBaseline(MinecraftClient client) {
        if (client.getWindow() == null) {
            return;
        }
        lastHudScaledW = client.getWindow().getScaledWidth();
        lastHudScaledH = client.getWindow().getScaledHeight();
    }

    /**
     * Si la résolution / facteur d’échelle GUI change, reproportionne carte, panneau paramètres et sections flottantes.
     */
    private static void syncHudLayoutToViewport(MinecraftClient client) {
        if (client.getWindow() == null) {
            return;
        }
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        if (lastHudScaledW <= 0 || lastHudScaledH <= 0) {
            lastHudScaledW = sw;
            lastHudScaledH = sh;
            return;
        }
        if (sw == lastHudScaledW && sh == lastHudScaledH) {
            return;
        }
        double rx = (double) sw / lastHudScaledW;
        double ry = (double) sh / lastHudScaledH;
        int minCardW = minMainCardWidth(client);
        cardWidth = clamp((int) Math.round(cardWidth * rx), minCardW, Math.max(minCardW, sw - 4));
        cardHeight = clamp((int) Math.round(cardHeight * ry), MIN_CARD_HEIGHT, Math.max(MIN_CARD_HEIGHT, sh - 4));
        int minSecW = minSectionsPanelWidth(client);
        sectionsWidth = Math.max(minSecW, clamp((int) Math.round(sectionsWidth * rx), minSecW, Math.max(minSecW, sw - 4)));
        sectionsHeight = Math.max(MIN_SECTIONS_PANEL_H, clamp((int) Math.round(sectionsHeight * ry), MIN_SECTIONS_PANEL_H, Math.max(MIN_SECTIONS_PANEL_H, sh - 4)));
        posX = clamp((int) Math.round(posX * rx), 2, Math.max(2, sw - cardWidth - 2));
        posY = clamp((int) Math.round(posY * ry), 2, Math.max(2, sh - cardHeight - 2));
        sectionsPosX = clamp((int) Math.round(sectionsPosX * rx), 2, Math.max(2, sw - sectionsWidth - 2));
        sectionsPosY = clamp((int) Math.round(sectionsPosY * ry), 2, Math.max(2, sh - sectionsHeight - 2));
        logsWidth = Math.max(MIN_LOGS_PANEL_W, clamp((int) Math.round(logsWidth * rx), MIN_LOGS_PANEL_W, Math.max(MIN_LOGS_PANEL_W, sw - 4)));
        logsHeight = Math.max(MIN_LOGS_PANEL_H, clamp((int) Math.round(logsHeight * ry), MIN_LOGS_PANEL_H, Math.max(MIN_LOGS_PANEL_H, sh - 4)));
        logsPosX = clamp((int) Math.round(logsPosX * rx), 2, Math.max(2, sw - logsWidth - 2));
        logsPosY = clamp((int) Math.round(logsPosY * ry), 2, Math.max(2, sh - logsHeight - 2));
        int minEnchantW = minEnchantPanelWidth(client);
        enchantWidth = Math.max(minEnchantW, clamp((int) Math.round(enchantWidth * rx), minEnchantW, Math.max(minEnchantW, sw - 4)));
        enchantHeight = Math.max(MIN_ENCHANT_PANEL_H, clamp((int) Math.round(enchantHeight * ry), MIN_ENCHANT_PANEL_H, Math.max(MIN_ENCHANT_PANEL_H, sh - 4)));
        enchantPosX = clamp((int) Math.round(enchantPosX * rx), 2, Math.max(2, sw - enchantWidth - 2));
        enchantPosY = clamp((int) Math.round(enchantPosY * ry), 2, Math.max(2, sh - enchantHeight - 2));
        enchantOptionsWidth = Math.max(MIN_ENCHANT_OPTIONS_PANEL_W,
                clamp((int) Math.round(enchantOptionsWidth * rx), MIN_ENCHANT_OPTIONS_PANEL_W, Math.max(MIN_ENCHANT_OPTIONS_PANEL_W, sw - 4)));
        enchantOptionsHeight = Math.max(MIN_ENCHANT_OPTIONS_PANEL_H,
                clamp((int) Math.round(enchantOptionsHeight * ry), MIN_ENCHANT_OPTIONS_PANEL_H, Math.max(MIN_ENCHANT_OPTIONS_PANEL_H, sh - 4)));
        enchantOptionsPosX = clamp((int) Math.round(enchantOptionsPosX * rx), 2, Math.max(2, sw - enchantOptionsWidth - 2));
        enchantOptionsPosY = clamp((int) Math.round(enchantOptionsPosY * ry), 2, Math.max(2, sh - enchantOptionsHeight - 2));
        for (int i = 0; i < HUD_SECTION_COUNT; i++) {
            if (hudSectionFloatW[i] >= MIN_CARD_WIDTH) {
                hudSectionFloatW[i] = clamp((int) Math.round(hudSectionFloatW[i] * rx), MIN_CARD_WIDTH, Math.max(MIN_CARD_WIDTH, sw - 4));
            }
            hudSectionFloatPadBottom[i] = clamp((int) Math.round(hudSectionFloatPadBottom[i] * ry),
                    MIN_FLOAT_SECTION_PAD_BOTTOM, MAX_FLOAT_SECTION_PAD_BOTTOM);
            hudSectionFloatX[i] = (int) Math.round(hudSectionFloatX[i] * rx);
            hudSectionFloatY[i] = (int) Math.round(hudSectionFloatY[i] * ry);
            if (hudSectionFloating[i]) {
                clampHudFloatSection(client, i);
            }
        }
        lastHudScaledW = sw;
        lastHudScaledH = sh;
    }

    private static void saveLayout(MinecraftClient client) {
        Path path = getLayoutPath(client);
        try {
            Files.createDirectories(path.getParent());
            Properties p = new Properties();
            p.setProperty("x", String.valueOf(posX));
            p.setProperty("y", String.valueOf(posY));
            p.setProperty("width", String.valueOf(cardWidth));
            p.setProperty("height", String.valueOf(cardHeight));
            p.setProperty("sections.x", String.valueOf(sectionsPosX));
            p.setProperty("sections.y", String.valueOf(sectionsPosY));
            p.setProperty("sections.width", String.valueOf(sectionsWidth));
            p.setProperty("sections.height", String.valueOf(sectionsHeight));
            p.setProperty("logs.x", String.valueOf(logsPosX));
            p.setProperty("logs.y", String.valueOf(logsPosY));
            p.setProperty("logs.width", String.valueOf(logsWidth));
            p.setProperty("logs.height", String.valueOf(logsHeight));
            p.setProperty("enchant.x", String.valueOf(enchantPosX));
            p.setProperty("enchant.y", String.valueOf(enchantPosY));
            p.setProperty("enchant.width", String.valueOf(enchantWidth));
            p.setProperty("enchant.height", String.valueOf(enchantHeight));
            p.setProperty("enchant.options.x", String.valueOf(enchantOptionsPosX));
            p.setProperty("enchant.options.y", String.valueOf(enchantOptionsPosY));
            p.setProperty("enchant.options.width", String.valueOf(enchantOptionsWidth));
            p.setProperty("enchant.options.height", String.valueOf(enchantOptionsHeight));
            saveEnchantOptionStates(p);
            savePotionSlotsTo(p);
            p.setProperty("enchant.show_cumulative_sums", String.valueOf(enchantShowCumulativeSums));
            p.setProperty("enchant.panel_sort_by_cumuls", String.valueOf(enchantPanelSortByCumuls));
            p.setProperty("section.session", String.valueOf(showHudSession));
            p.setProperty("section.prestige", String.valueOf(showHudPrestige));
            p.setProperty("section.afk", String.valueOf(showHudAfk));
            p.setProperty("section.reward", String.valueOf(showHudReward));
            p.setProperty("section.reward.hide_10min", String.valueOf(hideRewardTenMin));
            p.setProperty("section.reward.hide_hour", String.valueOf(hideRewardHourly));
            p.setProperty("section.afk.hide_10min", String.valueOf(hideAfkTenMin));
            p.setProperty("section.afk.hide_hour", String.valueOf(hideAfkHourly));
            p.setProperty("section.reward.hide_money_row", String.valueOf(hideRewardMoneyRow));
            p.setProperty("section.afk.hide_money_row", String.valueOf(hideAfkMoneyRow));
            p.setProperty("section.farm", String.valueOf(showHudFarm));
            p.setProperty("section.double", String.valueOf(showHudDouble));
            p.setProperty("section.potions", String.valueOf(showHudPotions));
            p.setProperty("section.reward_zone", rewardDisplayZone.name());
            p.setProperty("section.afk_zone", afkDisplayZone.name());
            p.setProperty("feature.autofish_log_trigger", String.valueOf(autoFishLogTriggerEnabled));
            p.setProperty("feature.informations_row", String.valueOf(informationsRowEnabled));
            p.setProperty("layout.panel_visible", String.valueOf(panelVisible));
            p.setProperty("layout.enchant_menu", String.valueOf(enchantMenuEnabled));
            p.setProperty("layout.enchant_options_menu", String.valueOf(enchantOptionsMenuEnabled));
            p.setProperty("layout.logs_menu", String.valueOf(logsMenuEnabled));
            p.setProperty("key.edit", String.valueOf(FeatherWorldMenuClient.getToggleHudKeyCode()));
            p.setProperty("key.hide", String.valueOf(FeatherWorldMenuClient.getToggleVisibleKeyCode()));
            p.setProperty("key.params", String.valueOf(FeatherWorldMenuClient.getToggleSectionsKeyCode()));
            p.setProperty("key.params_close", String.valueOf(FeatherWorldMenuClient.getCloseSectionsKeyCode()));
            p.setProperty("key.prestige_pause", String.valueOf(FeatherWorldMenuClient.getPrestigePauseKeyCode()));
            for (int i = 0; i < HUD_SECTION_COUNT; i++) {
                p.setProperty("section." + i + ".floating", String.valueOf(hudSectionFloating[i]));
                p.setProperty("section." + i + ".fx", String.valueOf(hudSectionFloatX[i]));
                p.setProperty("section." + i + ".fy", String.valueOf(hudSectionFloatY[i]));
                p.setProperty("section." + i + ".fw", String.valueOf(hudSectionFloatW[i]));
                p.setProperty("section." + i + ".fpad", String.valueOf(hudSectionFloatPadBottom[i]));
            }
            persistLogSessionArchiveTo(p);
            savePrestigeStateTo(p);
            p.setProperty("session.reapply_afk", String.valueOf(reapplyAfkLoggingOnNextWorldEntry));
            p.setProperty("prestige.resume_on_afk_reconnect", String.valueOf(prestigeAutoResumeOnAfkReconnect));
            p.store(Files.newBufferedWriter(path, StandardCharsets.UTF_8), "Feather World Menu layout");
        } catch (IOException ignored) {
        }
    }

    private static Path getLayoutPath(MinecraftClient client) {
        return client.runDirectory.toPath().resolve("config").resolve("feather-world-menu.properties");
    }

    private static boolean mouseOutsideMainCard(int mouseX, int mouseY, int margin) {
        return mouseX < posX - margin || mouseX > posX + cardWidth + margin
                || mouseY < posY - margin || mouseY > posY + cardHeight + margin;
    }

    private static int hudSectionFloatWidth(int idx) {
        return hudSectionFloatW[idx] >= MIN_CARD_WIDTH ? hudSectionFloatW[idx] : cardWidth;
    }

    private static int getHudSectionHeight(MinecraftClient client, int idx, int layoutWidth) {
        if (!hudSectionShown(idx)) {
            return 0;
        }
        boolean narrowStats = layoutWidth < NARROW_CARD_BREAKPOINT;
        boolean rewardStacked = layoutWidth < REWARD_STACKED_BREAKPOINT;
        int rewardTitleH = 10;
        int rewardPad = 1;
        int fh = client.textRenderer.fontHeight;
        return switch (idx) {
            case 0 -> SESSION_TEXT_DY + SESSION_LINE_AFTER_HEADER;
            case 1 -> {
                int pLineStep = fh + 2;
                int prestigeBoxH = rewardTitleH + rewardPad + 2 * pLineStep + rewardPad;
                yield prestigeBoxH + SECTION_BLOCK_TAIL_PAD;
            }
            case 2 -> {
                List<String> keys = rewardKeysForHudDisplayList(rewardDisplayZone, false);
                int linesPerEntry = rewardEntryLineCount(hideRewardTenMin, hideRewardHourly);
                int rewardRowStep = rewardStacked ? rewardStackedEntryStepPx(client, linesPerEntry) : rewardWideEntryStepPx(client);
                int farmTimerLineH = fh + 2;
                int rewardBoxH = rewardTitleH + farmTimerLineH + rewardPad + keys.size() * rewardRowStep + rewardPad;
                yield rewardBoxH + SECTION_BLOCK_TAIL_PAD;
            }
            case 3 -> {
                List<String> keys = rewardKeysForHudDisplayList(afkDisplayZone, true);
                int linesPerEntry = rewardEntryLineCount(hideAfkTenMin, hideAfkHourly);
                int afkRowStep = rewardStacked ? rewardStackedEntryStepPx(client, linesPerEntry) : rewardWideEntryStepPx(client);
                int idleExtra = !afkRewardLoggingActive ? (fh + 2) : 0;
                int farmTimerLineH = fh + 2;
                int afkBoxH = rewardTitleH + farmTimerLineH + rewardPad + idleExtra + keys.size() * afkRowStep + rewardPad;
                yield afkBoxH + SECTION_BLOCK_TAIL_PAD;
            }
            case 4 -> 26 + SECTION_BLOCK_TAIL_PAD;
            case 5 -> (narrowStats ? 44 : 26) + SECTION_BLOCK_TAIL_PAD;
            case 6 -> potionSectionDockedHeight(client);
            default -> 0;
        };
    }

    private static int getHudSectionHeight(MinecraftClient client, int idx) {
        return getHudSectionHeight(client, idx, cardWidth);
    }

    private static int getHudFloatPanelHeight(MinecraftClient client, int idx) {
        return getHudSectionHeight(client, idx, hudSectionFloatWidth(idx)) + hudSectionFloatPadBottom[idx];
    }

    /** Offset depuis le haut de la carte jusqu’au bas (exclu) du bloc de sections dockées. */
    private static int computeMainCardDockedBottomOffsetFromTop(MinecraftClient client, int layoutWidth) {
        int docLine = CARD_BODY_TOP_INSET;
        boolean anyDocked = false;
        for (int idx = 0; idx < HUD_SECTION_COUNT; idx++) {
            if (!hudSectionShown(idx) || hudSectionFloating[idx]) {
                continue;
            }
            if (anyDocked) {
                docLine += CARD_SECTION_STACK_GAP;
            }
            anyDocked = true;
            docLine += getHudSectionHeight(client, idx, layoutWidth);
        }
        return docLine;
    }

    private static int mainCardScrollViewportTop(int y) {
        return y + CARD_BODY_TOP_INSET;
    }

    private static final int MAIN_CARD_FOOTER_BTN_PAD_X = 6;
    private static final int MAIN_CARD_FOOTER_BTN_PAD_Y = 2;
    private static final int MAIN_CARD_FOOTER_BTN_GAP = 4;

    private static int mainCardEditerButtonWidth(MinecraftClient client) {
        int tw = client.textRenderer.getWidth(Text.literal("Editer"));
        return tw + 2 * MAIN_CARD_FOOTER_BTN_PAD_X;
    }

    private static int mainCardParametresButtonWidth(MinecraftClient client) {
        int tw = client.textRenderer.getWidth(Text.literal("Parametres"));
        return tw + 2 * MAIN_CARD_FOOTER_BTN_PAD_X;
    }

    private static int mainCardFermerSaveButtonWidth(MinecraftClient client) {
        int tw = client.textRenderer.getWidth(Text.literal("Fermer/Save"));
        return tw + 2 * MAIN_CARD_FOOTER_BTN_PAD_X;
    }

    private static int mainCardParametresButtonHeight(MinecraftClient client) {
        return Math.max(client.textRenderer.fontHeight + 2 * MAIN_CARD_FOOTER_BTN_PAD_Y, SECTIONS_KEY_ROW_H - 1);
    }

    private static boolean mainCardParametresButtonHit(MinecraftClient client, int mouseX, int mouseY) {
        if (!mainCardSettingsBtnRectValid || shouldHideHudForF1Only(client)) {
            return false;
        }
        return mouseX >= mainCardSettingsBtnLeft && mouseX < mainCardSettingsBtnRight
                && mouseY >= mainCardSettingsBtnTop && mouseY < mainCardSettingsBtnBottom;
    }

    private static boolean mainCardEditerButtonHit(MinecraftClient client, int mouseX, int mouseY) {
        if (!mainCardEditerBtnRectValid || shouldHideHudForF1Only(client)) {
            return false;
        }
        return mouseX >= mainCardEditerBtnLeft && mouseX < mainCardEditerBtnRight
                && mouseY >= mainCardEditerBtnTop && mouseY < mainCardEditerBtnBottom;
    }

    private static boolean mainCardFermerSaveButtonHit(MinecraftClient client, int mouseX, int mouseY) {
        if (!mainCardFermerSaveBtnRectValid || !editMode || shouldHideHudForF1Only(client)) {
            return false;
        }
        return mouseX >= mainCardFermerSaveBtnLeft && mouseX < mainCardFermerSaveBtnRight
                && mouseY >= mainCardFermerSaveBtnTop && mouseY < mainCardFermerSaveBtnBottom;
    }

    private static void handleMainCardFooterButtonsInteraction(MinecraftClient client) {
        if (client.getWindow() == null) {
            mainCardFooterBtnMouseDownLast = false;
            return;
        }
        if (!panelVisible || hideHudForMinecraftMenu || shouldHideHudForF1Only(client)) {
            mainCardFooterBtnMouseDownLast = false;
            return;
        }
        long window = client.getWindow().getHandle();
        boolean mouseDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        double scaledMouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double scaledMouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        int mouseX = (int) scaledMouseX;
        int mouseY = (int) scaledMouseY;

        if (sectionsDragging || sectionsResizing || logsDragging || logsResizing
                || enchantDragging || enchantResizing || enchantOptionsDragging || enchantOptionsResizing
                || resizing || dragging || hudSectionDragIndex >= 0 || hudFloatResizeIndex >= 0) {
            mainCardFooterBtnMouseDownLast = mouseDown;
            return;
        }
        if (enchantOptionsMenuEnabled && mouseInsideEnchantOptionsPanelBounds(mouseX, mouseY)) {
            mainCardFooterBtnMouseDownLast = mouseDown;
            return;
        }
        if (enchantMenuEnabled && mouseInsideEnchantPanelBounds(mouseX, mouseY)) {
            mainCardFooterBtnMouseDownLast = mouseDown;
            return;
        }
        if (logsMenuEnabled && mouseInsideLogsPanelBounds(mouseX, mouseY)) {
            mainCardFooterBtnMouseDownLast = mouseDown;
            return;
        }
        if (mouseOverHudSectionBlockingOverlays(mouseX, mouseY)) {
            mainCardFooterBtnMouseDownLast = mouseDown;
            return;
        }

        if (!mainCardFooterBtnMouseDownLast && mouseDown) {
            if (mainCardEditerButtonHit(client, mouseX, mouseY)) {
                toggleEditor(client);
            } else if (mainCardFermerSaveButtonHit(client, mouseX, mouseY)) {
                toggleEditor(client);
            } else if (mainCardParametresButtonHit(client, mouseX, mouseY)) {
                toggleSectionsPanel();
            }
        }
        mainCardFooterBtnMouseDownLast = mouseDown;
    }

    private static int mainCardFooterTextHeightPx(MinecraftClient client) {
        int btnH = mainCardParametresButtonHeight(client);
        return btnH + MAIN_CARD_FOOTER_BTN_GAP + btnH;
    }

    private static int mainCardFooterReservePx(MinecraftClient client) {
        return 8 + mainCardFooterTextHeightPx(client);
    }

    private static void drawMainCardFooterWrapped(DrawContext context, MinecraftClient client, int x, int footY) {
        mainCardEditerBtnRectValid = false;
        mainCardFermerSaveBtnRectValid = false;
        int fy = footY;
        int btnH = mainCardParametresButtonHeight(client);
        if (!editMode) {
            int edW = mainCardEditerButtonWidth(client);
            int edLeft = x + CARD_INSET;
            int edTop = fy;
            context.fill(edLeft, edTop, edLeft + edW, edTop + btnH, 0x55394979);
            context.drawBorder(edLeft, edTop, edW, btnH, 0xFF7986CB);
            String edLabel = "Editer";
            int edTw = client.textRenderer.getWidth(Text.literal(edLabel));
            int edTx = edLeft + (edW - edTw) / 2;
            int edTy = edTop + (btnH - client.textRenderer.fontHeight) / 2 + 1;
            context.drawText(client.textRenderer, Text.literal(edLabel), edTx, edTy, 0xFFE8EAF6, false);
            mainCardEditerBtnLeft = edLeft;
            mainCardEditerBtnTop = edTop;
            mainCardEditerBtnRight = edLeft + edW;
            mainCardEditerBtnBottom = edTop + btnH;
            mainCardEditerBtnRectValid = true;

            int paramW = mainCardParametresButtonWidth(client);
            int paramLeft = edLeft;
            int paramTop = edTop + btnH + MAIN_CARD_FOOTER_BTN_GAP;
            context.fill(paramLeft, paramTop, paramLeft + paramW, paramTop + btnH, 0x55394979);
            context.drawBorder(paramLeft, paramTop, paramW, btnH, 0xFF7986CB);
            String pLabel = "Parametres";
            int pTw = client.textRenderer.getWidth(Text.literal(pLabel));
            int pTx = paramLeft + (paramW - pTw) / 2;
            int pTy = paramTop + (btnH - client.textRenderer.fontHeight) / 2 + 1;
            context.drawText(client.textRenderer, Text.literal(pLabel), pTx, pTy, 0xFFE8EAF6, false);
            mainCardSettingsBtnLeft = paramLeft;
            mainCardSettingsBtnTop = paramTop;
            mainCardSettingsBtnRight = paramLeft + paramW;
            mainCardSettingsBtnBottom = paramTop + btnH;
        } else {
            int fsW = mainCardFermerSaveButtonWidth(client);
            int fsLeft = x + CARD_INSET;
            int fsTop = fy;
            context.fill(fsLeft, fsTop, fsLeft + fsW, fsTop + btnH, 0x55394979);
            context.drawBorder(fsLeft, fsTop, fsW, btnH, 0xFF7986CB);
            String fsLabel = "Fermer/Save";
            int fsTw = client.textRenderer.getWidth(Text.literal(fsLabel));
            int fsTx = fsLeft + (fsW - fsTw) / 2;
            int fsTy = fsTop + (btnH - client.textRenderer.fontHeight) / 2 + 1;
            context.drawText(client.textRenderer, Text.literal(fsLabel), fsTx, fsTy, 0xFFE8EAF6, false);
            mainCardFermerSaveBtnLeft = fsLeft;
            mainCardFermerSaveBtnTop = fsTop;
            mainCardFermerSaveBtnRight = fsLeft + fsW;
            mainCardFermerSaveBtnBottom = fsTop + btnH;
            mainCardFermerSaveBtnRectValid = true;

            int paramW = mainCardParametresButtonWidth(client);
            int paramLeft = fsLeft;
            int paramTop = fsTop + btnH + MAIN_CARD_FOOTER_BTN_GAP;
            context.fill(paramLeft, paramTop, paramLeft + paramW, paramTop + btnH, 0x55394979);
            context.drawBorder(paramLeft, paramTop, paramW, btnH, 0xFF7986CB);
            String pLabel = "Parametres";
            int pTw = client.textRenderer.getWidth(Text.literal(pLabel));
            int pTx = paramLeft + (paramW - pTw) / 2;
            int pTy = paramTop + (btnH - client.textRenderer.fontHeight) / 2 + 1;
            context.drawText(client.textRenderer, Text.literal(pLabel), pTx, pTy, 0xFFE8EAF6, false);
            mainCardSettingsBtnLeft = paramLeft;
            mainCardSettingsBtnTop = paramTop;
            mainCardSettingsBtnRight = paramLeft + paramW;
            mainCardSettingsBtnBottom = paramTop + btnH;
        }
        mainCardSettingsBtnRectValid = true;
    }

    private static int mainCardScrollMax(MinecraftClient client, int y, int bottom, int layoutWidth) {
        int vpTop = mainCardScrollViewportTop(y);
        int vpBottom = bottom - mainCardFooterReservePx(client);
        int vpH = Math.max(0, vpBottom - vpTop);
        int docBot = y + computeMainCardDockedBottomOffsetFromTop(client, layoutWidth);
        int contentH = Math.max(0, docBot - vpTop);
        return Math.max(0, contentH - vpH);
    }

    /** Ordonnée du haut de la ligne « Informations » (après les lignes sections, avant « Touches »). */
    private static int sectionsInformationsRowTop(int panelContentRowTop) {
        return panelContentRowTop + sectionRowsTotal() * SECTIONS_ROW_H + SECTIONS_INFORMATIONS_HEADER_GAP;
    }

    /** Hauteur du bloc défilable (lignes sections + touches), sous le titre fixe. */
    private static int computeSectionsPanelScrollableContentHeight(MinecraftClient client) {
        int h = sectionRowsTotal() * SECTIONS_ROW_H;
        h += SECTIONS_INFORMATIONS_HEADER_GAP + SECTIONS_ROW_H;
        h += SECTIONS_KEY_HEADER_GAP + SECTIONS_KEY_ROW_H;
        int keyRows = SECTIONS_KEY_BINDING_LABELS.length;
        h += keyRows * SECTIONS_KEY_ROW_H;
        return h;
    }

    private static int computeSectionsPanelScrollMax(MinecraftClient client, int sy, int sh) {
        int innerTop = sy + SECTIONS_HEADER_H + 2;
        int innerBottom = sy + sh - SECTIONS_PANEL_FOOTER_H;
        int vpH = Math.max(0, innerBottom - innerTop);
        int contentH = computeSectionsPanelScrollableContentHeight(client);
        return Math.max(0, contentH - vpH);
    }

    private static int computeSimplePanelScrollMax(int panelHeight, int headerHeight, int footerHeight, int contentHeight) {
        int innerTop = headerHeight + 2;
        int innerBottom = panelHeight - footerHeight;
        int vpH = Math.max(0, innerBottom - innerTop);
        return Math.max(0, contentHeight - vpH);
    }

    private static int computeEnchantPanelScrollMax(MinecraftClient client) {
        return computeSimplePanelScrollMax(enchantHeight, ENCHANT_HEADER_H, ENCHANT_PANEL_FOOTER_H,
                computeEnchantPanelContentHeight(client));
    }

    private static int computeEnchantOptionsPanelScrollMax(MinecraftClient client) {
        return computeSimplePanelScrollMax(enchantOptionsHeight, ENCHANT_OPTIONS_HEADER_H, ENCHANT_OPTIONS_PANEL_FOOTER_H,
                computeEnchantOptionsContentHeight(client));
    }

    private static void drawVerticalScrollbar(DrawContext context, int x0, int y0, int y1, int scrollPx, int maxScroll,
            int argbTrack, int argbThumb) {
        if (maxScroll <= 0 || y1 <= y0 + 4) {
            return;
        }
        int trackH = y1 - y0;
        context.fill(x0, y0, x0 + SCROLLBAR_TRACK_W, y1, argbTrack);
        float span = (float) maxScroll;
        float frac = Math.min(1f, (float) trackH / (trackH + span));
        int thumbH = Math.max(6, Math.round(trackH * frac));
        float pos = span > 0 ? scrollPx / span : 0f;
        pos = Math.max(0f, Math.min(1f, pos));
        int thumbY = y0 + Math.round((trackH - thumbH) * pos);
        context.fill(x0, thumbY, x0 + SCROLLBAR_TRACK_W, thumbY + thumbH, argbThumb);
    }

    private static void drawHorizontalScrollbar(DrawContext context, int x0, int x1, int y0, int scrollPx, int maxScroll, int argbTrack, int argbThumb) {
        if (maxScroll <= 0 || x1 <= x0 + 4) {
            return;
        }
        int trackW = x1 - x0;
        context.fill(x0, y0, x1, y0 + SCROLLBAR_TRACK_W, argbTrack);
        float span = (float) maxScroll;
        float frac = Math.min(1f, (float) trackW / (trackW + span));
        int thumbW = Math.max(6, Math.round(trackW * frac));
        float pos = span > 0 ? scrollPx / span : 0f;
        pos = Math.max(0f, Math.min(1f, pos));
        int thumbX = x0 + Math.round((trackW - thumbW) * pos);
        context.fill(thumbX, y0, thumbX + thumbW, y0 + SCROLLBAR_TRACK_W, argbThumb);
    }

    private static int logsArchiveColW(int innerW, int n, int gap) {
        if (n <= 0) {
            return LOGS_ARCHIVE_MIN_COL_W;
        }
        int shared = innerW - gap * (n - 1);
        return Math.max(LOGS_ARCHIVE_MIN_COL_W, shared / n);
    }

    private static int logsArchiveContentWidth(int n, int gap, int colW) {
        if (n <= 0) {
            return 0;
        }
        return n * colW + (n - 1) * gap;
    }

    private static int logsArchiveHScrollMax(int innerW, int n, int gap) {
        if (n <= 0 || innerW <= 0) {
            return 0;
        }
        int colW = logsArchiveColW(innerW, n, gap);
        int cw = logsArchiveContentWidth(n, gap, colW);
        return Math.max(0, cw - innerW);
    }

    private static void clampHudFloatSection(MinecraftClient client, int idx) {
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int fw = hudSectionDisplayWidth(client, idx);
        int fh = getHudFloatPanelHeight(client, idx);
        hudSectionFloatX[idx] = clamp(hudSectionFloatX[idx], 2, Math.max(2, sw - fw - 2));
        hudSectionFloatY[idx] = clamp(hudSectionFloatY[idx], 2, Math.max(2, sh - fh - 2));
    }

    private static boolean hudSectionShown(int idx) {
        return switch (idx) {
            case 0 -> showHudSession;
            case 1 -> showHudPrestige;
            case 2 -> showHudReward && !afkRewardLoggingActive;
            case 3 -> showHudAfk;
            case 4 -> showHudFarm;
            case 5 -> showHudDouble;
            case 6 -> showHudPotions;
            default -> false;
        };
    }

    private static void handleEditInteraction(MinecraftClient client) {
        if (!editMode || client.getWindow() == null || client.currentScreen == null) {
            dragging = false;
            resizing = false;
            hudSectionDragIndex = -1;
            hudFloatResizeIndex = -1;
            return;
        }
        long window = client.getWindow().getHandle();
        boolean mouseDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        double scaledMouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double scaledMouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        int mouseX = (int) scaledMouseX;
        int mouseY = (int) scaledMouseY;

        if (hudFloatResizeIndex >= 0 && !mouseDown) {
            saveLayout(client);
            hudFloatResizeIndex = -1;
        }
        if (hudFloatResizeIndex >= 0 && mouseDown) {
            int ri = hudFloatResizeIndex;
            int nw = Math.max(MIN_CARD_WIDTH, hudFloatResizeStartW + mouseX - hudFloatResizeStartMouseX);
            int nnat = getHudSectionHeight(client, ri, nw);
            int targetTotal = hudFloatResizeStartTotalH + mouseY - hudFloatResizeStartMouseY;
            int npad = clamp(targetTotal - nnat, MIN_FLOAT_SECTION_PAD_BOTTOM, MAX_FLOAT_SECTION_PAD_BOTTOM);
            hudSectionFloatW[ri] = nw;
            hudSectionFloatPadBottom[ri] = npad;
            clampHudFloatSection(client, ri);
            mouseDownLastFrame = mouseDown;
            return;
        }

        if (hudSectionDragIndex >= 0 && !mouseDown) {
            saveLayout(client);
            hudSectionDragIndex = -1;
        }
        if (hudSectionDragIndex >= 0 && mouseDown) {
            int di = hudSectionDragIndex;
            if (hudSectionDragStartedDocked && !hudSectionFloating[di] && mouseOutsideMainCard(mouseX, mouseY, 2)
                    && !(logsMenuEnabled && mouseInsideLogsPanelBounds(mouseX, mouseY))) {
                hudSectionFloating[di] = true;
                if (hudSectionFloatW[di] < MIN_CARD_WIDTH) {
                    hudSectionFloatW[di] = cardWidth;
                }
                hudSectionFloatX[di] = mouseX - hudSectionDragOffX;
                hudSectionFloatY[di] = mouseY - hudSectionDragOffY;
                clampHudFloatSection(client, di);
            }
            if (hudSectionFloating[di]) {
                hudSectionFloatX[di] = mouseX - hudSectionDragOffX;
                hudSectionFloatY[di] = mouseY - hudSectionDragOffY;
                clampHudFloatSection(client, di);
            }
            mouseDownLastFrame = mouseDown;
            return;
        }

        boolean inHeader = mouseX >= posX && mouseX <= posX + cardWidth && mouseY >= posY && mouseY <= posY + SESSION_HEADER_H;
        boolean inResizeHandle = mainHudCardResizeHandleHit(mouseX, mouseY);
        boolean blockMainChrome = mouseBlocksMainHudCardChrome(client, mouseX, mouseY);

        if (!mouseDownLastFrame && mouseDown && inResizeHandle && !blockMainChrome) {
            resizing = true;
            dragging = false;
            sectionsResizing = false;
            sectionsDragging = false;
            logsResizing = false;
            logsDragging = false;
            resizeStartMouseX = mouseX;
            resizeStartMouseY = mouseY;
            resizeStartWidth = cardWidth;
            resizeStartHeight = cardHeight;
        } else if (!mouseDownLastFrame && mouseDown) {
            int floatClose = editMode ? pickFloatingSectionCloseButton(client, mouseX, mouseY) : -1;
            if (floatClose >= 0) {
                hudSectionFloating[floatClose] = false;
                clampHudFloatSection(client, floatClose);
                saveLayout(client);
            } else {
                int floatRes = pickFloatingSectionResize(client, mouseX, mouseY);
                if (floatRes >= 0) {
                    dragging = false;
                    resizing = false;
                    logsResizing = false;
                    logsDragging = false;
                    hudFloatResizeIndex = floatRes;
                    hudFloatResizeStartMouseX = mouseX;
                    hudFloatResizeStartMouseY = mouseY;
                    hudFloatResizeStartW = hudSectionDisplayWidth(client, floatRes);
                    hudFloatResizeStartTotalH = getHudFloatPanelHeight(client, floatRes);
                } else {
                    int picked = pickHudSectionForEditDrag(client, mouseX, mouseY);
                    if (picked >= 0) {
                        dragging = false;
                        resizing = false;
                        logsResizing = false;
                        logsDragging = false;
                        hudSectionDragIndex = picked;
                        hudSectionDragStartedDocked = !hudSectionFloating[picked];
                        hudSectionDragOffX = mouseX - (hudSectionFloating[picked] ? hudSectionFloatX[picked] : posX);
                        hudSectionDragOffY = mouseY - (hudSectionFloating[picked] ? hudSectionFloatY[picked] : hudDockHitTop[picked]);
                    } else if (inHeader && !blockMainChrome) {
                        dragging = true;
                        resizing = false;
                        logsResizing = false;
                        logsDragging = false;
                        dragOffsetX = mouseX - posX;
                        dragOffsetY = mouseY - posY;
                    }
                }
            }
        } else if (!mouseDown) {
            if (dragging || resizing) {
                saveLayout(client);
            }
            dragging = false;
            resizing = false;
        }
        mouseDownLastFrame = mouseDown;

        if (dragging) {
            posX = clamp(mouseX - dragOffsetX, 2, Math.max(2, client.getWindow().getScaledWidth() - cardWidth - 2));
            posY = clamp(mouseY - dragOffsetY, 2, Math.max(2, client.getWindow().getScaledHeight() - cardHeight - 2));
        }
        if (resizing) {
            int dx = mouseX - resizeStartMouseX;
            int dy = mouseY - resizeStartMouseY;
            cardWidth = Math.max(minMainCardWidth(client), resizeStartWidth + dx);
            cardHeight = Math.max(MIN_CARD_HEIGHT, resizeStartHeight + dy);
        }
    }

    private static boolean mouseInFloatingResizeHandle(MinecraftClient client, int i, int mouseX, int mouseY) {
        int fw = hudSectionDisplayWidth(client, i);
        int fh = getHudFloatPanelHeight(client, i);
        int fx = hudSectionFloatX[i];
        int fy = hudSectionFloatY[i];
        int hLeft = fx + fw - RESIZE_HANDLE_INSET;
        int hTop = fy + fh - RESIZE_HANDLE_INSET;
        return mouseX >= hLeft && mouseX <= fx + fw && mouseY >= hTop && mouseY <= fy + fh;
    }

    private static int floatingSectionCloseLeft(MinecraftClient client, int idx) {
        return hudSectionFloatX[idx] + hudSectionDisplayWidth(client, idx) - FLOAT_CLOSE_BTN_INSET - FLOAT_CLOSE_BTN_SIZE;
    }

    private static int floatingSectionCloseTop(int idx) {
        return hudSectionFloatY[idx] + FLOAT_CLOSE_BTN_INSET;
    }

    private static boolean mouseInFloatingCloseButton(MinecraftClient client, int i, int mouseX, int mouseY) {
        int left = floatingSectionCloseLeft(client, i);
        int top = floatingSectionCloseTop(i);
        return mouseX >= left && mouseX < left + FLOAT_CLOSE_BTN_SIZE
                && mouseY >= top && mouseY < top + FLOAT_CLOSE_BTN_SIZE;
    }

    private static int pickFloatingSectionCloseButton(MinecraftClient client, int mouseX, int mouseY) {
        if (mouseOverHudSectionBlockingOverlays(mouseX, mouseY)) {
            return -1;
        }
        for (int i = HUD_SECTION_COUNT - 1; i >= 0; i--) {
            if (!hudSectionShown(i) || !hudSectionFloating[i]) {
                continue;
            }
            if (mouseInFloatingCloseButton(client, i, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    private static int pickFloatingSectionResize(MinecraftClient client, int mouseX, int mouseY) {
        if (mouseOverHudSectionBlockingOverlays(mouseX, mouseY)) {
            return -1;
        }
        for (int i = HUD_SECTION_COUNT - 1; i >= 0; i--) {
            if (!hudSectionShown(i) || !hudSectionFloating[i]) {
                continue;
            }
            if (mouseInFloatingCloseButton(client, i, mouseX, mouseY)) {
                continue;
            }
            if (mouseInFloatingResizeHandle(client, i, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /** Priorité : sections flottantes (dessus), puis ancrées, puis aucune (la barre d’entête déplace la carte). */
    private static int pickHudSectionForEditDrag(MinecraftClient client, int mouseX, int mouseY) {
        if (mouseOverHudSectionBlockingOverlays(mouseX, mouseY)) {
            return -1;
        }
        for (int i = HUD_SECTION_COUNT - 1; i >= 0; i--) {
            if (!hudSectionShown(i) || !hudSectionFloating[i]) {
                continue;
            }
            if (mouseInFloatingCloseButton(client, i, mouseX, mouseY)) {
                continue;
            }
            if (mouseInFloatingResizeHandle(client, i, mouseX, mouseY)) {
                continue;
            }
            int fw = hudSectionDisplayWidth(client, i);
            int fph = getHudFloatPanelHeight(client, i);
            if (fph <= 0) {
                continue;
            }
            if (mouseX >= hudSectionFloatX[i] && mouseX <= hudSectionFloatX[i] + fw
                    && mouseY >= hudSectionFloatY[i] && mouseY <= hudSectionFloatY[i] + fph) {
                return i;
            }
        }
        if (!mouseInsideMainCardScrollViewport(client, mouseX, mouseY)) {
            return -1;
        }
        for (int i = HUD_SECTION_COUNT - 1; i >= 0; i--) {
            if (!hudSectionShown(i) || hudSectionFloating[i] || hudDockHitTop[i] < 0) {
                continue;
            }
            if (mouseX >= posX && mouseX <= posX + cardWidth
                    && mouseY >= hudDockHitTop[i] && mouseY <= hudDockHitBottom[i]) {
                return i;
            }
        }
        return -1;
    }

    /** Poignée redimensionnement carte HUD (mode édition) : même rectangle que le carré dessiné. */
    private static boolean mainHudCardResizeHandleHit(int mouseX, int mouseY) {
        if (!panelVisible || !editMode) {
            return false;
        }
        int right = posX + cardWidth;
        int bottom = posY + cardHeight;
        int hLeft = right - RESIZE_HANDLE_INSET;
        int hTop = bottom - RESIZE_HANDLE_INSET;
        return mouseX >= hLeft && mouseX < hLeft + RESIZE_HANDLE_SIZE
                && mouseY >= hTop && mouseY < hTop + RESIZE_HANDLE_SIZE;
    }

    private static void closeEnchantOptionsMenu() {
        enchantOptionsMenuEnabled = false;
        enchantOptionsDragging = false;
        enchantOptionsResizing = false;
        enchantOptionsMouseDownLast = false;
        enchantOptionsScrollPx = 0;
    }

    private static void closeEnchantMenu() {
        enchantMenuEnabled = false;
        enchantDragging = false;
        enchantResizing = false;
        enchantMouseDownLast = false;
        enchantScrollPx = 0;
        closeEnchantOptionsMenu();
    }

    private static boolean enchantResizeHandleHit(int mouseX, int mouseY) {
        if (!enchantMenuEnabled) {
            return false;
        }
        int er = enchantPosX + enchantWidth;
        int eb = enchantPosY + enchantHeight;
        int shLeft = er - ENCHANT_RESIZE_VIS_INSET - ENCHANT_RESIZE_VIS_SIZE;
        int shTop = eb - ENCHANT_RESIZE_VIS_INSET - ENCHANT_RESIZE_VIS_SIZE;
        return mouseX >= shLeft && mouseX < shLeft + ENCHANT_RESIZE_VIS_SIZE
                && mouseY >= shTop && mouseY < shTop + ENCHANT_RESIZE_VIS_SIZE;
    }

    private static boolean enchantOptionsResizeHandleHit(int mouseX, int mouseY) {
        if (!enchantOptionsMenuEnabled) {
            return false;
        }
        int or = enchantOptionsPosX + enchantOptionsWidth;
        int ob = enchantOptionsPosY + enchantOptionsHeight;
        int shLeft = or - ENCHANT_OPTIONS_RESIZE_VIS_INSET - ENCHANT_OPTIONS_RESIZE_VIS_SIZE;
        int shTop = ob - ENCHANT_OPTIONS_RESIZE_VIS_INSET - ENCHANT_OPTIONS_RESIZE_VIS_SIZE;
        return mouseX >= shLeft && mouseX < shLeft + ENCHANT_OPTIONS_RESIZE_VIS_SIZE
                && mouseY >= shTop && mouseY < shTop + ENCHANT_OPTIONS_RESIZE_VIS_SIZE;
    }

    private static boolean logsResizeHandleHit(int mouseX, int mouseY) {
        if (!logsMenuEnabled) {
            return false;
        }
        int lr = logsPosX + logsWidth;
        int lb = logsPosY + logsHeight;
        int shLeft = lr - LOGS_RESIZE_VIS_INSET - LOGS_RESIZE_VIS_SIZE;
        int shTop = lb - LOGS_RESIZE_VIS_INSET - LOGS_RESIZE_VIS_SIZE;
        return mouseX >= shLeft && mouseX < shLeft + LOGS_RESIZE_VIS_SIZE
                && mouseY >= shTop && mouseY < shTop + LOGS_RESIZE_VIS_SIZE;
    }

    private static boolean sectionsResizeHandleHit(int mouseX, int mouseY) {
        if (!sectionsPanelOpen) {
            return false;
        }
        int sr = sectionsPosX + sectionsWidth;
        int sb = sectionsPosY + sectionsHeight;
        int shLeft = sr - SECTIONS_RESIZE_VIS_INSET - SECTIONS_RESIZE_VIS_SIZE;
        int shTop = sb - SECTIONS_RESIZE_VIS_INSET - SECTIONS_RESIZE_VIS_SIZE;
        return mouseX >= shLeft && mouseX < shLeft + SECTIONS_RESIZE_VIS_SIZE
                && mouseY >= shTop && mouseY < shTop + SECTIONS_RESIZE_VIS_SIZE;
    }

    private static void handleEnchantOptionsPanelInteraction(MinecraftClient client) {
        if ((!enchantOptionsMenuEnabled && !enchantOptionsDragging && !enchantOptionsResizing) || client.getWindow() == null) {
            enchantOptionsMouseDownLast = false;
            return;
        }
        if (!enchantOptionsMenuEnabled) {
            enchantOptionsDragging = false;
            enchantOptionsResizing = false;
            enchantOptionsMouseDownLast = false;
            return;
        }
        if (client.currentScreen == null && !editMode) {
            enchantOptionsDragging = false;
            enchantOptionsResizing = false;
            enchantOptionsMouseDownLast = false;
            return;
        }
        long window = client.getWindow().getHandle();
        boolean mouseDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        double scaledMouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double scaledMouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        int mouseX = (int) scaledMouseX;
        int mouseY = (int) scaledMouseY;

        if (resizing) {
            enchantOptionsResizing = false;
            enchantOptionsDragging = false;
            enchantOptionsMouseDownLast = mouseDown;
            return;
        }

        int ox = enchantOptionsPosX;
        int oy = enchantOptionsPosY;
        int ow = enchantOptionsWidth;
        int oh = enchantOptionsHeight;
        int or = ox + ow;
        int adjY = mouseY + enchantOptionsScrollPx;
        int innerTop = oy + ENCHANT_OPTIONS_HEADER_H + 2;
        int scrollTrackLeft = or - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
        int buttonsTop = enchantOptionsEnchantButtonsTop(client, innerTop);
        boolean inScrollableViewport = mouseInsideEnchantOptionsScrollableViewport(mouseX, mouseY, ox, oy, ow, oh);

        boolean inDragBar = mouseX >= ox && mouseX <= or && mouseY >= oy && mouseY < oy + ENCHANT_OPTIONS_HEADER_H;
        boolean inResizeHandle = enchantOptionsResizeHandleHit(mouseX, mouseY) && !mainHudCardResizeHandleHit(mouseX, mouseY);
        int footerTop = oy + oh - ENCHANT_OPTIONS_PANEL_FOOTER_H;
        boolean inFooterStrip = mouseY >= footerTop;

        if (!enchantOptionsMouseDownLast && mouseDown) {
            if (inResizeHandle) {
                enchantOptionsResizing = true;
                enchantOptionsDragging = false;
                resizing = false;
                dragging = false;
                sectionsResizing = false;
                sectionsDragging = false;
                logsResizing = false;
                logsDragging = false;
                enchantResizing = false;
                enchantDragging = false;
                enchantOptionsResizeStartMouseX = mouseX;
                enchantOptionsResizeStartMouseY = mouseY;
                enchantOptionsResizeStartW = enchantOptionsWidth;
                enchantOptionsResizeStartH = enchantOptionsHeight;
            } else if (!inFooterStrip) {
                boolean hitChip = false;
                List<EnchantOptionButtonLayout> chips = isEnchantSectionVisibleForCurrentWorld()
                        ? layoutEnchantLacOptionButtons(client, ox + CARD_INSET, buttonsTop, scrollTrackLeft - 4)
                        : List.of();
                EnchantOptionSet optionSet = currentEnchantOptionSet();
                boolean[] optionEnabled = optionSet != null ? optionSet.enabled() : new boolean[0];
                for (EnchantOptionButtonLayout chip : chips) {
                    if (inScrollableViewport
                            && mouseX >= chip.x() && mouseX < chip.right()
                            && adjY >= chip.y() && adjY < chip.bottom()) {
                        optionEnabled[chip.optionIndex()] = !optionEnabled[chip.optionIndex()];
                        saveLayout(client);
                        hitChip = true;
                        break;
                    }
                }
                if (hitChip) {
                    enchantOptionsDragging = false;
                    enchantOptionsResizing = false;
                } else if (inDragBar) {
                    enchantOptionsDragging = true;
                    enchantOptionsResizing = false;
                    enchantOptionsDragOffX = mouseX - ox;
                    enchantOptionsDragOffY = mouseY - oy;
                }
            }
        } else if (!mouseDown) {
            if (enchantOptionsDragging || enchantOptionsResizing) {
                saveLayout(client);
            }
            enchantOptionsDragging = false;
            enchantOptionsResizing = false;
        }
        enchantOptionsMouseDownLast = mouseDown;

        if (enchantOptionsDragging) {
            enchantOptionsPosX = clamp(mouseX - enchantOptionsDragOffX, 2, Math.max(2, client.getWindow().getScaledWidth() - enchantOptionsWidth - 2));
            enchantOptionsPosY = clamp(mouseY - enchantOptionsDragOffY, 2, Math.max(2, client.getWindow().getScaledHeight() - enchantOptionsHeight - 2));
        }
        if (enchantOptionsResizing) {
            int dx = mouseX - enchantOptionsResizeStartMouseX;
            int dy = mouseY - enchantOptionsResizeStartMouseY;
            enchantOptionsWidth = Math.max(MIN_ENCHANT_OPTIONS_PANEL_W, enchantOptionsResizeStartW + dx);
            enchantOptionsHeight = Math.max(MIN_ENCHANT_OPTIONS_PANEL_H, enchantOptionsResizeStartH + dy);
        }
    }

    private static void handleEnchantPanelInteraction(MinecraftClient client) {
        if ((!enchantMenuEnabled && !enchantDragging && !enchantResizing) || client.getWindow() == null) {
            enchantMouseDownLast = false;
            return;
        }
        if (!enchantMenuEnabled) {
            enchantDragging = false;
            enchantResizing = false;
            enchantMouseDownLast = false;
            return;
        }
        if (client.currentScreen == null && !editMode) {
            enchantDragging = false;
            enchantResizing = false;
            enchantMouseDownLast = false;
            return;
        }
        long window = client.getWindow().getHandle();
        boolean mouseDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        double scaledMouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double scaledMouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        int mouseX = (int) scaledMouseX;
        int mouseY = (int) scaledMouseY;

        if (enchantOptionsMenuEnabled && (enchantOptionsDragging || enchantOptionsResizing || mouseInsideEnchantOptionsPanelBounds(mouseX, mouseY))) {
            enchantMouseDownLast = mouseDown;
            return;
        }
        if (resizing) {
            enchantResizing = false;
            enchantDragging = false;
            enchantMouseDownLast = mouseDown;
            return;
        }

        int ex = enchantPosX;
        int ey = enchantPosY;
        int ew = enchantWidth;
        int eh = enchantHeight;
        int eb = ey + eh;
        int er = ex + ew;
        int innerTop = ey + ENCHANT_HEADER_H + 2;
        int innerLeft = ex + CARD_INSET;
        int scrollTrackLeft = enchantPanelScrollTrackLeft(ex, ew);

        boolean inDragBar = mouseX >= ex && mouseX <= er && mouseY >= ey && mouseY < ey + ENCHANT_HEADER_H;
        boolean inResizeHandle = enchantResizeHandleHit(mouseX, mouseY) && !mainHudCardResizeHandleHit(mouseX, mouseY);
        int footerTop = ey + eh - ENCHANT_PANEL_FOOTER_H;
        boolean inFooterStrip = mouseY >= footerTop;

        if (!enchantMouseDownLast && mouseDown) {
            if (inResizeHandle) {
                enchantResizing = true;
                enchantDragging = false;
                resizing = false;
                dragging = false;
                sectionsResizing = false;
                sectionsDragging = false;
                logsResizing = false;
                logsDragging = false;
                enchantResizeStartMouseX = mouseX;
                enchantResizeStartMouseY = mouseY;
                enchantResizeStartW = enchantWidth;
                enchantResizeStartH = enchantHeight;
            } else if (!inFooterStrip) {
                boolean hitTopBtn = false;
                if (isEnchantSectionVisibleForCurrentWorld()) {
                    EnchantOptionsTopBtnRowLayout topRow = layoutEnchantTopButtonRow(client, innerLeft, innerTop, scrollTrackLeft);
                    if (mouseX >= topRow.cumuls().left() && mouseX < topRow.cumuls().right()
                            && mouseY >= topRow.cumuls().top() && mouseY < topRow.cumuls().bottom()) {
                        enchantShowCumulativeSums = !enchantShowCumulativeSums;
                        saveLayout(client);
                        hitTopBtn = true;
                    } else if (mouseX >= topRow.topSort().left() && mouseX < topRow.topSort().right()
                            && mouseY >= topRow.topSort().top() && mouseY < topRow.topSort().bottom()) {
                        enchantPanelSortByCumuls = !enchantPanelSortByCumuls;
                        saveLayout(client);
                        hitTopBtn = true;
                    } else if (mouseX >= topRow.reset().left() && mouseX < topRow.reset().right()
                            && mouseY >= topRow.reset().top() && mouseY < topRow.reset().bottom()) {
                        resetEnchantPanelCounters();
                        hitTopBtn = true;
                    }
                }
                if (hitTopBtn) {
                    enchantDragging = false;
                    enchantResizing = false;
                } else if (inDragBar) {
                    enchantDragging = true;
                    enchantResizing = false;
                    enchantDragOffX = mouseX - ex;
                    enchantDragOffY = mouseY - ey;
                }
            }
        } else if (!mouseDown) {
            if (enchantDragging || enchantResizing) {
                saveLayout(client);
            }
            enchantDragging = false;
            enchantResizing = false;
        }
        enchantMouseDownLast = mouseDown;

        if (enchantDragging) {
            enchantPosX = clamp(mouseX - enchantDragOffX, 2, Math.max(2, client.getWindow().getScaledWidth() - enchantWidth - 2));
            enchantPosY = clamp(mouseY - enchantDragOffY, 2, Math.max(2, client.getWindow().getScaledHeight() - enchantHeight - 2));
        }
        if (enchantResizing) {
            int dx = mouseX - enchantResizeStartMouseX;
            int dy = mouseY - enchantResizeStartMouseY;
            enchantWidth = Math.max(minEnchantPanelWidth(client), enchantResizeStartW + dx);
            enchantHeight = Math.max(MIN_ENCHANT_PANEL_H, enchantResizeStartH + dy);
        }
    }

    /** Panneau Logs : déplacement depuis la barre du haut ; redimensionnement coin bas-droit ; corps vide. */
    private static void handleLogsPanelInteraction(MinecraftClient client) {
        if ((!logsMenuEnabled && !logsDragging && !logsResizing) || client.getWindow() == null) {
            logsMouseDownLast = false;
            return;
        }
        if (!logsMenuEnabled) {
            logsDragging = false;
            logsResizing = false;
            logsMouseDownLast = false;
            return;
        }
        if (client.currentScreen == null && !editMode) {
            logsDragging = false;
            logsResizing = false;
            logsMouseDownLast = false;
            return;
        }
        long window = client.getWindow().getHandle();
        boolean mouseDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        double scaledMouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double scaledMouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        int mouseX = (int) scaledMouseX;
        int mouseY = (int) scaledMouseY;

        if (enchantOptionsMenuEnabled && (enchantOptionsDragging || enchantOptionsResizing || mouseInsideEnchantOptionsPanelBounds(mouseX, mouseY))) {
            logsMouseDownLast = mouseDown;
            return;
        }
        if (enchantMenuEnabled && (enchantDragging || enchantResizing || mouseInsideEnchantPanelBounds(mouseX, mouseY))) {
            logsMouseDownLast = mouseDown;
            return;
        }
        if (resizing) {
            logsResizing = false;
            logsDragging = false;
            logsMouseDownLast = mouseDown;
            return;
        }

        int lx = logsPosX;
        int ly = logsPosY;
        int lw = logsWidth;
        int lh = logsHeight;
        int lb = ly + lh;
        int lr = lx + lw;

        boolean inDragBar = mouseX >= lx && mouseX <= lr && mouseY >= ly && mouseY < ly + LOGS_HEADER_H;
        boolean inResizeHandle = logsResizeHandleHit(mouseX, mouseY) && !mainHudCardResizeHandleHit(mouseX, mouseY);
        int logsFooterTop = ly + lh - LOGS_PANEL_FOOTER_H;
        boolean inFooterStrip = mouseY >= logsFooterTop;

        if (!logsMouseDownLast && mouseDown) {
            if (inResizeHandle && !mainHudCardResizeHandleHit(mouseX, mouseY)) {
                logsResizing = true;
                logsDragging = false;
                resizing = false;
                dragging = false;
                sectionsResizing = false;
                sectionsDragging = false;
                logsResizeStartMouseX = mouseX;
                logsResizeStartMouseY = mouseY;
                logsResizeStartW = logsWidth;
                logsResizeStartH = logsHeight;
            } else if (!inFooterStrip && inDragBar) {
                logsDragging = true;
                logsResizing = false;
                logsDragOffX = mouseX - lx;
                logsDragOffY = mouseY - ly;
            }
        } else if (!mouseDown) {
            if (logsDragging || logsResizing) {
                saveLayout(client);
            }
            logsDragging = false;
            logsResizing = false;
        }
        logsMouseDownLast = mouseDown;

        if (logsDragging) {
            logsPosX = clamp(mouseX - logsDragOffX, 2, Math.max(2, client.getWindow().getScaledWidth() - logsWidth - 2));
            logsPosY = clamp(mouseY - logsDragOffY, 2, Math.max(2, client.getWindow().getScaledHeight() - logsHeight - 2));
        }
        if (logsResizing) {
            int dx = mouseX - logsResizeStartMouseX;
            int dy = mouseY - logsResizeStartMouseY;
            logsWidth = Math.max(MIN_LOGS_PANEL_W, logsResizeStartW + dx);
            logsHeight = Math.max(MIN_LOGS_PANEL_H, logsResizeStartH + dy);
        }
    }

    private static void drawLogsPanel(DrawContext context, MinecraftClient client) {
        int lx = logsPosX;
        int ly = logsPosY;
        int lw = logsWidth;
        int lh = logsHeight;
        int lr = lx + lw;
        int lb = ly + lh;

        context.fill(lx + 2, ly + 2, lr + 2, lb + 2, 0x77000000);
        context.fill(lx, ly, lr, lb, 0xDD263238);
        context.drawBorder(lx, ly, lw, lh, 0xFF80CBC4);
        context.drawBorder(lx + 1, ly + 1, lw - 2, lh - 2, 0x66400606);

        context.fill(lx, ly, lr, ly + LOGS_HEADER_H, 0x88212121);
        drawDragHandle(context, lx, ly, lw, 0xFF80CBC4);
        int logsTitleMax = Math.max(8, lw - 2 * CARD_INSET - 8);
        String logsTitleShown = client.textRenderer.trimToWidth("Logs", logsTitleMax);
        context.drawText(client.textRenderer, Text.literal(logsTitleShown), lx + CARD_INSET, ly + 4, 0xFFECEFF1, false);

        int innerTop = ly + LOGS_HEADER_H + 2;
        int innerBottom = lb - LOGS_PANEL_FOOTER_H;
        int innerL = lx + CARD_INSET;
        int innerR = lr - CARD_INSET;
        int innerW = innerR - innerL;
        int step = client.textRenderer.fontHeight;
        context.enableScissor(lx + 1, innerTop, lr - 1, innerBottom);
        try {
            String hintRaw = Text.translatable("feather_world_menu.logs.save_min_hint").getString();
            String hintShown = client.textRenderer.trimToWidth(hintRaw, Math.max(40, innerW));
            context.drawText(client.textRenderer, Text.literal(hintShown), innerL, innerTop + 1, 0xFFB0BEC5, false);
            int colsTop = innerTop + step + 4;
            int n = logSessionArchive.size();
            int gap = 2;
            if (n == 0) {
                drawLogArchiveColumns(context, client, innerL, colsTop, innerW, Math.max(0, innerBottom - colsTop), gap, 0);
            } else {
                int maxLh = logsArchiveHScrollMax(innerW, n, gap);
                int hBarReserve = maxLh > 0 ? SCROLLBAR_TRACK_W + SCROLLBAR_PAD : 0;
                int archiveBottom = innerBottom - hBarReserve;
                int archiveH = Math.max(0, archiveBottom - colsTop);
                context.enableScissor(lx + 1, colsTop, lr - 1, archiveBottom);
                try {
                    context.getMatrices().pushMatrix();
                    context.getMatrices().translate((float) -logsScrollHorizPx, 0f);
                    int colW = logsArchiveColW(innerW, n, gap);
                    drawLogArchiveColumns(context, client, innerL, colsTop, innerW, archiveH, gap, colW);
                    context.getMatrices().popMatrix();
                } finally {
                    context.disableScissor();
                }
                if (maxLh > 0) {
                    int barY = archiveBottom + SCROLLBAR_PAD;
                    drawHorizontalScrollbar(context, innerL, lr - 1, barY, logsScrollHorizPx, maxLh, 0x55303030, 0xFF80CBC4);
                }
            }
        } finally {
            context.disableScissor();
        }

        int shLeft = lr - LOGS_RESIZE_VIS_INSET - LOGS_RESIZE_VIS_SIZE;
        int shTop = lb - LOGS_RESIZE_VIS_INSET - LOGS_RESIZE_VIS_SIZE;
        context.fill(shLeft, shTop, shLeft + LOGS_RESIZE_VIS_SIZE, shTop + LOGS_RESIZE_VIS_SIZE, 0xFF4DB6AC);
        drawDragResizeHintLeftOfSquare(context, client, shLeft, shTop, LOGS_RESIZE_VIS_SIZE, lx + CARD_INSET, 0xFF90A4AE);
    }

    private static void drawEnchantPanel(DrawContext context, MinecraftClient client) {
        int ex = enchantPosX;
        int ey = enchantPosY;
        int ew = enchantWidth;
        int eh = enchantHeight;
        int er = ex + ew;
        int eb = ey + eh;

        context.fill(ex + 2, ey + 2, er + 2, eb + 2, 0x77000000);
        context.fill(ex, ey, er, eb, 0xDD4A148C);
        context.drawBorder(ex, ey, ew, eh, 0xFFCE93D8);
        context.drawBorder(ex + 1, ey + 1, ew - 2, eh - 2, 0x664A148C);

        context.fill(ex, ey, er, ey + ENCHANT_HEADER_H, 0x884A148C);
        drawDragHandle(context, ex, ey, ew, 0xFFCE93D8);
        int titleMax = Math.max(8, ew - 2 * CARD_INSET - 8);
        String titleShown = client.textRenderer.trimToWidth(Text.translatable("feather_world_menu.enchant.title").getString(), titleMax);
        context.drawText(client.textRenderer, Text.literal(titleShown), ex + CARD_INSET, ey + 4, 0xFFF3E5F5, false);

        int innerTop = ey + ENCHANT_HEADER_H + 2;
        int innerBottom = eb - ENCHANT_PANEL_FOOTER_H;
        int innerLeft = ex + CARD_INSET;
        int scrollTrackLeft = enchantPanelScrollTrackLeft(ex, ew);
        context.enableScissor(ex + 1, innerTop, scrollTrackLeft, innerBottom);
        drawEnchantTopButtonRow(context, client, innerLeft, innerTop, scrollTrackLeft);
        int contentTop = enchantPanelScrollContentTop(client, innerTop, innerLeft, scrollTrackLeft);
        try {
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(0f, (float) -enchantScrollPx);
            EnchantOptionSet optionSet = currentEnchantOptionSet();
            if (optionSet != null) {
                int titleY = contentTop + 1;
                int entriesTop = titleY + client.textRenderer.fontHeight + 4;
                int sectionTitleMax = Math.max(12, scrollTrackLeft - innerLeft - 4);
                String lacTitleShown = client.textRenderer.trimToWidth(optionSet.title(), sectionTitleMax);
                context.drawText(client.textRenderer, Text.literal(lacTitleShown), innerLeft, titleY, 0xFFCE93D8, false);
                for (EnchantOptionButtonLayout entry : layoutEnchantPanelEntries(client, innerLeft, entriesTop, scrollTrackLeft - 4)) {
                    drawEnchantPanelEntry(context, client, entry);
                }
            }
            context.getMatrices().popMatrix();
        } finally {
            context.disableScissor();
        }

        int shLeft = er - ENCHANT_RESIZE_VIS_INSET - ENCHANT_RESIZE_VIS_SIZE;
        int shTop = eb - ENCHANT_RESIZE_VIS_INSET - ENCHANT_RESIZE_VIS_SIZE;
        context.fill(shLeft, shTop, shLeft + ENCHANT_RESIZE_VIS_SIZE, shTop + ENCHANT_RESIZE_VIS_SIZE, 0xFFBA68C8);
        drawDragResizeHintLeftOfSquare(context, client, shLeft, shTop, ENCHANT_RESIZE_VIS_SIZE, ex + CARD_INSET, 0xFFCE93D8);
        drawVerticalScrollbar(context, scrollTrackLeft, contentTop, innerBottom, enchantScrollPx, computeEnchantPanelScrollMax(client), 0x5548486E, 0xFFCE93D8);
    }

    private static void drawEnchantOptionsPanel(DrawContext context, MinecraftClient client) {
        int ox = enchantOptionsPosX;
        int oy = enchantOptionsPosY;
        int ow = enchantOptionsWidth;
        int oh = enchantOptionsHeight;
        int or = ox + ow;
        int ob = oy + oh;

        context.fill(ox + 2, oy + 2, or + 2, ob + 2, 0x77000000);
        context.fill(ox, oy, or, ob, 0xDD283593);
        context.drawBorder(ox, oy, ow, oh, 0xFF90CAF9);
        context.drawBorder(ox + 1, oy + 1, ow - 2, oh - 2, 0x66283593);

        context.fill(ox, oy, or, oy + ENCHANT_OPTIONS_HEADER_H, 0x88283593);
        drawDragHandle(context, ox, oy, ow, 0xFF90CAF9);
        int titleMax = Math.max(8, ow - 2 * CARD_INSET - 8);
        String titleShown = client.textRenderer.trimToWidth(Text.translatable("feather_world_menu.enchant.options_title").getString(), titleMax);
        context.drawText(client.textRenderer, Text.literal(titleShown), ox + CARD_INSET, oy + 4, 0xFFE3F2FD, false);

        int innerTop = oy + ENCHANT_OPTIONS_HEADER_H + 2;
        int innerBottom = ob - ENCHANT_OPTIONS_PANEL_FOOTER_H;
        int innerLeft = ox + CARD_INSET;
        int scrollTrackLeft = or - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
        context.enableScissor(ox + 1, innerTop, scrollTrackLeft, innerBottom);
        try {
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(0f, (float) -enchantOptionsScrollPx);
            EnchantOptionSet optionSet = currentEnchantOptionSet();
            if (optionSet != null) {
                int titleY = enchantOptionsWorldTitleY(innerTop);
                int buttonsTop = enchantOptionsEnchantButtonsTop(client, innerTop);
                int sectionTitleMax = Math.max(12, scrollTrackLeft - innerLeft - 4);
                String lacTitleShown = client.textRenderer.trimToWidth(optionSet.title(), sectionTitleMax);
                context.drawText(client.textRenderer, Text.literal(lacTitleShown), innerLeft, titleY, 0xFFB3E5FC, false);
                for (EnchantOptionButtonLayout chip : layoutEnchantLacOptionButtons(client, innerLeft, buttonsTop, scrollTrackLeft - 4)) {
                    drawEnchantOptionButton(context, client, chip);
                }
            }
            context.getMatrices().popMatrix();
        } finally {
            context.disableScissor();
        }

        int shLeft = or - ENCHANT_OPTIONS_RESIZE_VIS_INSET - ENCHANT_OPTIONS_RESIZE_VIS_SIZE;
        int shTop = ob - ENCHANT_OPTIONS_RESIZE_VIS_INSET - ENCHANT_OPTIONS_RESIZE_VIS_SIZE;
        context.fill(shLeft, shTop, shLeft + ENCHANT_OPTIONS_RESIZE_VIS_SIZE, shTop + ENCHANT_OPTIONS_RESIZE_VIS_SIZE, 0xFF64B5F6);
        drawDragResizeHintLeftOfSquare(context, client, shLeft, shTop, ENCHANT_OPTIONS_RESIZE_VIS_SIZE, ox + CARD_INSET, 0xFF90CAF9);
        drawVerticalScrollbar(context, scrollTrackLeft, innerTop, innerBottom, enchantOptionsScrollPx, computeEnchantOptionsPanelScrollMax(client),
                0x5548486E, 0xFF90CAF9);
    }

    private static void onClientStopping(MinecraftClient client) {
        autoFishSecondUseDueAtMs = 0L;
        resetEnchantOptionLogSums();
        if (client != null) {
            onPlaySessionSuspend(client);
            if (client.player != null && client.world != null) {
                pushLogSessionArchive(client);
            }
        }
    }

    /** Déconnexion / transfert vers un autre serveur : pause prestige en premier, puis mémorise l’AFK. */
    private static void onPlayConnectionDisconnect(MinecraftClient client) {
        onPlaySessionSuspend(client);
    }

    /** Entrée sur un serveur (reconnexion, transfert Bungee, etc.). */
    private static void onPlayConnectionJoin(MinecraftClient client) {
        if (client == null) {
            return;
        }
        ensureLayoutLoaded(client);
        lastPlayNetworkHandler = client.getNetworkHandler();
        if (prestigeRunActive && (prestigeAutoResumeOnAfkReconnect || reapplyAfkLoggingOnNextWorldEntry) && !prestigePaused) {
            freezePrestigeOnSessionSuspend();
            persistAllMenuPositions(client);
        }
        scheduleAfkDetectionForWorldEntry();
        tryRestorePendingAfkState(client);
    }

    /** Planifie une détection AFK (JOIN serveur, même si le joueur est déjà chargé). */
    private static void scheduleAfkDetectionForWorldEntry() {
        afkWorldEntryDetectionPending = true;
    }

    /**
     * À chaque monde / serveur : relit la fin du log, puis surveille les nouvelles lignes AFK.
     */
    private static void beginAfkDetectionForWorldEntry(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            scheduleAfkDetectionForWorldEntry();
            return;
        }
        afkWorldEntryDetectionPending = false;
        afkWorldEntryProbeActive = true;
        afkWorldEntryProbeResolved = false;
        afkWorldEntryProbeTicks = 0;
        scanRecentLogTailForAfkState(client);
        seekLogReadToEnd(client);
        tryRestorePendingAfkState(client);
    }

    private static void seekLogReadToEnd(MinecraftClient client) {
        try {
            Path lp = resolveLatestLogPath(client);
            logReadOffsetPath = lp;
            logReadOffset = Files.exists(lp) ? Files.size(lp) : 0L;
        } catch (IOException e) {
            logReadOffsetPath = null;
            logReadOffset = 0L;
        }
    }

    /**
     * Dernières lignes du log : dernière bascule AFK ON/OFF (évite de relire tout l’historique).
     */
    private static void scanRecentLogTailForAfkState(MinecraftClient client) {
        if (client == null) {
            return;
        }
        Path logPath = resolveLatestLogPath(client);
        if (!Files.isReadable(logPath)) {
            return;
        }
        try {
            List<String> tailLines = readLastLogLines(logPath, AFK_WORLD_ENTRY_LOG_TAIL_MAX_LINES);
            int lastOnIdx = -1;
            int lastOffIdx = -1;
            for (int i = 0; i < tailLines.size(); i++) {
                String line = tailLines.get(i);
                String s = stripLogPrefix(line);
                if (s.isBlank()) {
                    s = stripForRecapFarm(line);
                }
                String t = s.trim();
                String norm = normalizeAsciiLower(t);
                if (AFK_ON_PATTERN.matcher(norm).find() || t.equals(AFK_LOG_ON_EXACT)) {
                    lastOnIdx = i;
                } else if (AFK_OFF_PATTERN.matcher(norm).find() || t.equals(AFK_LOG_OFF_EXACT)) {
                    lastOffIdx = i;
                }
            }
            if (lastOnIdx > lastOffIdx) {
                parseAfkStateFromLog(tailLines.get(lastOnIdx));
                afkWorldEntryProbeResolved = true;
            } else if (lastOffIdx > lastOnIdx && afkRewardLoggingActive) {
                clearAfkLoggingClientStateAsIfLeft();
                afkWorldEntryProbeResolved = true;
            }
        } catch (IOException ignored) {
        }
    }

    private static List<String> readLastLogLines(Path path, int maxLines) throws IOException {
        ArrayList<String> lines = new ArrayList<>(Math.min(maxLines, 64));
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            if (length <= 0L) {
                return lines;
            }
            long start = Math.max(0L, length - AFK_WORLD_ENTRY_LOG_TAIL_BYTES);
            file.seek(start);
            if (start > 0L) {
                readUtf8Line(file);
            }
            String line;
            while ((line = readUtf8Line(file)) != null) {
                lines.add(line);
                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }
        }
        return lines;
    }

    private static void tickAfkWorldEntryProbe(MinecraftClient client) {
        if (!afkWorldEntryProbeActive || client == null || client.player == null || client.world == null) {
            return;
        }
        afkWorldEntryProbeTicks++;
        if (afkWorldEntryProbeResolved || afkWorldEntryProbeTicks >= AFK_WORLD_ENTRY_PROBE_MAX_TICKS) {
            afkWorldEntryProbeActive = false;
            if (!afkWorldEntryProbeResolved && reapplyAfkLoggingOnNextWorldEntry && !afkRewardLoggingActive) {
                restoreAfkLoggingStateAfterReconnect();
            }
        }
    }

    /**
     * Transfert inter-serveurs (souvent sans {@code player == null}) : le handler réseau change ou disparaît.
     */
    private static void detectPlayConnectionChange(MinecraftClient client) {
        if (client == null) {
            return;
        }
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        ClientPlayNetworkHandler prev = lastPlayNetworkHandler;
        if (prev != null && prev != handler) {
            onPlaySessionSuspend(client);
        }
        lastPlayNetworkHandler = handler;
    }

    private static void onPlaySessionSuspend(MinecraftClient client) {
        if (client == null) {
            return;
        }
        freezePrestigeOnSessionSuspend();
        boolean wasAfk = afkRewardLoggingActive || reapplyAfkLoggingOnNextWorldEntry;
        prepareAfkReconnectFlags(wasAfk);
        persistAllMenuPositions(client);
    }

    private static void prepareAfkReconnectFlags(boolean wasAfk) {
        if (wasAfk) {
            reapplyAfkLoggingOnNextWorldEntry = true;
            if (prestigeRunActive) {
                prestigeAutoResumeOnAfkReconnect = true;
            }
        } else {
            reapplyAfkLoggingOnNextWorldEntry = false;
            prestigeAutoResumeOnAfkReconnect = false;
        }
    }

    /**
     * Réactive l’AFK côté client (et envoie {@code /afk} si besoin) après déco serveur ou fermeture du jeu.
     */
    private static void tryRestorePendingAfkState(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        if (!reapplyAfkLoggingOnNextWorldEntry || afkRewardLoggingActive) {
            return;
        }
        reapplyAfkLoggingOnNextWorldEntry = false;
        restoreAfkLoggingStateAfterReconnect();
    }

    /** Sauvegarde toutes les positions / tailles des panneaux (fermeture du jeu, déco, fin d’édition). */
    private static void persistAllMenuPositions(MinecraftClient client) {
        if (client == null) {
            return;
        }
        saveLayout(client);
    }

    /** Capture Reward + AFK (même structure par monde que le HUD) + durée Session ; FIFO 5 entrées ; session ≥ 20min. */
    private static void pushLogSessionArchive(MinecraftClient client) {
        if (client == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long sessionMs = Math.max(0L, now - startTimeMs);
        if (sessionMs < MIN_SESSION_MS_FOR_LOG_ARCHIVE) {
            return;
        }
        LogSessionArchiveEntry neu = LogSessionArchiveEntry.captureAt(now, sessionMs);
        LogSessionArchiveEntry last = logSessionArchive.peekLast();
        if (last != null && neu.isQuickDuplicateOf(last)) {
            return;
        }
        logSessionArchive.addLast(neu);
        while (logSessionArchive.size() > MAX_LOG_SESSION_ARCHIVE) {
            logSessionArchive.removeFirst();
        }
    }

    private static void reloadLogSessionArchiveFrom(Properties p) {
        logSessionArchive.clear();
        int n = (int) Math.min(MAX_LOG_SESSION_ARCHIVE, parseLongLenient(p.getProperty("logs.session_archive.count", "0")));
        for (int i = 0; i < n; i++) {
            try {
                logSessionArchive.addLast(LogSessionArchiveEntry.readSlot(p, i));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void persistLogSessionArchiveTo(Properties p) {
        int n = logSessionArchive.size();
        p.setProperty("logs.session_archive.count", String.valueOf(n));
        int i = 0;
        for (LogSessionArchiveEntry e : logSessionArchive) {
            e.writeSlot(p, i++);
        }
        for (int j = n; j < MAX_LOG_SESSION_ARCHIVE + 3; j++) {
            String b = "logs.session_archive." + j + ".";
            p.remove(b + "savedAt");
            p.remove(b + "sessionMs");
            p.remove(b + "CHAMP.money");
            p.remove(b + "CHAMP.exp");
            p.remove(b + "MINE.money");
            p.remove(b + "MINE.exp");
            p.remove(b + "LAC.money");
            p.remove(b + "LAC.exp");
            p.remove(b + "CHAMP.orbes");
            p.remove(b + "CHAMP.cultures");
            p.remove(b + "MINE.gemmes");
            p.remove(b + "MINE.blocs");
            p.remove(b + "LAC.perles");
            p.remove(b + "LAC.poissons");
            p.remove(b + "CHAMP.afk.money");
            p.remove(b + "CHAMP.afk.exp");
            p.remove(b + "CHAMP.afk.orbes");
            p.remove(b + "CHAMP.afk.cultures");
            p.remove(b + "MINE.afk.money");
            p.remove(b + "MINE.afk.exp");
            p.remove(b + "MINE.afk.gemmes");
            p.remove(b + "MINE.afk.blocs");
            p.remove(b + "LAC.afk.money");
            p.remove(b + "LAC.afk.exp");
            p.remove(b + "LAC.afk.perles");
            p.remove(b + "LAC.afk.poissons");
        }
    }

    private static void drawLogArchiveColumns(DrawContext context, MinecraftClient client, int innerL, int innerTop, int innerW, int innerH, int gap,
            int colW) {
        if (innerW <= 4 || innerH <= 6) {
            return;
        }
        int n = logSessionArchive.size();
        if (n == 0) {
            context.drawText(client.textRenderer, Text.translatable("feather_world_menu.logs.archive_empty"),
                    innerL, innerTop + 2, 0xFFB0BEC5, false);
            return;
        }
        int useColW = colW > 0 ? colW : logsArchiveColW(innerW, n, gap);
        List<LogSessionArchiveEntry> ordered = new ArrayList<>(logSessionArchive);
        int idx = 0;
        for (int sourceIndex = ordered.size() - 1; sourceIndex >= 0; sourceIndex--) {
            LogSessionArchiveEntry e = ordered.get(sourceIndex);
            int colLeft = innerL + idx * (useColW + gap);
            drawOneLogArchiveColumn(context, client, colLeft, innerTop, useColW, innerH, idx == 0, e);
            idx++;
        }
    }

    private static void drawOneLogArchiveColumn(DrawContext context, MinecraftClient client, int colLeft, int yTop, int colW, int colH, boolean newestOnLeft,
            LogSessionArchiveEntry e) {
        context.fill(colLeft, yTop, colLeft + colW, yTop + colH, 0x44263238);
        context.drawBorder(colLeft, yTop, colW, colH, 0xFF78909C);
        int x = colLeft + 2;
        int y = yTop + 2;
        int step = client.textRenderer.fontHeight;
        int maxTw = Math.max(8, colW - 4);
        Text colTitle = newestOnLeft
                ? Text.translatable("feather_world_menu.logs.column_new")
                : Text.translatable("feather_world_menu.logs.column_old");
        String colTitleShown = client.textRenderer.trimToWidth(colTitle.getString(), maxTw);
        context.drawText(client.textRenderer, Text.literal(colTitleShown), x, y, 0xFF80CBC4, false);
        y += step;
        context.drawText(client.textRenderer, Text.translatable("feather_world_menu.logs.archive_session_label"), x, y, 0xFF90CAF9, false);
        y += step;
        String dur = client.textRenderer.trimToWidth(formatDuration(e.sessionMs()), maxTw);
        context.drawText(client.textRenderer, Text.literal(dur), x, y, 0xFFECEFF1, false);
        y += step;
        y += step;
        int innerGap = 2;
        int subColW = Math.max(8, (colW - 4 - innerGap) / 2);
        int xReward = colLeft + 2;
        int xAfk = colLeft + 2 + subColW + innerGap;
        int maxTwR = Math.max(6, subColW - 2);
        int maxTwA = Math.max(6, colW - 4 - subColW - innerGap);
        Text rewardHdr = Text.translatable("feather_world_menu.logs.archive_column_reward");
        Text afkHdr = Text.translatable("feather_world_menu.logs.archive_column_afk");
        String rewardHdrShown = client.textRenderer.trimToWidth(rewardHdr.getString(), maxTwR);
        String afkHdrShown = client.textRenderer.trimToWidth(afkHdr.getString(), maxTwA);
        context.drawText(client.textRenderer, Text.literal(rewardHdrShown), xReward, y, 0xFFA5D6A7, false);
        context.drawText(client.textRenderer, Text.literal(afkHdrShown), xAfk, y, 0xFFFFD54F, false);
        y += step;
        List<String> kCh = rewardKeysForZone(RewardDisplayZone.CHAMP);
        List<String> kMi = rewardKeysForZone(RewardDisplayZone.MINE);
        List<String> kLa = rewardKeysForZone(RewardDisplayZone.LAC);
        int yR = drawArchiveWorldRewards(context, client, xReward, y, maxTwR, step, "Champ", kCh, e.champMoney(), e.champExp(), e.champExtra0(), e.champExtra1());
        int yA = drawArchiveWorldRewards(context, client, xAfk, y, maxTwA, step, "Champ", kCh, e.afkChampMoney(), e.afkChampExp(), e.afkChampExtra0(), e.afkChampExtra1());
        y = Math.max(yR, yA) + step;
        yR = drawArchiveWorldRewards(context, client, xReward, y, maxTwR, step, "Mine", kMi, e.mineMoney(), e.mineExp(), e.mineExtra0(), e.mineExtra1());
        yA = drawArchiveWorldRewards(context, client, xAfk, y, maxTwA, step, "Mine", kMi, e.afkMineMoney(), e.afkMineExp(), e.afkMineExtra0(), e.afkMineExtra1());
        y = Math.max(yR, yA) + step;
        drawArchiveWorldRewards(context, client, xReward, y, maxTwR, step, "Lac", kLa, e.lacMoney(), e.lacExp(), e.lacExtra0(), e.lacExtra1());
        drawArchiveWorldRewards(context, client, xAfk, y, maxTwA, step, "Lac", kLa, e.afkLacMoney(), e.afkLacExp(), e.afkLacExtra0(), e.afkLacExtra1());
    }

    /** Affiche les 4 ressources d’un monde (Money / Exp + deux spécifiques) avec libellés complets. */
    private static int drawArchiveWorldRewards(DrawContext context, MinecraftClient client, int x, int y, int maxTw, int step, String worldTitle,
            List<String> keysFour, double vMoney, double vExp, double vExtra0, double vExtra1) {
        context.drawText(client.textRenderer, Text.literal(worldTitle), x, y, 0xFFB0BEC5, false);
        y += step;
        String moneyLabel = STATS.get(keysFour.get(0)).label();
        String expLabel = STATS.get(keysFour.get(1)).label();
        String ex0Label = STATS.get(keysFour.get(2)).label();
        String ex1Label = STATS.get(keysFour.get(3)).label();
        y = drawArchiveRewardLine(context, client, x, y, maxTw, step, moneyLabel, vMoney);
        y = drawArchiveRewardLine(context, client, x, y, maxTw, step, expLabel, vExp);
        y = drawArchiveRewardLine(context, client, x, y, maxTw, step, ex0Label, vExtra0);
        return drawArchiveRewardLine(context, client, x, y, maxTw, step, ex1Label, vExtra1);
    }

    private static int drawArchiveRewardLine(DrawContext context, MinecraftClient client, int x, int y, int maxTw, int step, String label, double value) {
        String line = client.textRenderer.trimToWidth(label + " " + formatSignedCompact(value), maxTw);
        context.drawText(client.textRenderer, Text.literal(line), x, y, 0xFFE8EAF6, false);
        return y + step;
    }

    /** Panneau sections : déplaçable / redimensionnable ; clics valides aussi sans {@code Screen} (HUD seul en jeu). */
    private static void handleSectionsPanelInteraction(MinecraftClient client) {
        if (!sectionsPanelOpen || client.getWindow() == null) {
            sectionsDragging = false;
            sectionsResizing = false;
            sectionsMouseDownLast = false;
            return;
        }
        long window = client.getWindow().getHandle();
        boolean mouseDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        double scaledMouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double scaledMouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        int mouseX = (int) scaledMouseX;
        int mouseY = (int) scaledMouseY;
        if (enchantOptionsMenuEnabled && (enchantOptionsDragging || enchantOptionsResizing || mouseInsideEnchantOptionsPanelBounds(mouseX, mouseY))) {
            sectionsMouseDownLast = mouseDown;
            return;
        }
        if (enchantMenuEnabled && (enchantDragging || enchantResizing || mouseInsideEnchantPanelBounds(mouseX, mouseY))) {
            sectionsMouseDownLast = mouseDown;
            return;
        }
        if (logsMenuEnabled && (logsDragging || logsResizing || mouseInsideLogsPanelBounds(mouseX, mouseY))) {
            sectionsMouseDownLast = mouseDown;
            return;
        }
        if (resizing) {
            sectionsResizing = false;
            sectionsDragging = false;
            logsResizing = false;
            logsDragging = false;
            enchantResizing = false;
            enchantDragging = false;
            enchantOptionsResizing = false;
            enchantOptionsDragging = false;
            sectionsMouseDownLast = mouseDown;
            return;
        }
        int adjY = mouseY + sectionsScrollPx;

        int sx = sectionsPosX;
        int sy = sectionsPosY;
        int sw = sectionsWidth;
        int sh = sectionsHeight;

        boolean inHeader = mouseX >= sx && mouseX <= sx + sw && mouseY >= sy && mouseY <= sy + SECTIONS_HEADER_H;
        boolean inResizeHandle = sectionsResizeHandleHit(mouseX, mouseY) && !mainHudCardResizeHandleHit(mouseX, mouseY);
        int sectionsFooterTop = sy + sh - SECTIONS_PANEL_FOOTER_H;
        boolean inFooterStrip = mouseY >= sectionsFooterTop;

        int rowTop = sy + SECTIONS_HEADER_H + 2;
        int prestigeRowY = rowTop + sectionRowIndex(1) * SECTIONS_ROW_H;
        int prestigeSubRowY = showHudPrestige ? prestigeRowY + SECTIONS_ROW_H : Integer.MIN_VALUE;
        int branchWPrestigeHit = client.textRenderer.getWidth(Text.literal(SECTIONS_REWARD_MONEY_BRANCH));
        int prestigeSubClearW = prestigeSubRowClearBtnW(client);
        String prestigePauseLabelHit = prestigePaused ? "resume" : "pause";
        int prestigeSubPauseW = prestigeSubRowPauseBtnWForLabel(client, prestigePauseLabelHit);
        int prestigeSubClearLeft = sx + CARD_INSET + branchWPrestigeHit + 2;
        int prestigeSubPauseLeft = prestigeSubClearLeft + prestigeSubClearW + SECTIONS_PRESTIGE_BTN_GAP;
        RewardAfkCacherSubLayout raCacher = layoutRewardAfkCacherSubRow(client, sx);
        RewardAfkClearSubLayout raClear = layoutRewardAfkClearSubRow(client, sx);
        int rewardResetBtnW = sectionsRewardResetBtnW(client);
        int rewardClearHourlyBtnW = sectionsRewardClearHourlyBtnW(client);
        int rewardClearTenMinBtnW = sectionsRewardClearTenMinBtnW(client);
        int keyBtnW = sectionsKeyBtnW(client);
        int rewardCacherTenMinLeft = raCacher.tenMinLeft();
        int rewardCacherHourLeft = raCacher.hourLeft();
        int rewardClearTenMinLeft = raClear.tenMinLeft();
        int rewardClearHourlyLeft = raClear.hourlyLeft();
        int rewardResetLeft = raClear.resetLeft();
        int rewardRowY = rowTop + sectionRowIndex(2) * SECTIONS_ROW_H;
        int rewardCacherMoneyRowY = showHudReward ? rewardRowY + SECTIONS_ROW_H : Integer.MIN_VALUE;
        int rewardClearSubRowY = showHudReward ? rewardRowY + 2 * SECTIONS_ROW_H : Integer.MIN_VALUE;
        int afkRowY = rowTop + sectionRowIndex(3) * SECTIONS_ROW_H;
        int afkCacherMoneyRowY = showHudAfk ? afkRowY + SECTIONS_ROW_H : Integer.MIN_VALUE;
        int afkClearSubRowY = showHudAfk ? afkRowY + 2 * SECTIONS_ROW_H : Integer.MIN_VALUE;
        int enchantRowY = rowTop + sectionRowIndex(SECTIONS_ENCHANT_ROW_INDEX) * SECTIONS_ROW_H;
        int enchantOptionsSubRowY = enchantMenuEnabled ? enchantRowY + SECTIONS_ROW_H : Integer.MIN_VALUE;
        int enchantOptionsBtnLeft = sx + CARD_INSET + client.textRenderer.getWidth(Text.literal(SECTIONS_REWARD_MONEY_BRANCH)) + 2;
        int enchantOptionsBtnW = sectionsEnchantOptionsBtnW(client);
        int cacherTenMinBtnW = sectionsCacherTenMinBtnW(client);
        int cacherHourBtnW = sectionsCacherHourBtnW(client);
        int informationsRowY = sectionsInformationsRowTop(rowTop);
        int keyHeaderY = informationsRowY + SECTIONS_ROW_H + SECTIONS_KEY_HEADER_GAP;
        int keyRowsStartY = keyHeaderY + SECTIONS_KEY_ROW_H;
        int keyBtnLeft = sx + sw - CARD_INSET - keyBtnW;
        int scrollTrackLeft = sx + sw - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
        boolean inScrollableViewport = mouseInsideSectionsScrollableViewport(mouseX, mouseY, sx, sy, sw, sh);
        if (captureBindingIndex != KEY_CAPTURE_NONE) {
            int pressedKey = detectPressedKeyCode(window);
            if (pressedKey != -1) {
                applyBindingCode(captureBindingIndex, pressedKey);
                captureBindingIndex = KEY_CAPTURE_NONE;
                saveLayout(client);
            }
        }
        if (!sectionsMouseDownLast && mouseDown) {
            if (inResizeHandle && !mainHudCardResizeHandleHit(mouseX, mouseY)) {
                sectionsResizing = true;
                sectionsDragging = false;
                resizing = false;
                dragging = false;
                sectionsResizeStartMouseX = mouseX;
                sectionsResizeStartMouseY = mouseY;
                sectionsResizeStartW = sectionsWidth;
                sectionsResizeStartH = sectionsHeight;
            } else if (!inFooterStrip) {
                boolean hitToggle = false;
                int checkBox = 8;
                int bxCheck = sx + CARD_INSET;
                if (inScrollableViewport) {
                    for (int si = 0; si < SECTIONS_SECTION_ROWS; si++) {
                        int ry = rowTop + sectionRowIndex(si) * SECTIONS_ROW_H;
                        if (!sectionsPanelRowCheckboxHit(mouseX, adjY, bxCheck, checkBox, ry)) {
                            continue;
                        }
                        if (si == SECTIONS_LOG_ROW_INDEX) {
                                logsMenuEnabled = !logsMenuEnabled;
                                if (!logsMenuEnabled) {
                                    logsDragging = false;
                                    logsResizing = false;
                                    logsScrollHorizPx = 0;
                                }
                            } else if (si == SECTIONS_ENCHANT_ROW_INDEX) {
                                if (enchantMenuEnabled) {
                                    closeEnchantMenu();
                                } else {
                                    enchantMenuEnabled = true;
                                }
                            } else if (si == SECTIONS_AUTOFISH_ROW_INDEX) {
                                autoFishLogTriggerEnabled = !autoFishLogTriggerEnabled;
                                saveLayout(client);
                            } else {
                                toggleHudSection(si);
                                saveLayout(client);
                            }
                        hitToggle = true;
                        break;
                    }
                }
                /* Informations : ouverture écran uniquement via la case à gauche. */
                if (inScrollableViewport && !hitToggle) {
                    int infoRowTop = sectionsInformationsRowTop(rowTop);
                    if (sectionsPanelRowCheckboxHit(mouseX, adjY, bxCheck, checkBox, infoRowTop)) {
                        MinecraftClient c = client;
                        Screen parent = c.currentScreen;
                        c.execute(() -> c.setScreen(new ModInfoScreen(parent)));
                        hitToggle = true;
                    }
                }
            if (!hitToggle && inScrollableViewport) {
                if (showHudPrestige && adjY >= prestigeSubRowY && adjY < prestigeSubRowY + SECTIONS_ROW_H) {
                    if (mouseX >= prestigeSubClearLeft && mouseX < prestigeSubClearLeft + prestigeSubClearW) {
                        clearPrestigeTimers();
                        hitToggle = true;
                    } else if (mouseX >= prestigeSubPauseLeft && mouseX < prestigeSubPauseLeft + prestigeSubPauseW) {
                        togglePrestigePause();
                        hitToggle = true;
                    }
                }
            }
            if (!hitToggle) {
                if (showHudReward && adjY >= rewardClearSubRowY && adjY < rewardClearSubRowY + SECTIONS_ROW_H) {
                    if (mouseX >= rewardResetLeft && mouseX < rewardResetLeft + rewardResetBtnW) {
                        resetRewardTrackers();
                        hitToggle = true;
                    } else if (mouseX >= rewardClearHourlyLeft
                            && mouseX < rewardClearHourlyLeft + rewardClearHourlyBtnW) {
                        resetRewardTrackersHourly();
                        hitToggle = true;
                    } else if (mouseX >= rewardClearTenMinLeft
                            && mouseX < rewardClearTenMinLeft + rewardClearTenMinBtnW) {
                        resetRewardTrackersTenMinutes();
                        hitToggle = true;
                    }
                } else if (showHudAfk && adjY >= afkClearSubRowY && adjY < afkClearSubRowY + SECTIONS_ROW_H) {
                    if (mouseX >= rewardResetLeft && mouseX < rewardResetLeft + rewardResetBtnW) {
                        resetAfkRewardTrackers();
                        hitToggle = true;
                    } else if (mouseX >= rewardClearHourlyLeft
                            && mouseX < rewardClearHourlyLeft + rewardClearHourlyBtnW) {
                        resetAfkRewardTrackersHourly();
                        hitToggle = true;
                    } else if (mouseX >= rewardClearTenMinLeft
                            && mouseX < rewardClearTenMinLeft + rewardClearTenMinBtnW) {
                        resetAfkRewardTrackersTenMinutes();
                        hitToggle = true;
                    }
                } else if (showHudReward && adjY >= rewardCacherMoneyRowY && adjY < rewardCacherMoneyRowY + SECTIONS_ROW_H) {
                    if (mouseX >= rewardCacherTenMinLeft && mouseX < rewardCacherTenMinLeft + cacherTenMinBtnW) {
                        hideRewardTenMin = !hideRewardTenMin;
                        saveLayout(client);
                        hitToggle = true;
                    } else if (mouseX >= rewardCacherHourLeft && mouseX < rewardCacherHourLeft + cacherHourBtnW) {
                        hideRewardHourly = !hideRewardHourly;
                        saveLayout(client);
                        hitToggle = true;
                    } else {
                        int moneyLeft = sectionsMoneyBtnLeft(rewardCacherHourLeft, cacherHourBtnW);
                        int moneyBtnW = sectionsMoneyBtnW(client);
                        int moneyBtnTop = rewardCacherMoneyRowY + 1;
                        int moneyBtnH = SECTIONS_ROW_H - 2;
                        if (mouseX >= moneyLeft && mouseX < moneyLeft + moneyBtnW
                                && adjY >= moneyBtnTop && adjY < moneyBtnTop + moneyBtnH) {
                            hideRewardMoneyRow = !hideRewardMoneyRow;
                            saveLayout(client);
                            hitToggle = true;
                        }
                    }
                } else if (showHudAfk && adjY >= afkCacherMoneyRowY && adjY < afkCacherMoneyRowY + SECTIONS_ROW_H) {
                    if (mouseX >= rewardCacherTenMinLeft && mouseX < rewardCacherTenMinLeft + cacherTenMinBtnW) {
                        hideAfkTenMin = !hideAfkTenMin;
                        saveLayout(client);
                        hitToggle = true;
                    } else if (mouseX >= rewardCacherHourLeft && mouseX < rewardCacherHourLeft + cacherHourBtnW) {
                        hideAfkHourly = !hideAfkHourly;
                        saveLayout(client);
                        hitToggle = true;
                    } else {
                        int moneyLeft = sectionsMoneyBtnLeft(rewardCacherHourLeft, cacherHourBtnW);
                        int moneyBtnW = sectionsMoneyBtnW(client);
                        int moneyBtnTop = afkCacherMoneyRowY + 1;
                        int moneyBtnH = SECTIONS_ROW_H - 2;
                        if (mouseX >= moneyLeft && mouseX < moneyLeft + moneyBtnW
                                && adjY >= moneyBtnTop && adjY < moneyBtnTop + moneyBtnH) {
                            hideAfkMoneyRow = !hideAfkMoneyRow;
                            saveLayout(client);
                            hitToggle = true;
                        }
                    }
                } else if (enchantMenuEnabled && adjY >= enchantOptionsSubRowY && adjY < enchantOptionsSubRowY + SECTIONS_ROW_H) {
                    int btnTop = enchantOptionsSubRowY + 1;
                    int btnH = SECTIONS_ROW_H - 2;
                    if (mouseX >= enchantOptionsBtnLeft && mouseX < enchantOptionsBtnLeft + enchantOptionsBtnW
                            && adjY >= btnTop && adjY < btnTop + btnH) {
                        if (enchantOptionsMenuEnabled) {
                            closeEnchantOptionsMenu();
                        } else {
                            enchantOptionsMenuEnabled = true;
                        }
                        hitToggle = true;
                    }
                }
            }
            if (!hitToggle && inScrollableViewport) {
                for (int bi = 0; bi < SECTIONS_KEY_BINDING_LABELS.length; bi++) {
                    int by = keyRowsStartY + bi * SECTIONS_KEY_ROW_H;
                    if (mouseX >= keyBtnLeft && mouseX < keyBtnLeft + keyBtnW
                            && adjY >= by && adjY < by + SECTIONS_KEY_ROW_H) {
                        captureBindingIndex = bi;
                        hitToggle = true;
                        break;
                    }
                }
            }
            if (!hitToggle && inHeader) {
                sectionsDragging = true;
                sectionsResizing = false;
                sectionsDragOffX = mouseX - sx;
                sectionsDragOffY = mouseY - sy;
            }
            }
        } else if (!mouseDown) {
            if (sectionsDragging || sectionsResizing) {
                saveLayout(client);
            }
            sectionsDragging = false;
            sectionsResizing = false;
        }
        sectionsMouseDownLast = mouseDown;

        if (sectionsDragging) {
            sectionsPosX = clamp(mouseX - sectionsDragOffX, 2, Math.max(2, client.getWindow().getScaledWidth() - sectionsWidth - 2));
            sectionsPosY = clamp(mouseY - sectionsDragOffY, 2, Math.max(2, client.getWindow().getScaledHeight() - sectionsHeight - 2));
        }
        if (sectionsResizing) {
            int dx = mouseX - sectionsResizeStartMouseX;
            int dy = mouseY - sectionsResizeStartMouseY;
            sectionsWidth = Math.max(minSectionsPanelWidth(client), sectionsResizeStartW + dx);
            sectionsHeight = Math.max(MIN_SECTIONS_PANEL_H, sectionsResizeStartH + dy);
        }
    }

    private static void resetRewardTrackers() {
        for (RewardTracker rw : REWARDS.values()) {
            rw.reset();
        }
        for (RewardTracker rw : rewardMoneyByZone.values()) {
            rw.reset();
        }
        for (RewardTracker rw : rewardExpByZone.values()) {
            rw.reset();
        }
        clearSeenRewardLineFingerprints();
    }

    private static void resetAfkRewardTrackers() {
        for (RewardTracker rw : AFK_REWARDS.values()) {
            rw.reset();
        }
        for (RewardTracker rw : afkMoneyByZone.values()) {
            rw.reset();
        }
        for (RewardTracker rw : afkExpByZone.values()) {
            rw.reset();
        }
        clearSeenAfkRewardLineFingerprints();
    }

    /** Vide uniquement le « /h » de Reward ; le « /10min » et le total session continuent. */
    private static void resetRewardTrackersHourly() {
        long now = System.currentTimeMillis();
        for (RewardTracker rw : REWARDS.values()) {
            rw.resetHourly(now);
        }
        for (RewardTracker rw : rewardMoneyByZone.values()) {
            rw.resetHourly(now);
        }
        for (RewardTracker rw : rewardExpByZone.values()) {
            rw.resetHourly(now);
        }
    }

    /** Vide uniquement le « /h » d’AFK ; le « /10min » et le total session continuent. */
    private static void resetAfkRewardTrackersHourly() {
        long now = System.currentTimeMillis();
        for (RewardTracker rw : AFK_REWARDS.values()) {
            rw.resetHourly(now);
        }
        for (RewardTracker rw : afkMoneyByZone.values()) {
            rw.resetHourly(now);
        }
        for (RewardTracker rw : afkExpByZone.values()) {
            rw.resetHourly(now);
        }
    }

    /** Vide uniquement le « /10m » de Reward ; le « /h » et le total session continuent. */
    private static void resetRewardTrackersTenMinutes() {
        long now = System.currentTimeMillis();
        for (RewardTracker rw : REWARDS.values()) {
            rw.resetTenMinutes(now);
        }
        for (RewardTracker rw : rewardMoneyByZone.values()) {
            rw.resetTenMinutes(now);
        }
        for (RewardTracker rw : rewardExpByZone.values()) {
            rw.resetTenMinutes(now);
        }
    }

    /** Vide uniquement le « /10m » d’AFK ; le « /h » et le total session continuent. */
    private static void resetAfkRewardTrackersTenMinutes() {
        long now = System.currentTimeMillis();
        for (RewardTracker rw : AFK_REWARDS.values()) {
            rw.resetTenMinutes(now);
        }
        for (RewardTracker rw : afkMoneyByZone.values()) {
            rw.resetTenMinutes(now);
        }
        for (RewardTracker rw : afkExpByZone.values()) {
            rw.resetTenMinutes(now);
        }
    }

    private static void toggleHudSection(int index) {
        switch (index) {
            case 0 -> showHudSession = !showHudSession;
            case 1 -> showHudPrestige = !showHudPrestige;
            case 2 -> showHudReward = !showHudReward;
            case 3 -> {
                showHudAfk = !showHudAfk;
                // Si l’utilisateur change manuellement la visibilité pendant l’auto-affichage AFK,
                // on enregistre son nouveau choix comme valeur à restaurer à la sortie d’AFK.
                if (afkAutoShownActive) {
                    showHudAfkSavedBeforeAfk = showHudAfk;
                }
            }
            case 4 -> showHudFarm = !showHudFarm;
            case 5 -> showHudDouble = !showHudDouble;
            case 6 -> showHudPotions = !showHudPotions;
            default -> {
            }
        }
    }

    private static void resetForNewSessionIfNeeded(MinecraftClient client) {
        boolean inGame = client.player != null && client.world != null;
        if (!inGame && wasInGame) {
            onPlaySessionSuspend(client);
            boolean wasAfkBeforeDisconnect = afkRewardLoggingActive || reapplyAfkLoggingOnNextWorldEntry;
            reconnectAfkCommandWatchActive = false;
            reconnectAfkCommandWatchTicks = 0;
            prepareAfkReconnectFlags(wasAfkBeforeDisconnect);
            pushLogSessionArchive(client);
            clearSessionTracking();
            reapplyAfkLoggingOnNextWorldEntry = wasAfkBeforeDisconnect;
            if (wasAfkBeforeDisconnect && prestigeRunActive) {
                prestigeAutoResumeOnAfkReconnect = true;
            }
            persistAllMenuPositions(client);
            lastHudScaledW = -1;
            lastHudScaledH = -1;
        }
        if (inGame && !wasInGame) {
            lastHudScaledW = -1;
            lastHudScaledH = -1;
            startTimeMs = System.currentTimeMillis();
            beginAfkDetectionForWorldEntry(client);
        }
        wasInGame = inGame;
    }

    /**
     * Après reconnexion : le log ne rejoue pas « maintenant AFK » (offset en fin de fichier),
     * mais le serveur peut toujours être en AFK — on réaligne l’état client.
     */
    private static void restoreAfkLoggingStateAfterReconnect() {
        afkRewardLoggingActive = true;
        afkAutoShownActive = true;
        if (!showHudAfk) {
            showHudAfk = true;
        }
        afkAwaitingLocalActivityClearAfterReconnect = true;
        reconnectAfkCommandWatchActive = true;
        reconnectAfkCommandWatchTicks = 0;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null && c.player != null) {
            reconnectAfkActivityBaseline = c.player.getPos();
        } else {
            reconnectAfkActivityBaseline = null;
        }
        if (prestigeRunActive && prestigePaused) {
            prestigeAutoResumeOnAfkReconnect = true;
        }
        if (c != null) {
            saveLayout(c);
        }
    }

    /** Reprend le chrono prestige quand l’AFK est confirmé après reconnexion / redém. */
    private static void resumePrestigeAfterAfkReconnect() {
        if (!prestigeRunActive) {
            prestigeAutoResumeOnAfkReconnect = false;
            return;
        }
        if (!prestigeAutoResumeOnAfkReconnect && !prestigePaused) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = prestigePaused
                ? Math.max(0L, prestigeFrozenElapsedMs)
                : Math.max(0L, now - prestigeStartMs);
        prestigeStartMs = now - elapsed;
        prestigePaused = false;
        prestigeFrozenElapsedMs = 0L;
        prestigeAutoResumeOnAfkReconnect = false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            saveLayout(client);
        }
    }

    /** Reprend le chrono prestige si en pause (bouton / touche). */
    private static void resumePrestigeIfPaused() {
        if (!prestigeRunActive || !prestigePaused) {
            return;
        }
        long now = System.currentTimeMillis();
        prestigeStartMs = now - Math.max(0L, prestigeFrozenElapsedMs);
        prestigePaused = false;
        prestigeAutoResumeOnAfkReconnect = false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            saveLayout(client);
        }
    }

    /**
     * Envoie {@code /afk} seulement si, après reconnexion, le log « maintenant AFK » n’a pas été lu
     * ({@link #parseAfkStateFromLog} annule la surveillance dès qu’il apparaît).
     */
    private static void tickReconnectAfkCommand(MinecraftClient client) {
        if (!reconnectAfkCommandWatchActive) {
            return;
        }
        reconnectAfkCommandWatchTicks++;
        if (reconnectAfkCommandWatchTicks < RECONNECT_AFK_COMMAND_MIN_WAIT_TICKS) {
            return;
        }
        if (reconnectAfkCommandWatchTicks < RECONNECT_AFK_COMMAND_MAX_WAIT_TICKS) {
            return;
        }
        reconnectAfkCommandWatchActive = false;
        if (client.player == null || client.getNetworkHandler() == null) {
            reconnectAfkCommandWatchActive = true;
            reconnectAfkCommandWatchTicks = RECONNECT_AFK_COMMAND_MAX_WAIT_TICKS - 10;
            return;
        }
        sendReconnectAfkCommand(client);
    }

    private static void sendReconnectAfkCommand(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) {
            return;
        }
        suppressReconnectAfkSendClear = true;
        client.player.networkHandler.sendChatCommand("afk");
    }

    private static void tryClearReconnectRestoredAfkOnLocalActivity(MinecraftClient client) {
        if (!afkAwaitingLocalActivityClearAfterReconnect || !afkRewardLoggingActive) {
            return;
        }
        if (client.player == null || client.world == null) {
            return;
        }
        if (reconnectAfkActivityBaseline == null) {
            reconnectAfkActivityBaseline = client.player.getPos();
            return;
        }
        Vec3d pos = client.player.getPos();
        boolean moved = pos.squaredDistanceTo(reconnectAfkActivityBaseline) > RECONNECT_AFK_MOVE_EPS_SQ
                || Math.abs(pos.y - reconnectAfkActivityBaseline.y) > RECONNECT_AFK_VERT_EPS;
        var opts = client.options;
        boolean input = opts.forwardKey.wasPressed() || opts.backKey.wasPressed()
                || opts.leftKey.wasPressed() || opts.rightKey.wasPressed()
                || opts.jumpKey.wasPressed() || opts.sneakKey.wasPressed() || opts.sprintKey.wasPressed()
                || opts.attackKey.wasPressed() || opts.useKey.wasPressed()
                || opts.inventoryKey.wasPressed() || opts.dropKey.wasPressed()
                || opts.swapHandsKey.wasPressed() || opts.pickItemKey.wasPressed();
        if (moved || input) {
            clearAfkLoggingClientStateAsIfLeft();
        }
    }

    /** Même effet qu’une ligne « plus AFK » dans le log, plus reset du mode « post-reconnexion ». */
    private static void clearAfkLoggingClientStateAsIfLeft() {
        afkAwaitingLocalActivityClearAfterReconnect = false;
        reconnectAfkActivityBaseline = null;
        prestigeAutoResumeOnAfkReconnect = false;
        reapplyAfkLoggingOnNextWorldEntry = false;
        if (!afkRewardLoggingActive) {
            return;
        }
        afkRewardLoggingActive = false;
        pauseAfkFarmSessionsAtLeave(System.currentTimeMillis());
        if (afkAutoShownActive) {
            showHudAfk = showHudAfkSavedBeforeAfk;
            afkAutoShownActive = false;
        }
    }

    private static void clearSessionTracking() {
        startTimeMs = System.currentTimeMillis();
        eventEndMs = 0L;
        nextEventEndMs = 0L;
        eventZone = "--";
        doubleEventEndMs = 0L;
        doubleProchainEndMs = 0L;
        doubleMoneyActive = false;
        doubleExpActive = false;
        doubleGensDropActive = false;
        doubleEventRestartAfterProchain = true;
        for (StatLine stat : STATS.values()) {
            stat.reset();
        }
        for (RewardTracker rw : REWARDS.values()) {
            rw.reset();
        }
        for (RewardTracker rw : AFK_REWARDS.values()) {
            rw.reset();
        }
        for (RewardTracker rw : rewardMoneyByZone.values()) {
            rw.reset();
        }
        for (RewardTracker rw : rewardExpByZone.values()) {
            rw.reset();
        }
        for (RewardTracker rw : afkMoneyByZone.values()) {
            rw.reset();
        }
        for (RewardTracker rw : afkExpByZone.values()) {
            rw.reset();
        }
        resetEnchantOptionLogCounts();
        latestLogSourceAnnounced = false;
        latestLogMissingWarned = false;
        latestLogReadErrorWarned = false;
        cachedHudLatestLogPath = null;
        logReadOffsetPath = null;
        lastLogPathRefreshWallMs = -999_000L;
        clearSeenRewardLineFingerprints();
        clearSeenAfkRewardLineFingerprints();
        clearSeenEnchantOptionLogLineFingerprints();
        clearSeenPrestigeLogLineFingerprints();
        afkRewardLoggingActive = false;
        rewardFarmSessionByZone.clear();
        afkFarmSessionByZone.clear();
        afkAutoShownActive = false;
        afkAwaitingLocalActivityClearAfterReconnect = false;
        reconnectAfkActivityBaseline = null;
    }

    private static void clearSeenRewardLineFingerprints() {
        seenRewardLineOrder.clear();
        seenRewardLineFingerprints.clear();
    }

    private static void clearSeenAfkRewardLineFingerprints() {
        seenAfkRewardLineOrder.clear();
        seenAfkRewardLineFingerprints.clear();
    }

    private static void clearSeenEnchantOptionLogLineFingerprints() {
        seenEnchantOptionLogLineOrder.clear();
        seenEnchantOptionLogLineFingerprints.clear();
    }

    private static void clearSeenPrestigeLogLineFingerprints() {
        seenPrestigeLogLineOrder.clear();
        seenPrestigeLogLineFingerprints.clear();
    }

    /** @return {@code false} si cette ligne a déjà été comptée pour la section Reward. */
    private static boolean rememberRewardLineIfNew(String normalizedFingerprint) {
        if (seenRewardLineFingerprints.contains(normalizedFingerprint)) {
            return false;
        }
        seenRewardLineFingerprints.add(normalizedFingerprint);
        seenRewardLineOrder.addLast(normalizedFingerprint);
        while (seenRewardLineOrder.size() > MAX_SEEN_RECAP_LINE_FINGERPRINTS) {
            String old = seenRewardLineOrder.pollFirst();
            if (old != null) {
                seenRewardLineFingerprints.remove(old);
            }
        }
        return true;
    }

    /** @return {@code false} si cette ligne exacte a déjà été comptée pour les compteurs du menu Enchant. */
    private static boolean rememberEnchantOptionLogLineIfNew(String normalizedFingerprint) {
        if (seenEnchantOptionLogLineFingerprints.contains(normalizedFingerprint)) {
            return false;
        }
        seenEnchantOptionLogLineFingerprints.add(normalizedFingerprint);
        seenEnchantOptionLogLineOrder.addLast(normalizedFingerprint);
        while (seenEnchantOptionLogLineOrder.size() > MAX_SEEN_RECAP_LINE_FINGERPRINTS) {
            String old = seenEnchantOptionLogLineOrder.pollFirst();
            if (old != null) {
                seenEnchantOptionLogLineFingerprints.remove(old);
            }
        }
        return true;
    }

    private static void loadEnchantOptionStates(Properties p) {
        for (EnchantOptionSet optionSet : ENCHANT_OPTION_SETS) {
            for (int i = 0; i < optionSet.enabled().length; i++) {
                optionSet.enabled()[i] = Boolean.parseBoolean(
                        p.getProperty("enchant." + optionSet.configKey() + ".option." + i, "false"));
            }
        }
    }

    private static void saveEnchantOptionStates(Properties p) {
        for (EnchantOptionSet optionSet : ENCHANT_OPTION_SETS) {
            for (int i = 0; i < optionSet.enabled().length; i++) {
                p.setProperty("enchant." + optionSet.configKey() + ".option." + i, String.valueOf(optionSet.enabled()[i]));
            }
        }
    }

    private static void resetEnchantOptionLogCounts() {
        for (EnchantOptionSet optionSet : ENCHANT_OPTION_SETS) {
            Arrays.fill(optionSet.logCounts(), 0);
        }
    }

    /** Cumuls Enchant : uniquement à la fermeture du client (pas à la sortie d’un monde). */
    private static void resetEnchantOptionLogSums() {
        for (EnchantOptionSet optionSet : ENCHANT_OPTION_SETS) {
            Arrays.fill(optionSet.logSums(), 0.0);
            for (Map<String, Double> byType : optionSet.logSumsByType()) {
                byType.clear();
            }
        }
    }

    /** Remet à zéro procs + cumuls Enchant du monde courant uniquement (Lac, Mine ou Ferme). */
    public static void resetEnchantPanelCounters() {
        EnchantOptionSet optionSet = currentEnchantOptionSet();
        if (optionSet == null) {
            return;
        }
        Arrays.fill(optionSet.logCounts(), 0);
        Arrays.fill(optionSet.logSums(), 0.0);
        for (Map<String, Double> byType : optionSet.logSumsByType()) {
            byType.clear();
        }
    }

    /** @return {@code false} si cette ligne prestige a déjà été traitée (évite double déclenchement Lunar). */
    private static boolean rememberPrestigeLogLineIfNew(String normalizedFingerprint) {
        if (seenPrestigeLogLineFingerprints.contains(normalizedFingerprint)) {
            return false;
        }
        seenPrestigeLogLineFingerprints.add(normalizedFingerprint);
        seenPrestigeLogLineOrder.addLast(normalizedFingerprint);
        while (seenPrestigeLogLineOrder.size() > MAX_SEEN_RECAP_LINE_FINGERPRINTS) {
            String old = seenPrestigeLogLineOrder.pollFirst();
            if (old != null) {
                seenPrestigeLogLineFingerprints.remove(old);
            }
        }
        return true;
    }

    /** @return {@code false} si cette ligne a déjà été comptée pour la section AFK. */
    private static boolean rememberAfkRewardLineIfNew(String normalizedFingerprint) {
        if (seenAfkRewardLineFingerprints.contains(normalizedFingerprint)) {
            return false;
        }
        seenAfkRewardLineFingerprints.add(normalizedFingerprint);
        seenAfkRewardLineOrder.addLast(normalizedFingerprint);
        while (seenAfkRewardLineOrder.size() > MAX_SEEN_RECAP_LINE_FINGERPRINTS) {
            String old = seenAfkRewardLineOrder.pollFirst();
            if (old != null) {
                seenAfkRewardLineFingerprints.remove(old);
            }
        }
        return true;
    }

    private static void parsePrestigeFromLog(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String s = stripLogPrefix(raw);
        if (s.isBlank()) {
            s = stripForRecapFarm(raw);
        }
        if (s.isBlank()) {
            s = raw.trim();
            s = ANSI_ESCAPE.matcher(s).replaceAll("");
            s = LEGACY_FORMATTING_CODES.matcher(s).replaceAll("").trim();
        }
        if (s.isBlank()) {
            return;
        }
        String norm = normalizeAsciiLower(s);
        if (!PRESTIGE_LOG_PHRASE_NORM.matcher(norm).find()
                && !PRESTIGE_LOG_PHRASE_NORM.matcher(normalizeAsciiLower(raw)).find()) {
            return;
        }
        if (!rememberPrestigeLogLineIfNew(norm)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (prestigeRunActive) {
            long elapsedForDernier = prestigePaused ? prestigeFrozenElapsedMs : (now - prestigeStartMs);
            prestigeDernierDurationMs = Math.max(0L, elapsedForDernier);
        }
        prestigePaused = false;
        prestigeFrozenElapsedMs = 0L;
        prestigeStartMs = now;
        prestigeRunActive = true;
    }

    public static void clearPrestigeTimers() {
        prestigeRunActive = false;
        prestigeStartMs = 0L;
        prestigeDernierDurationMs = -1L;
        prestigePaused = false;
        prestigeFrozenElapsedMs = 0L;
        prestigeAutoResumeOnAfkReconnect = false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            saveLayout(client);
        }
    }

    /** Pause / reprise du chrono « Actuel » (touche configurable ou bouton sous-menu). */
    public static void togglePrestigePause() {
        if (!prestigeRunActive) {
            return;
        }
        long now = System.currentTimeMillis();
        if (prestigePaused) {
            resumePrestigeIfPaused();
        } else {
            prestigeFrozenElapsedMs = now - prestigeStartMs;
            prestigePaused = true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            saveLayout(client);
        }
    }

    /** Met en pause le chrono sans l’effacer (déconnexion, transfert serveur, fermeture du jeu). */
    private static void freezePrestigeOnSessionSuspend() {
        if (!prestigeRunActive || prestigePaused) {
            return;
        }
        prestigeFrozenElapsedMs = Math.max(0L, System.currentTimeMillis() - prestigeStartMs);
        prestigePaused = true;
        prestigeAutoResumeOnAfkReconnect = afkRewardLoggingActive || reapplyAfkLoggingOnNextWorldEntry
                || prestigeAutoResumeOnAfkReconnect;
    }

    private static void savePrestigeStateTo(Properties p) {
        p.setProperty("prestige.run_active", String.valueOf(prestigeRunActive));
        p.setProperty("prestige.paused", String.valueOf(prestigePaused));
        p.setProperty("prestige.frozen_ms", String.valueOf(prestigeFrozenElapsedMs));
        p.setProperty("prestige.start_ms", String.valueOf(prestigeStartMs));
        p.setProperty("prestige.dernier_ms", String.valueOf(prestigeDernierDurationMs));
        p.setProperty("prestige.resume_on_afk_reconnect", String.valueOf(prestigeAutoResumeOnAfkReconnect));
    }

    private static void loadPrestigeStateFrom(Properties p) {
        prestigeRunActive = Boolean.parseBoolean(p.getProperty("prestige.run_active", "false"));
        prestigePaused = Boolean.parseBoolean(p.getProperty("prestige.paused", "false"));
        try {
            prestigeFrozenElapsedMs = Long.parseLong(p.getProperty("prestige.frozen_ms", "0"));
            prestigeStartMs = Long.parseLong(p.getProperty("prestige.start_ms", "0"));
            prestigeDernierDurationMs = Long.parseLong(p.getProperty("prestige.dernier_ms", "-1"));
        } catch (NumberFormatException ignored) {
            prestigeRunActive = false;
            prestigePaused = false;
            prestigeFrozenElapsedMs = 0L;
            prestigeStartMs = 0L;
            prestigeDernierDurationMs = -1L;
            return;
        }
        if (!prestigeRunActive) {
            prestigePaused = false;
            prestigeFrozenElapsedMs = 0L;
            prestigeStartMs = 0L;
        } else if (!prestigePaused) {
            long elapsed = prestigeFrozenElapsedMs > 0L
                    ? prestigeFrozenElapsedMs
                    : Math.max(0L, System.currentTimeMillis() - prestigeStartMs);
            prestigeStartMs = System.currentTimeMillis() - elapsed;
        }
    }

    private static String prestigeTimeTitleString() {
        String title = Text.translatable("feather_world_menu.prestige_time.title").getString();
        if (prestigePaused) {
            title += Text.translatable("feather_world_menu.prestige_time.pause_suffix").getString();
        }
        return title;
    }

    /** Affiche une durée en secondes, minutes et heures (ex. {@code 45s}, {@code 12m 30s}, {@code 1h 5m}). */
    private static String formatPrestigeSeconds(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        StringBuilder b = new StringBuilder();
        if (h > 0) {
            b.append(h).append('h');
            if (m > 0 || s > 0) {
                b.append(' ');
            }
        }
        if (m > 0) {
            b.append(m).append('m');
            if (s > 0) {
                b.append(' ');
            }
        }
        if (s > 0 || b.length() == 0) {
            b.append(s).append('s');
        }
        return b.toString();
    }

    private static void addPathIfPresent(LinkedHashSet<Path> out, String first, String... more) {
        if (first == null || first.isBlank()) {
            return;
        }
        try {
            out.add(Path.of(first, more).toAbsolutePath().normalize());
        } catch (Exception ignored) {
        }
    }

    private static List<Path> lunarClientRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>(8);
        String userHome = System.getProperty("user.home");
        String appData = System.getenv("APPDATA");
        String localAppData = System.getenv("LOCALAPPDATA");

        addPathIfPresent(roots, userHome, ".lunarclient");
        addPathIfPresent(roots, appData, ".lunarclient");
        addPathIfPresent(roots, localAppData, ".lunarclient");
        addPathIfPresent(roots, localAppData, "LunarClient");
        addPathIfPresent(roots, userHome, "AppData", "Roaming", ".lunarclient");
        addPathIfPresent(roots, userHome, "AppData", "Local", ".lunarclient");
        addPathIfPresent(roots, userHome, "AppData", "Local", "LunarClient");
        addPathIfPresent(roots, userHome, "Library", "Application Support", "lunarclient");

        return new ArrayList<>(roots);
    }

    /** Log global Lunar Client : {@code ~/.lunarclient/offline/multiver/logs/latest.log} (souvent hors du run Minecraft). */
    private static void appendLunarMultiverLatestLogs(LinkedHashSet<Path> order) {
        for (Path root : lunarClientRoots()) {
            order.add(root.resolve("offline").resolve("multiver").resolve("logs").resolve("latest.log").toAbsolutePath().normalize());
        }
    }

    /**
     * Lunar récent écrit souvent dans {@code ~/.lunarclient/profiles/<profil>/<version>/logs/latest.log}.
     * On ajoute aussi la variante sans dossier version.
     */
    private static void appendLunarProfileLatestLogs(LinkedHashSet<Path> order) {
        for (Path root : lunarClientRoots()) {
            Path profilesRoot = root.resolve("profiles");
            if (!Files.isDirectory(profilesRoot)) {
                continue;
            }
            try (var profileDirs = Files.newDirectoryStream(profilesRoot)) {
                for (Path profileDir : profileDirs) {
                    if (!Files.isDirectory(profileDir)) {
                        continue;
                    }
                    order.add(profileDir.resolve("logs").resolve("latest.log").toAbsolutePath().normalize());
                    try (var versionDirs = Files.newDirectoryStream(profileDir)) {
                        for (Path versionDir : versionDirs) {
                            if (!Files.isDirectory(versionDir)) {
                                continue;
                            }
                            order.add(versionDir.resolve("logs").resolve("latest.log").toAbsolutePath().normalize());
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isPreferredLunarLogPath(Path path) {
        return path != null && path.toString().toLowerCase(Locale.ROOT).contains(".lunarclient");
    }

    private static List<Path> latestLogPathCandidates(MinecraftClient client) {
        Path vanilla = client.runDirectory.toPath().resolve("logs").resolve("latest.log").toAbsolutePath().normalize();
        LinkedHashSet<Path> order = new LinkedHashSet<>(8);
        if (LauncherCompat.isLunarClient(client)) {
            appendLunarMultiverLatestLogs(order);
            appendLunarProfileLatestLogs(order);
            order.add(vanilla);
        } else {
            order.add(vanilla);
            appendLunarMultiverLatestLogs(order);
            appendLunarProfileLatestLogs(order);
        }
        return new ArrayList<>(order);
    }

    /**
     * Fichier {@code latest.log} pour la partie HUD lue depuis les logs uniquement :
     * instance courante, ou copie Lunar multiver si détecté / en secours.
     */
    private static Path resolveLatestLogPath(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (cachedHudLatestLogPath != null && now - lastLogPathRefreshWallMs < LOG_PATH_REFRESH_INTERVAL_MS) {
            try {
                if (Files.isRegularFile(cachedHudLatestLogPath) && Files.isReadable(cachedHudLatestLogPath)) {
                    return cachedHudLatestLogPath;
                }
            } catch (Exception ignored) {
            }
            cachedHudLatestLogPath = null;
        }
        lastLogPathRefreshWallMs = now;
        Path best = null;
        long bestLastModified = Long.MIN_VALUE;
        boolean bestPreferred = false;
        for (Path p : latestLogPathCandidates(client)) {
            try {
                if (Files.isRegularFile(p) && Files.isReadable(p)) {
                    long modified = Files.getLastModifiedTime(p).toMillis();
                    boolean preferred = isPreferredLunarLogPath(p);
                    if (best == null
                            || modified > bestLastModified
                            || (modified == bestLastModified && preferred && !bestPreferred)) {
                        best = p;
                        bestLastModified = modified;
                        bestPreferred = preferred;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (best != null) {
            if (cachedHudLatestLogPath == null || !cachedHudLatestLogPath.equals(best)) {
                latestLogSourceAnnounced = false;
            }
            cachedHudLatestLogPath = best;
            return best;
        }
        return vanillaLatestLogPath(client);
    }

    private static Path vanillaLatestLogPath(MinecraftClient client) {
        return client.runDirectory.toPath().resolve("logs").resolve("latest.log").toAbsolutePath().normalize();
    }

    /** Incrémente la lecture de {@code logs/latest.log} (récompenses, AFK, événements, prestige, etc.). */
    private static void updateEventsFromLogs(MinecraftClient client) {
        long wall = System.currentTimeMillis();
        if (wall - lastLogPollWallMs < LOG_POLL_INTERVAL_MS) {
            return;
        }
        lastLogPollWallMs = wall;
        Path logPath = resolveLatestLogPath(client);
        if (!Files.exists(logPath)) {
            if (!latestLogMissingWarned) {
                latestLogMissingWarned = true;
                LOGGER.warn(
                        "[FeatherWorldMenu] Fichier latest.log introuvable — récompenses / AFK / événements (logs) inactifs. Chemin attendu : {}",
                        logPath);
            }
            return;
        }
        if (!Files.isReadable(logPath)) {
            if (!latestLogReadErrorWarned) {
                latestLogReadErrorWarned = true;
                LOGGER.warn("[FeatherWorldMenu] latest.log existe mais n’est pas lisible : {}", logPath);
            }
            return;
        }
        latestLogMissingWarned = false;
        try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
            if (!latestLogSourceAnnounced) {
                latestLogSourceAnnounced = true;
                LOGGER.info("[FeatherWorldMenu] Suivi HUD : lecture incrémentale de {}", logPath);
            }
            long length = file.length();
            boolean logPathChanged = logReadOffsetPath == null || !logReadOffsetPath.equals(logPath);
            if (logPathChanged) {
                logReadOffsetPath = logPath;
                logReadOffset = length;
            } else if (logReadOffset > length) {
                logReadOffset = length;
            }
            file.seek(logReadOffset);
            int processed = 0;
            int maxLinesPerPoll = LauncherCompat.isLunarClient(client) ? MAX_LOG_LINES_PER_POLL_LUNAR : MAX_LOG_LINES_PER_POLL;
            while (processed < maxLinesPerPoll) {
                String line = readUtf8Line(file);
                if (line == null) {
                    break;
                }
                processed++;
                processLogLine(line);
            }
            logReadOffset = file.getFilePointer();
            latestLogReadErrorWarned = false;
        } catch (IOException e) {
            if (!latestLogReadErrorWarned) {
                latestLogReadErrorWarned = true;
                LOGGER.warn("[FeatherWorldMenu] Erreur lors de la lecture de latest.log ({}) : {}", logPath, e.toString());
            }
        }
    }

    private static void processLogLine(String raw) {
        parseFinEvenementDoubleFromLog(raw);
        parseDoubleEventFromLog(raw);
        parseFarmConcoursFromLog(raw);
        parseAfkStateFromLog(raw);
        parsePrestigeFromLog(raw);
        parseEnchantOptionExactLogLine(raw);
        parseAutoFishLogTriggerFromLog(raw);
        String content = rewardLineContent(raw);
        if (!content.isBlank()) {
            tryParseRewardsFromLogLine(content, raw, System.currentTimeMillis());
        }
    }

    private static void tickAutoFishScheduledSecondUse(MinecraftClient client) {
        if (autoFishSecondUseDueAtMs <= 0L) {
            return;
        }
        if (System.currentTimeMillis() < autoFishSecondUseDueAtMs) {
            return;
        }
        autoFishSecondUseDueAtMs = 0L;
        performAutoFishUseInteraction(client);
    }

    private static void performAutoFishUseInteraction(MinecraftClient client) {
        if (client == null || client.player == null || client.interactionManager == null || client.world == null) {
            return;
        }
        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
    }

    private static boolean logLineMatchesAutoFishTrigger(String raw) {
        if (raw == null || AUTOFISH_LOG_TRIGGER_PREFIX.isEmpty()) {
            return false;
        }
        String stripped = stripLogPrefix(raw).trim();
        if (stripped.startsWith(AUTOFISH_LOG_TRIGGER_PREFIX)) {
            return true;
        }
        String alt = stripForRecapFarm(raw).trim();
        if (alt.startsWith(AUTOFISH_LOG_TRIGGER_PREFIX)) {
            return true;
        }
        if (stripped.contains(AUTOFISH_LOG_TRIGGER_PREFIX) || alt.contains(AUTOFISH_LOG_TRIGGER_PREFIX)) {
            return true;
        }
        return raw.contains(AUTOFISH_LOG_TRIGGER_PREFIX);
    }

    /**
     * Déclenché sur une ligne {@code latest.log} (vanilla ou Lunar) : premier « utiliser » tout de suite,
     * second après {@link #AUTOFISH_SECOND_USE_DELAY_MS} ms (équivalent double-clic droit).
     */
    private static void parseAutoFishLogTriggerFromLog(String raw) {
        if (!autoFishLogTriggerEnabled || !logLineMatchesAutoFishTrigger(raw)) {
            return;
        }
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null || c.interactionManager == null || c.world == null) {
            return;
        }
        performAutoFishUseInteraction(c);
        autoFishSecondUseDueAtMs = System.currentTimeMillis() + AUTOFISH_SECOND_USE_DELAY_MS;
    }

    private static void parseEnchantOptionExactLogLine(String raw) {
        String payload = extractSystemChatPayload(raw);
        if (payload.isEmpty()) {
            return;
        }
        String core = stripLeadingChatNoise(payload);
        if (core.isEmpty()) {
            return;
        }
        EnchantOptionMatch match = resolveEnchantOptionMatch(core);
        if (match == null) {
            return;
        }
        EnchantOptionSet optionSet = match.optionSet();
        int matchedIndex = match.optionIndex();
        String[] labels = optionSet.labels();
        int[] logCounts = optionSet.logCounts();
        double[] logSums = optionSet.logSums();
        String fp = normalizeAsciiLower(raw != null ? raw.trim() : payload);
        if (!rememberEnchantOptionLogLineIfNew(fp)) {
            return;
        }
        logCounts[matchedIndex]++;
        final int sumIdx = matchedIndex;
        String tail = core.substring(match.contentStart()).trim();
        Map<String, Double> sumsByType = optionSet.logSumsByType()[sumIdx];
        RewardDisplayZone labelZone = rewardZoneForEnchantOptionSet(optionSet);
        List<EnchantCumulValue> values = extractEnchantCumulValuesFromText(tail, labels[sumIdx]);
        if (values.isEmpty()) {
            values = extractEnchantCumulValuesFromText(core, labels[sumIdx]);
        }
        if (values.isEmpty()) {
            String alt = stripLeadingChatNoise(rewardLineContent(raw));
            if (!alt.isBlank() && !alt.equals(core)) {
                for (String exactLabel : enchantPanelDetectableLabels(labels[sumIdx], labelZone)) {
                    int altStart = enchantLabelContentStart(alt, exactLabel);
                    if (altStart < 0) {
                        continue;
                    }
                    values = extractEnchantCumulValuesFromText(alt.substring(altStart).trim(), labels[sumIdx]);
                    if (!values.isEmpty()) {
                        break;
                    }
                }
            }
        }
        for (EnchantCumulValue cv : values) {
            String typeKey = enchantCumulCanonicalTypeKey(cv.typeWord());
            sumsByType.merge(typeKey, cv.amount(), Double::sum);
            logSums[sumIdx] += cv.amount();
        }
    }

    /** Lac / Mine / Ferme : cherche le bon enchant ; noms partagés (expérience, thor…) → zone scoreboard actuelle. */
    private static EnchantOptionMatch resolveEnchantOptionMatch(String core) {
        List<EnchantOptionMatch> matches = new ArrayList<>();
        for (EnchantOptionSet optionSet : ENCHANT_OPTION_SETS) {
            EnchantOptionMatch m = findEnchantOptionMatchInSet(core, optionSet);
            if (m != null) {
                matches.add(m);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        EnchantOptionSet current = currentEnchantOptionSet();
        if (current != null) {
            for (EnchantOptionMatch m : matches) {
                if (m.optionSet() == current) {
                    return m;
                }
            }
        }
        return matches.get(0);
    }

    private static EnchantOptionMatch findEnchantOptionMatchInSet(String core, EnchantOptionSet optionSet) {
        RewardDisplayZone zone = rewardZoneForEnchantOptionSet(optionSet);
        String[] labels = optionSet.labels();
        for (int i = 0; i < labels.length; i++) {
            for (String exactLabel : enchantPanelDetectableLabels(labels[i], zone)) {
                int contentStart = enchantLabelContentStart(core, exactLabel);
                if (contentStart < 0) {
                    continue;
                }
                return new EnchantOptionMatch(optionSet, i, exactLabel, contentStart);
            }
        }
        return null;
    }

    /** Fin du nom d’enchant en tête de ligne (gère accents / casse). */
    private static int enchantLabelContentStart(String core, String label) {
        if (core == null || label == null || label.isEmpty() || core.length() < label.length()) {
            return -1;
        }
        Matcher head = Pattern.compile("^" + Pattern.quote(label), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(core);
        if (!head.find()) {
            return -1;
        }
        int after = head.end();
        if (after < core.length() && Character.isLetterOrDigit(core.charAt(after))) {
            return -1;
        }
        return after;
    }

    private static RewardDisplayZone rewardZoneForEnchantOptionSet(EnchantOptionSet optionSet) {
        return switch (optionSet.configKey()) {
            case "lac" -> RewardDisplayZone.LAC;
            case "mine" -> RewardDisplayZone.MINE;
            case "farm" -> RewardDisplayZone.CHAMP;
            default -> rewardDisplayZone;
        };
    }

    /** Cumul Enchant : uniquement « +nombre type » dans les crochets {@code [...]}. */
    private static final String ENCHANT_CUMUL_NUM_BODY = "(?:\\d+[\\.,]\\d*|[\\.,]\\d+|\\d+)(?:(?:Se|Qu)|[KMBTQS])?";
    private static final Pattern ENCHANT_CUMUL_PLUS_TYPE_PATTERN = Pattern.compile(
            "[+＋](" + ENCHANT_CUMUL_NUM_BODY + ")\\s*([\\p{L}][\\p{L}\\p{Nd}]*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ENCHANT_CUMUL_BRACKET_BLOCK_PATTERN = Pattern.compile(
            "[\\[\\uFF3B]([^\\]\\uFF3D]+)[\\]\\uFF3D]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Cumuls uniquement dans les crochets : {@code [+49.49M perles +725.47K exp]} (avec {@code +} obligatoire).
     */
    private static List<EnchantCumulValue> extractEnchantCumulValuesFromText(String text, String enchantRawLabel) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<EnchantCumulValue> found = new ArrayList<>();
        Matcher bracketBlock = ENCHANT_CUMUL_BRACKET_BLOCK_PATTERN.matcher(text);
        while (bracketBlock.find()) {
            collectEnchantCumulBracketMatches(bracketBlock.group(1), found);
        }
        return found;
    }

    private static void collectEnchantCumulBracketMatches(String bracketInner, List<EnchantCumulValue> out) {
        Matcher m = ENCHANT_CUMUL_PLUS_TYPE_PATTERN.matcher(bracketInner);
        while (m.find()) {
            enchantCumulFromGroups(m.group(1), m.group(2)).ifPresent(out::add);
        }
    }

    private static Optional<EnchantCumulValue> enchantCumulFromGroups(String numGroup, String typeGroup) {
        String typeWord = normalizeAsciiLower(typeGroup);
        if (typeWord.isEmpty() || typeWord.length() < 2 || isEnchantCumulCompactSuffixWord(typeWord)) {
            return Optional.empty();
        }
        return parseCompactNumber("+" + numGroup).map(v -> new EnchantCumulValue(v, typeWord));
    }

    private static boolean isEnchantCumulCompactSuffixWord(String typeWordNorm) {
        return typeWordNorm.length() == 1 && "kmbtqs".indexOf(typeWordNorm.charAt(0)) >= 0;
    }

    private static List<String> enchantValueKeywordsForLabel(String rawLabel) {
        String n = normalizeAsciiLower(enchantPanelDisplayLabel(rawLabel));
        if (n.contains("experience")) {
            return List.of("exp", "xp", "experience");
        }
        if (n.contains("tresor")) {
            return List.of("money", "argent", "tresor");
        }
        if (n.contains("multi") && n.contains("orbe")) {
            return List.of("orbes", "orbe", "exp", "xp", "experience");
        }
        if (n.contains("orbe")) {
            return List.of("orbes", "orbe");
        }
        if (n.contains("perle")) {
            return List.of("perles", "perle");
        }
        if (n.contains("poisson")) {
            return List.of("poissons", "poisson");
        }
        if (n.contains("culture")) {
            return List.of("cultures", "culture");
        }
        if (n.contains("bloc")) {
            return List.of("blocs", "bloc");
        }
        if (n.contains("gemme") || n.contains("gennes")) {
            return List.of("gemmes", "gemme", "gennes");
        }
        if (n.contains("lostcoin")) {
            return List.of("lostcoins", "lostcoin");
        }
        if (n.contains("braquage") || n.contains("antiquitaire")) {
            return List.of("money", "argent", "tresor");
        }
        if (n.contains("filet") || n.contains("poisson") || n.contains("tsunami") || n.contains("dragon")
                || n.contains("megalodon") || n.contains("poseidon")) {
            return List.of("poissons", "poisson", "perles", "perle", "exp", "xp", "experience");
        }
        if (n.contains("foreuse") || n.contains("stomp") || n.contains("thor") || n.contains("golem")
                || n.contains("nain") || n.contains("liens") || n.contains("tacos") || n.contains("lance")
                || n.contains("trou noir") || n.contains("pluie")) {
            return List.of("blocs", "bloc", "gemmes", "gemme", "exp", "xp", "experience");
        }
        if (n.contains("ferme en folie") || n.contains("pyrobarbare") || n.contains("asteroide")
                || n.contains("clone") || n.contains("ange") || n.contains("ambidextre") || n.contains("warden")
                || n.contains("kung")) {
            return List.of("cultures", "culture", "orbes", "orbe", "exp", "xp", "experience", "money", "argent");
        }
        if (n.contains("ferme") || n.contains("culture") || n.contains("engrais") || n.contains("essaim")) {
            return List.of("cultures", "culture", "exp", "xp", "experience");
        }
        if (n.contains("boomeur") || n.contains("laser") || n.contains("avalanche") || n.contains("flash")
                || n.contains("apocalypse") || n.contains("peste")) {
            return List.of("exp", "money", "gemmes", "blocs", "cultures", "orbes");
        }
        return List.of();
    }

    private static String enchantCumulCanonicalTypeKey(String typeWord) {
        String n = normalizeAsciiLower(typeWord == null ? "" : typeWord);
        if (n.startsWith("exp") || n.equals("xp") || n.contains("experience")) {
            return "exp";
        }
        if (n.startsWith("perle")) {
            return "perles";
        }
        if (n.startsWith("orbe")) {
            return "orbes";
        }
        if (n.startsWith("poisson")) {
            return "poissons";
        }
        if (n.startsWith("argent") || n.equals("money") || n.startsWith("tresor")) {
            return "money";
        }
        if (n.startsWith("culture")) {
            return "cultures";
        }
        if (n.startsWith("bloc")) {
            return "blocs";
        }
        if (n.startsWith("gemme") || n.startsWith("genn")) {
            return "gemmes";
        }
        if (n.startsWith("lostcoin")) {
            return "lostcoins";
        }
        return n;
    }

    /** Libellé court du type de gain pour l’affichage cumul (repli si map vide). */
    private static String enchantCumulDisplayTypeForLabel(String rawLabel) {
        List<String> keywords = enchantValueKeywordsForLabel(rawLabel);
        return keywords.isEmpty() ? "" : keywords.get(0);
    }

    /** Un montant cumul affiché : {@code +49.49M perles}. */
    private static String formatEnchantCumulAmountWithType(double amount, String typeKey) {
        String signed = formatSignedCompact(amount);
        if (typeKey == null || typeKey.isBlank()) {
            return signed;
        }
        return signed + " " + typeKey;
    }

    /** Suffixe cumul HUD : {@code +49.49M perles +725.47K exp} (sans crochets). */
    private static String formatEnchantCumulDisplaySuffix(Map<String, Double> sumsByType, double logSumFallback,
            String rawLabel) {
        if (sumsByType != null && !sumsByType.isEmpty()) {
            List<Map.Entry<String, Double>> entries = new ArrayList<>(sumsByType.entrySet());
            entries.removeIf(e -> Math.abs(e.getValue()) <= 1e-9);
            if (!entries.isEmpty()) {
                entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Double> e : entries) {
                    sb.append(' ').append(formatEnchantCumulAmountWithType(e.getValue(), e.getKey()));
                }
                return sb.toString();
            }
        }
        if (Math.abs(logSumFallback) <= 1e-9) {
            return "";
        }
        String type = enchantCumulDisplayTypeForLabel(rawLabel);
        return " " + formatEnchantCumulAmountWithType(logSumFallback, type);
    }

    /**
     * En AFK les recaps « | + » vont dans {@link #AFK_REWARDS} (hors Money/Exp) et dans les trackers AFK Money/Exp par zone ;
     * hors AFK : {@link #REWARDS} et Money/Exp par zone Reward.
     */
    private static void parseAfkStateFromLog(String raw) {
        String s = stripLogPrefix(raw);
        if (s.isBlank()) {
            s = stripForRecapFarm(raw);
        }
        String t = s.trim();
        String norm = normalizeAsciiLower(t);
        if (AFK_ON_PATTERN.matcher(norm).find() || t.equals(AFK_LOG_ON_EXACT)) {
            if (afkWorldEntryProbeActive) {
                afkWorldEntryProbeResolved = true;
            }
            if (reconnectAfkCommandWatchActive) {
                reconnectAfkCommandWatchActive = false;
            }
            if (prestigeRunActive && prestigePaused) {
                if (prestigeAutoResumeOnAfkReconnect) {
                    resumePrestigeAfterAfkReconnect();
                } else {
                    resumePrestigeIfPaused();
                }
            }
            if (!afkRewardLoggingActive) {
                afkAwaitingLocalActivityClearAfterReconnect = false;
                reconnectAfkActivityBaseline = null;
                afkRewardLoggingActive = true;
                // Auto-affiche la section AFK si elle était masquée ; on mémorise l’état actuel
                // pour pouvoir le restaurer à la sortie AFK.
                if (!afkAutoShownActive) {
                    showHudAfkSavedBeforeAfk = showHudAfk;
                    afkAutoShownActive = true;
                }
                if (!showHudAfk) {
                    showHudAfk = true;
                }
            } else if (afkAwaitingLocalActivityClearAfterReconnect) {
                afkAwaitingLocalActivityClearAfterReconnect = false;
                reconnectAfkActivityBaseline = null;
            }
        } else if (AFK_OFF_PATTERN.matcher(norm).find() || t.equals(AFK_LOG_OFF_EXACT)) {
            if (afkWorldEntryProbeActive) {
                afkWorldEntryProbeResolved = true;
            }
            if (afkRewardLoggingActive) {
                clearAfkLoggingClientStateAsIfLeft();
            }
        }
    }

    private static String rewardLineContent(String raw) {
        if (raw == null) {
            return "";
        }
        String s = stripLogPrefix(raw);
        if (s.isBlank()) {
            s = stripForRecapFarm(raw);
        }
        return s;
    }

    /**
     * Recap « | + » + montant + type : hors AFK → trackers Reward (Money/Exp par zone monde) ; en AFK → trackers AFK par zone.
     * {@code rawLogLine} sert à l’empreinte anti-doublon : même texte recap à deux horodatages = deux gains (ex. +1 puis +1).
     */
    private static void tryParseRewardsFromLogLine(String text, String rawLogLine, long now) {
        if (text == null || text.isBlank()) {
            return;
        }
        String ascii = normalizeAsciiLower(text).trim();
        if (!REWARD_PIPE_PLUS.matcher(text).find()) {
            return;
        }
        String fpSource = rawLogLine != null && !rawLogLine.isBlank() ? rawLogLine : text;
        String fp = normalizeAsciiLower(fpSource).trim();
        boolean applyReward;
        boolean applyAfk;
        if (afkRewardLoggingActive) {
            applyReward = false;
            applyAfk = rememberAfkRewardLineIfNew(fp);
        } else {
            applyReward = rememberRewardLineIfNew(fp);
            applyAfk = false;
        }
        if (!applyReward && !applyAfk) {
            return;
        }
        RewardDisplayZone recapZone = zoneForIncomingRewardRecap();
        /* Pulse chrono farm dès qu’une ligne recap est acceptée : même si aucun montant n’est extrait, le timer doit repartir après une pause. */
        if (applyReward) {
            noteRewardFarmSessionPulse(recapZone, now);
        }
        if (applyAfk) {
            noteAfkFarmSessionPulse(recapZone, now);
        }
        for (Map.Entry<String, RewardTracker> e : REWARDS.entrySet()) {
            RewardTracker rw = e.getValue();
            if (!recapLineMatchesReward(ascii, rw)) {
                continue;
            }
            Optional<Double> val = extractRewardNumberForLogLine(text, rw);
            if (val.isEmpty()) {
                continue;
            }
            double v = val.get();
            String key = e.getKey();
            if (applyReward) {
                if ("money".equals(key)) {
                    rewardMoneyByZone.get(recapZone).recordValue(v, now);
                } else if ("exp".equals(key)) {
                    rewardExpByZone.get(recapZone).recordValue(v, now);
                } else {
                    rw.recordValue(v, now);
                }
            }
            if (applyAfk) {
                if ("money".equals(key)) {
                    afkMoneyByZone.get(recapZone).recordValue(v, now);
                } else if ("exp".equals(key)) {
                    afkExpByZone.get(recapZone).recordValue(v, now);
                } else {
                    AFK_REWARDS.get(key).recordValue(v, now);
                }
            }
        }
    }

    /** Zone scoreboard pour classer un gain Money/Exp (Reward vs AFK selon l’état courant). */
    private static RewardDisplayZone zoneForIncomingRewardRecap() {
        return afkRewardLoggingActive ? afkDisplayZone : rewardDisplayZone;
    }

    /**
     * Tracker affiché dans le HUD : Money et Exp sont par {@code displayZone} (Lac / Mine / Champ) ;
     * les autres ressources restent globales dans {@link #REWARDS} / {@link #AFK_REWARDS}.
     */
    private static RewardTracker rewardHudTracker(String rk, RewardDisplayZone displayZone, boolean afkSection) {
        if ("money".equals(rk)) {
            return afkSection ? afkMoneyByZone.get(displayZone) : rewardMoneyByZone.get(displayZone);
        }
        if ("exp".equals(rk)) {
            return afkSection ? afkExpByZone.get(displayZone) : rewardExpByZone.get(displayZone);
        }
        return afkSection ? AFK_REWARDS.get(rk) : REWARDS.get(rk);
    }

    private static void noteFarmSessionPulse(
            EnumMap<RewardDisplayZone, RewardFarmSessionState> sessionsByZone,
            RewardDisplayZone zone,
            long now,
            long silenceMs,
            long firstRecapOffsetMs,
            long resumeAfterSilenceBonusMs) {
        RewardFarmSessionState st = sessionsByZone.computeIfAbsent(zone, z -> new RewardFarmSessionState());
        if (st.sessionStartMs == 0L || st.lastRecapMs == 0L) {
            st.sessionStartMs = now - firstRecapOffsetMs;
            st.lastRecapMs = now;
            return;
        }
        long prevLast = st.lastRecapMs;
        long gap = now - prevLast;
        long frozen = prevLast - st.sessionStartMs;
        st.lastRecapMs = now;
        if (gap > silenceMs) {
            st.sessionStartMs = now - Math.max(0L, frozen + resumeAfterSilenceBonusMs);
        }
    }

    private static long farmSessionElapsedMsForDisplayZone(
            EnumMap<RewardDisplayZone, RewardFarmSessionState> sessionsByZone,
            RewardDisplayZone zone,
            long now,
            long silenceMs) {
        RewardFarmSessionState st = sessionsByZone.get(zone);
        if (st == null || st.sessionStartMs == 0L || st.lastRecapMs == 0L) {
            return 0L;
        }
        long idle = now - st.lastRecapMs;
        if (idle > silenceMs) {
            return Math.max(0L, st.lastRecapMs - st.sessionStartMs);
        }
        return Math.max(0L, now - st.sessionStartMs);
    }

    private static long afkFarmSilenceMsForZone(RewardDisplayZone zone) {
        return zone == RewardDisplayZone.LAC ? AFK_FARM_SILENCE_LAC_MS : AFK_FARM_SILENCE_FIELD_MS;
    }

    private static void noteRewardFarmSessionPulse(RewardDisplayZone zone, long now) {
        noteFarmSessionPulse(rewardFarmSessionByZone, zone, now,
                REWARD_FARM_SILENCE_MS, REWARD_FARM_FIRST_RECAP_OFFSET_MS, REWARD_FARM_RESUME_AFTER_SILENCE_BONUS_MS);
    }

    private static void noteAfkFarmSessionPulse(RewardDisplayZone zone, long now) {
        noteFarmSessionPulse(afkFarmSessionByZone, zone, now,
                afkFarmSilenceMsForZone(zone), AFK_FARM_FIRST_RECAP_OFFSET_MS, AFK_FARM_RESUME_AFTER_SILENCE_BONUS_MS);
    }

    /**
     * Temps affiché Reward : avance jusqu’à {@value #REWARD_FARM_SILENCE_MS} ms après le dernier recap,
     * puis s’arrête ; recap suivant : cumul + {@value #REWARD_FARM_RESUME_AFTER_SILENCE_BONUS_MS} ms puis temps vivant.
     * Premier recap : départ à {@value #REWARD_FARM_FIRST_RECAP_OFFSET_MS} ms.
     */
    private static long rewardFarmSessionElapsedMsForDisplayZone(RewardDisplayZone zone, long now) {
        return farmSessionElapsedMsForDisplayZone(rewardFarmSessionByZone, zone, now, REWARD_FARM_SILENCE_MS);
    }

    /**
     * Figé le chrono farm AFK de chaque zone à la sortie AFK (sans vider la map) :
     * l’affichage reste sur la dernière valeur tant que {@link #afkRewardLoggingActive} est faux.
     */
    private static void pauseAfkFarmSessionsAtLeave(long now) {
        for (Map.Entry<RewardDisplayZone, RewardFarmSessionState> e : afkFarmSessionByZone.entrySet()) {
            RewardFarmSessionState st = e.getValue();
            if (st.sessionStartMs == 0L || st.lastRecapMs == 0L) {
                continue;
            }
            RewardDisplayZone zone = e.getKey();
            long elapsed = farmSessionElapsedMsForDisplayZone(
                    afkFarmSessionByZone, zone, now, afkFarmSilenceMsForZone(zone));
            st.sessionStartMs = now - elapsed;
            st.lastRecapMs = now;
        }
    }

    /** AFK : seuil silence selon Lac vs Champ/Mine ; hors AFK le temps affiché ne avance plus. */
    private static long afkFarmSessionElapsedMsForDisplayZone(RewardDisplayZone zone, long now) {
        if (!afkRewardLoggingActive) {
            RewardFarmSessionState st = afkFarmSessionByZone.get(zone);
            if (st == null || st.sessionStartMs == 0L || st.lastRecapMs == 0L) {
                return 0L;
            }
            return Math.max(0L, st.lastRecapMs - st.sessionStartMs);
        }
        return farmSessionElapsedMsForDisplayZone(afkFarmSessionByZone, zone, now, afkFarmSilenceMsForZone(zone));
    }

    /** Libellé HUD + synonymes serveur (fautes, singulier, argot). */
    private static boolean recapLineMatchesReward(String asciiLower, RewardTracker rw) {
        if (wordBoundaryMatch(asciiLower, normalizeAsciiLower(rw.label))) {
            return true;
        }
        return switch (rw.label) {
            case "Gemmes" -> wordBoundaryMatch(asciiLower, "gennes") || wordBoundaryMatch(asciiLower, "gemme");
            case "Money" -> wordBoundaryMatch(asciiLower, "argent");
            case "Exp" -> wordBoundaryMatch(asciiLower, "xp") || wordBoundaryMatch(asciiLower, "experience");
            case "Blocs" -> wordBoundaryMatch(asciiLower, "bloc");
            case "Orbes" -> wordBoundaryMatch(asciiLower, "orbe");
            case "Perles" -> wordBoundaryMatch(asciiLower, "perle");
            case "Poissons" -> wordBoundaryMatch(asciiLower, "poisson");
            case "Cultures" -> wordBoundaryMatch(asciiLower, "culture");
            default -> false;
        };
    }

    /**
     * Essaie plusieurs libellés (singulier / pluriel / fautes serveur) pour parser le montant à côté du type de reward.
     */
    private static Optional<Double> extractRewardNumberForLogLine(String line, RewardTracker rw) {
        return switch (rw.label) {
            case "Gemmes" -> firstExtractRewardNumber(line, "Gemmes", "gemmes", "Gennes", "gennes", "gemme");
            case "Money" -> firstExtractRewardNumber(line, "Money", "money", "argent");
            case "Exp" -> firstExtractRewardNumber(line, rw.label, "xp", "XP", "experience");
            case "Blocs" -> firstExtractRewardNumber(line, "Blocs", "blocs", "bloc");
            case "Orbes" -> firstExtractRewardNumber(line, "Orbes", "orbes", "orbe");
            case "Perles" -> firstExtractRewardNumber(line, "Perles", "perles", "perle");
            case "Poissons" -> firstExtractRewardNumber(line, "Poissons", "poissons", "poisson");
            case "Cultures" -> firstExtractRewardNumber(line, "Cultures", "cultures", "culture");
            default -> extractRewardNumber(line, rw.label);
        };
    }

    private static Optional<Double> firstExtractRewardNumber(String line, String... labels) {
        for (String label : labels) {
            Optional<Double> v = extractRewardNumber(line, label);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    private static Optional<Double> extractRewardNumber(String line, String label) {
        Optional<Double> v = numberAfterLabel(line, label);
        if (v.isPresent()) {
            return v;
        }
        return numberBeforeLabel(line, label);
    }

    /** Montant compact : suffixes K → M → B → T → Q → Qu → S → Se (10³ … 10²⁴ ; regex : Se/Qu avant S/Q). */
    private static final String REWARD_NUM_CAPTURE = "[+\\-]?(?:\\d+[\\.,]\\d*|[\\.,]\\d+|\\d+)(?:\\s*(?:Se|Qu|S|[KMBTQ]))?";

    private static Optional<Double> numberAfterLabel(String line, String label) {
        String quoted = Pattern.quote(label);
        Pattern p = Pattern.compile(quoted + "\\s*[:=\\s\\-–—·|]*(" + REWARD_NUM_CAPTURE + ")", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(line);
        if (m.find()) {
            return parseCompactNumber(m.group(1));
        }
        return Optional.empty();
    }

    private static Optional<Double> numberBeforeLabel(String line, String label) {
        String quoted = Pattern.quote(label);
        Pattern withSep = Pattern.compile("(" + REWARD_NUM_CAPTURE + ")\\s+[:=\\s\\-–—·|]*" + quoted, Pattern.CASE_INSENSITIVE);
        Matcher m = withSep.matcher(line);
        if (m.find()) {
            return parseCompactNumber(m.group(1));
        }
        Pattern tight = Pattern.compile("(" + REWARD_NUM_CAPTURE + ")\\s+" + quoted, Pattern.CASE_INSENSITIVE);
        m = tight.matcher(line);
        if (m.find()) {
            return parseCompactNumber(m.group(1));
        }
        return Optional.empty();
    }

    private static Optional<Double> parseCompactNumber(String raw) {
        String cleaned = raw.replace("\u00A0", "").replace(" ", "").replace(",", ".").toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        double multiplier = 1.0;
        // Suffixes multi-lettres d’abord (Se vs S, Qu vs Q), puis K → S (même échelle que l’affichage).
        if (cleaned.endsWith("SE")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
            multiplier = 1_000_000_000_000_000_000_000_000D;
        } else if (cleaned.endsWith("QU")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
            multiplier = 1_000_000_000_000_000_000D;
        } else if (!cleaned.isEmpty()) {
            char suffix = cleaned.charAt(cleaned.length() - 1);
            if (suffix == 'K' || suffix == 'M' || suffix == 'B' || suffix == 'T' || suffix == 'Q' || suffix == 'S') {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
                multiplier = switch (suffix) {
                    case 'K' -> 1_000D;
                    case 'M' -> 1_000_000D;
                    case 'B' -> 1_000_000_000D;
                    case 'T' -> 1_000_000_000_000D;
                    case 'Q' -> 1_000_000_000_000_000D;
                    case 'S' -> 1_000_000_000_000_000_000_000D;
                    default -> 1D;
                };
            }
        }
        try {
            return Optional.of(Double.parseDouble(cleaned) * multiplier);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static String formatCompact(double value) {
        double abs = Math.abs(value);
        // Du plus petit au plus grand : sans suffixe, puis K … Se.
        if (abs < 1_000D) {
            if (abs >= 1D || value == 0D) {
                return abs < 0.01D && value != 0D
                        ? String.format(Locale.ROOT, "%.4f", value)
                        : String.format(Locale.ROOT, "%.2f", value);
            }
            return String.format(Locale.ROOT, "%.4f", value);
        }
        if (abs < 1_000_000D) {
            return String.format(Locale.ROOT, "%.2fK", value / 1_000D);
        }
        if (abs < 1_000_000_000D) {
            return String.format(Locale.ROOT, "%.2fM", value / 1_000_000D);
        }
        if (abs < 1_000_000_000_000D) {
            return String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000D);
        }
        if (abs < 1_000_000_000_000_000D) {
            return String.format(Locale.ROOT, "%.2fT", value / 1_000_000_000_000D);
        }
        if (abs < 1_000_000_000_000_000_000D) {
            return String.format(Locale.ROOT, "%.2fQ", value / 1_000_000_000_000_000D);
        }
        if (abs < 1_000_000_000_000_000_000_000D) {
            return String.format(Locale.ROOT, "%.2fQu", value / 1_000_000_000_000_000_000D);
        }
        if (abs < 1_000_000_000_000_000_000_000_000D) {
            return String.format(Locale.ROOT, "%.2fS", value / 1_000_000_000_000_000_000_000D);
        }
        return String.format(Locale.ROOT, "%.2fSe", value / 1_000_000_000_000_000_000_000_000D);
    }

    private static String formatSignedCompact(double value) {
        return (value >= 0 ? "+" : "") + formatCompact(value);
    }

    /**
     * {@link RandomAccessFile#readLine()} est en ISO-8859-1 : casse l’UTF-8 du latest.log (accents, etc.).
     */
    private static String readUtf8Line(RandomAccessFile file) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        while (true) {
            int b = file.read();
            if (b == -1) {
                return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
            }
            if (b == '\n') {
                return buf.toString(StandardCharsets.UTF_8);
            }
            if (b != '\r') {
                buf.write(b);
            }
        }
    }

    private static String stripLogPrefix(String line) {
        if (line == null) {
            return "";
        }
        String s = line.trim();
        s = ANSI_ESCAPE.matcher(s).replaceAll("");
        s = LOG_LINE_PREFIX.matcher(s).replaceFirst("").trim();
        s = CHAT_TAG_PREFIX.matcher(s).replaceFirst("").trim();
        s = LEGACY_FORMATTING_CODES.matcher(s).replaceAll("").trim();
        return s;
    }

    /**
     * Si le préfixe horloge Fabric ne matche pas, retire quand même les segments {@code […]} en tête (chat / wrappers).
     */
    private static String stripForRecapFarm(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        s = ANSI_ESCAPE.matcher(s).replaceAll("");
        s = LEGACY_FORMATTING_CODES.matcher(s).replaceAll("").trim();
        for (int i = 0; i < 8 && !s.isEmpty() && s.charAt(0) == '['; i++) {
            int end = s.indexOf(']');
            if (end < 0) {
                break;
            }
            s = s.substring(end + 1).trim();
        }
        return s;
    }

    private static String normalizeAsciiLower(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static String scoreboardPlain(Text text) {
        if (text == null) {
            return "";
        }
        return LEGACY_FORMATTING_CODES.matcher(text.getString()).replaceAll("");
    }

    /**
     * Texte d’une ligne du sidebar tel qu’affiché : équipe (préfixe / suffixe) + nom, puis score formaté.
     * Sans cela, « MONDE: Lac » peut être uniquement dans l’équipe et absent de {@link ScoreboardEntry#name()}.
     */
    private static String sidebarEntryPlainLine(Scoreboard sb, ScoreboardObjective objective, ScoreboardEntry e) {
        Text base = e.name();
        Team team = sb.getScoreHolderTeam(e.owner());
        MutableText decorated = team != null ? Team.decorateName(team, base) : base.copy();
        String main = scoreboardPlain(decorated).trim();
        String scorePart = scoreboardPlain(
                e.formatted(objective.getNumberFormatOr(BlankNumberFormat.INSTANCE))).trim();
        if (!scorePart.isEmpty()) {
            return (main + " " + scorePart).trim();
        }
        return main;
    }

    /**
     * Détecte Lac / Mine / Ferme (ou Champ) sur une ligne du sidebar déjà normalisée.
     * Le mot peut être seul ou après un libellé (ex. « MONDE: Lac » → le « lac » suffit).
     * Si plusieurs mots zone sont présents : Lac, puis Mine, puis Ferme/Champ.
     */
    private static RewardDisplayZone detectRewardZoneFromScoreboardLine(String lineNormLower) {
        if (SCOREBOARD_ZONE_LAC.matcher(lineNormLower).find()) {
            return RewardDisplayZone.LAC;
        }
        if (SCOREBOARD_ZONE_MINE.matcher(lineNormLower).find()) {
            return RewardDisplayZone.MINE;
        }
        if (SCOREBOARD_ZONE_FERME.matcher(lineNormLower).find() || SCOREBOARD_ZONE_CHAMP.matcher(lineNormLower).find()) {
            return RewardDisplayZone.CHAMP;
        }
        return null;
    }

    /** Même logique que {@link #parseFarmConcoursFromLog} : ligne « concours » + « farm » (ou « concours de farm »). */
    private static boolean scoreboardLineLooksLikeFarmContest(String normLine) {
        return normLine.contains("concours de farm")
                || (normLine.contains("concours") && normLine.contains("farm"));
    }

    private static String farmEventZoneDisplay(RewardDisplayZone z) {
        return switch (z) {
            case CHAMP -> "Champ";
            case MINE -> "Mine";
            case LAC -> "Lac";
        };
    }

    /**
     * Reste affiché sur le sidebar : « 9m30s » ou « 9:30 » (minutes:secondes), plafonné pour éviter les faux positifs.
     */
    private static Long tryParseScoreboardCountdownMs(String normLine) {
        Matcher m = SCOREBOARD_COUNTDOWN_MS.matcher(normLine);
        if (m.find()) {
            int mm = Integer.parseInt(m.group(1));
            int ss = Integer.parseInt(m.group(2));
            if (ss < 60 && mm <= 180) {
                return (mm * 60L + ss) * 1000L;
            }
        }
        m = SCOREBOARD_COUNTDOWN_COLON.matcher(normLine);
        if (m.find()) {
            int mm = Integer.parseInt(m.group(1));
            int ss = Integer.parseInt(m.group(2));
            if (ss < 60 && mm <= 180) {
                return (mm * 60L + ss) * 1000L;
            }
        }
        return null;
    }

    /**
     * Lit le sidebar : zones Reward/AFK (Lac / Mine / Ferme / Champ) et concours farm (zone + temps restant si présent).
     */
    private static void updateFromSidebarScoreboard(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return;
        }
        long t = client.world.getTime();
        if (t % 20L != 0L || t == lastSidebarPollTick) {
            return;
        }
        lastSidebarPollTick = t;
        Scoreboard sb = client.world.getScoreboard();
        ScoreboardObjective sidebar = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return;
        }
        Collection<ScoreboardEntry> entries = sb.getScoreboardEntries(sidebar);
        RewardDisplayZone rewardZoneFound = null;
        RewardDisplayZone rewardZoneOnMondeLine = null;
        String farmZoneFound = null;
        Long farmRemainingMs = null;
        for (ScoreboardEntry e : entries) {
            if (e.hidden()) {
                continue;
            }
            String line = sidebarEntryPlainLine(sb, sidebar, e);
            String normLine = normalizeAsciiLower(line);
            RewardDisplayZone z = detectRewardZoneFromScoreboardLine(normLine);
            if (z != null) {
                if (rewardZoneFound == null) {
                    rewardZoneFound = z;
                }
                if (rewardZoneOnMondeLine == null && normLine.contains("monde")) {
                    rewardZoneOnMondeLine = z;
                }
            }
            if (scoreboardLineLooksLikeFarmContest(normLine)) {
                RewardDisplayZone fz = detectRewardZoneFromScoreboardLine(normLine);
                if (fz != null) {
                    farmZoneFound = farmEventZoneDisplay(fz);
                }
                if (farmRemainingMs == null) {
                    Long rem = tryParseScoreboardCountdownMs(normLine);
                    if (rem != null) {
                        farmRemainingMs = rem;
                    }
                }
            }
        }
        RewardDisplayZone zoneToApply = rewardZoneOnMondeLine != null ? rewardZoneOnMondeLine : rewardZoneFound;
        boolean needSave = false;
        if (zoneToApply != null
                && (rewardDisplayZone != zoneToApply || afkDisplayZone != zoneToApply)) {
            rewardDisplayZone = zoneToApply;
            afkDisplayZone = zoneToApply;
            needSave = true;
        }
        if (farmZoneFound != null && !farmZoneFound.equals(eventZone)) {
            eventZone = farmZoneFound;
        }
        if (farmRemainingMs != null) {
            eventEndMs = System.currentTimeMillis() + farmRemainingMs;
            nextEventEndMs = 0L;
        }
        if (needSave) {
            saveLayout(client);
        }
    }

    /** Normalise pour détecter « Evenement double » / « Événement double » dans les logs. */
    private static String normalizeForDoubleEventLine(String line) {
        return Normalizer.normalize(line, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }

    /** Phrase attendue côté serveur : « Evenement double » (insensible à la casse / accents). */
    private static final String EVENEMENT_DOUBLE_KEY = "EVENEMENT DOUBLE";
    private static final String EVENT_DOUBLE_KEY = "EVENT DOUBLE";

    private static boolean hasWordAsciiUpper(String normalizedUpper, String asciiWordUpper) {
        return Pattern.compile("\\b" + Pattern.quote(asciiWordUpper) + "\\b").matcher(normalizedUpper).find();
    }

    /** « Fin de l'événement double » : termine la phase 5 min, lance le prochain event 15 min, sans boucle 5 min après. */
    private static void parseFinEvenementDoubleFromLog(String raw) {
        String s = stripLogPrefix(raw);
        if (s.isBlank()) {
            s = stripForRecapFarm(raw);
        }
        if (s.isBlank()) {
            return;
        }
        String low = normalizeAsciiLower(s);
        if (!low.contains("fin") || !low.contains("evenement double")) {
            return;
        }
        int finAt = low.indexOf("fin");
        int evAt = low.indexOf("evenement double");
        if (finAt < 0 || evAt < 0 || finAt > evAt) {
            return;
        }
        long now = System.currentTimeMillis();
        doubleEventRestartAfterProchain = false;
        doubleEventEndMs = now;
        doubleProchainEndMs = now + 15L * 60L * 1000L;
    }

    private static boolean isFinEvenementDoubleNormalizedUpper(String n) {
        if (!n.contains("FIN") || !n.contains("EVENEMENT DOUBLE")) {
            return false;
        }
        return n.indexOf("FIN") < n.indexOf("EVENEMENT DOUBLE");
    }

    /**
     * Tags Money / Exp / GENS DROP : uniquement après « Evenement double » dans la portion du log qui suit cette phrase (mêmes noms que les stats).
     */
    private static void parseDoubleEventFromLog(String line) {
        String n = normalizeForDoubleEventLine(line);
        if (isFinEvenementDoubleNormalizedUpper(n)) {
            return;
        }
        int keyAt;
        int keyLen;
        if (n.contains(EVENEMENT_DOUBLE_KEY)) {
            keyAt = n.indexOf(EVENEMENT_DOUBLE_KEY);
            keyLen = EVENEMENT_DOUBLE_KEY.length();
        } else if (n.contains(EVENT_DOUBLE_KEY)) {
            keyAt = n.indexOf(EVENT_DOUBLE_KEY);
            keyLen = EVENT_DOUBLE_KEY.length();
        } else {
            return;
        }
        String tail = n.substring(keyAt + keyLen);

        doubleMoneyActive = hasWordAsciiUpper(tail, STATS.get("money").label().toUpperCase(Locale.ROOT));
        doubleExpActive = hasWordAsciiUpper(tail, STATS.get("exp").label().toUpperCase(Locale.ROOT));
        doubleGensDropActive = Pattern.compile("GENS\\s+DROP").matcher(tail).find();

        long now = System.currentTimeMillis();
        doubleEventRestartAfterProchain = true;
        doubleEventEndMs = now + 5L * 60L * 1000L;
        doubleProchainEndMs = 0L;
    }

    private static void updateDoubleEventTimers() {
        long now = System.currentTimeMillis();
        if (doubleProchainEndMs > 0L && now >= doubleProchainEndMs) {
            doubleProchainEndMs = 0L;
            if (doubleEventRestartAfterProchain) {
                doubleEventEndMs = now + 5L * 60L * 1000L;
            } else {
                doubleEventEndMs = 0L;
                doubleMoneyActive = false;
                doubleExpActive = false;
                doubleGensDropActive = false;
            }
            return;
        }
        if (doubleEventEndMs > 0L && now >= doubleEventEndMs && doubleProchainEndMs == 0L) {
            doubleProchainEndMs = now + 15L * 60L * 1000L;
        }
    }

    /**
     * Compteur « event double » : 5 min → {@code 00m00s}, puis « prochain event » 15 min → {@code 00m00s}.
     * Si le serveur n’a pas envoyé « Fin de l'événement double », un nouveau 5 min démarre ; sinon tout s’arrête après le prochain event.
     */
    private static String formatDoubleEventMainTimer(long now) {
        if (doubleEventEndMs == 0L && doubleProchainEndMs == 0L) {
            return "--";
        }
        if (doubleEventEndMs > 0L && now < doubleEventEndMs) {
            return formatEventRemaining(doubleEventEndMs);
        }
        return "00m00s";
    }

    private static void parseFarmConcoursFromLog(String line) {
        if (!line.contains("Le Concours de Farm")) {
            return;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        eventEndMs = System.currentTimeMillis() + 10L * 60L * 1000L;
        nextEventEndMs = 0L;
        if (lower.contains("champ")) {
            eventZone = "Champ";
        } else if (lower.contains("mine")) {
            eventZone = "Mine";
        } else if (lower.contains("lac")) {
            eventZone = "Lac";
        } else {
            eventZone = "--";
        }
    }

    private static final Pattern POTION_PERCENT_PATTERN = Pattern.compile(
            "([+\\-]?\\d+(?:[\\.,]\\d+)?)\\s*(?:%|pour\\s*cent|\\bpct\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern POTION_DURATION_MMSS = Pattern.compile("(\\d+)\\s*m\\s*(\\d+)\\s*s", Pattern.CASE_INSENSITIVE);
    /** Ex. « 5m30 » sans « s » final. */
    private static final Pattern POTION_DURATION_M_MIN = Pattern.compile("(\\d+)\\s*m\\s*(\\d{1,2})\\b(?!\\s*%)", Pattern.CASE_INSENSITIVE);
    private static final Pattern POTION_DURATION_COLON = Pattern.compile("(\\d+)\\s*:\\s*(\\d{2})");
    private static final Pattern POTION_DURATION_SEC = Pattern.compile("(\\d+)\\s*s(?:ec(?:onde)?s?)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POTION_DURATION_MIN = Pattern.compile("(\\d+)\\s*min(?:ute)?s?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POTION_DURATION_FR_MINSEC = Pattern.compile(
            "(\\d+)\\s*(?:minute|minutes|min)s?\\s+(\\d+)\\s*(?:seconde|secondes|sec)s?", Pattern.CASE_INSENSITIVE);
    private static final Pattern POTION_DURATION_FR_PENDANT = Pattern.compile(
            "pendant\\s+(\\d+)\\s*(?:min(?:ute)?s?|m\\b)", Pattern.CASE_INSENSITIVE);

    /** Texte normalisé (sans accents) : le message commence toujours ainsi (chat + logs). */
    private static final String POTION_BOOST_PREFIX = "vous avez recu un boost";

    private static String stripLeadingChatNoise(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c <= ' ' || c == '»' || c == '›' || c == '>' || c == '-' || c == '*' || c == '•' || c == '|') {
                i++;
                continue;
            }
            break;
        }
        return text.substring(i);
    }

    private static void savePotionSlotsTo(Properties p) {
        for (Map.Entry<String, PotionSlot> e : POTION_SLOTS.entrySet()) {
            String key = e.getKey();
            PotionSlot slot = e.getValue();
            p.setProperty("potion." + key + ".endMs", String.valueOf(slot.endMs));
            p.setProperty("potion." + key + ".percent", slot.percentDisplay);
        }
    }

    /**
     * Restaure les boosts potion (horodatage absolu {@code endMs}) : le temps continue hors jeu.
     * Réaffiche la section si au moins un boost est encore actif.
     */
    private static void loadPotionSlotsFrom(Properties p) {
        long now = System.currentTimeMillis();
        boolean anyActive = false;
        for (Map.Entry<String, PotionSlot> e : POTION_SLOTS.entrySet()) {
            String key = e.getKey();
            PotionSlot slot = e.getValue();
            try {
                slot.endMs = Long.parseLong(p.getProperty("potion." + key + ".endMs", "0"));
            } catch (NumberFormatException ignored) {
                slot.endMs = 0L;
            }
            slot.percentDisplay = p.getProperty("potion." + key + ".percent", "--%");
            if (slot.endMs > now) {
                anyActive = true;
            } else if (slot.endMs != 0L) {
                slot.endMs = 0L;
                slot.percentDisplay = "--%";
            }
        }
        if (anyActive) {
            showHudPotions = true;
        }
    }

    private static void applyPotionBoostFromPlainText(String stripped) {
        if (stripped.isBlank()) {
            return;
        }
        String core = stripLeadingChatNoise(stripped);
        if (core.isBlank()) {
            return;
        }
        String norm = normalizeAsciiLower(core);
        if (!norm.startsWith(POTION_BOOST_PREFIX)) {
            return;
        }
        String matchedKey = detectPotionSlotKeyFromLine(norm);
        if (matchedKey == null || !POTION_SLOTS.containsKey(matchedKey)) {
            return;
        }
        PotionSlot slot = POTION_SLOTS.get(matchedKey);
        int typeStart = typeWordStartInOriginal(core, matchedKey);
        slot.percentDisplay = extractPotionPercentFromLine(core, typeStart);
        slot.endMs = System.currentTimeMillis() + parsePotionBoostDurationMs(core);
        slot.lastDetectedPhrase = stripped.length() > 512 ? stripped.substring(0, 509) + "..." : stripped;
    }

    /** Détecte le type de boost : uniquement le libellé du slot potion (comme à l’écran). */
    private static String detectPotionSlotKeyFromLine(String asciiLowerNorm) {
        for (Map.Entry<String, PotionSlot> e : POTION_SLOTS.entrySet()) {
            if ("level".equals(e.getKey())) {
                if (wordBoundaryMatch(asciiLowerNorm, "level") || wordBoundaryMatch(asciiLowerNorm, "levels")) {
                    return "level";
                }
                continue;
            }
            String labelNorm = normalizeAsciiLower(e.getValue().label);
            if (wordBoundaryMatch(asciiLowerNorm, labelNorm)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static int typeWordStartInOriginal(String stripped, String slotKey) {
        PotionSlot slot = POTION_SLOTS.get(slotKey);
        if (slot == null) {
            return -1;
        }
        int idx = indexOfWordIgnoreCase(stripped, slot.label);
        if (idx >= 0) {
            return idx;
        }
        if ("level".equals(slotKey)) {
            return indexOfWordIgnoreCase(stripped, "Level");
        }
        return -1;
    }

    private static String extractPotionPercentFromLine(String stripped, int typeStart) {
        Matcher pm = POTION_PERCENT_PATTERN.matcher(stripped);
        String firstAfterType = null;
        String lastInLine = null;
        while (pm.find()) {
            String chunk = pm.group(1).replace(',', '.') + "%";
            lastInLine = chunk;
            if (typeStart >= 0 && pm.start() >= typeStart && firstAfterType == null) {
                firstAfterType = chunk;
            }
        }
        if (firstAfterType != null) {
            return firstAfterType;
        }
        return lastInLine != null ? lastInLine : "--%";
    }

    private static int indexOfWordIgnoreCase(String haystack, String word) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE).matcher(haystack);
        if (m.find()) {
            return m.start();
        }
        return -1;
    }

    /**
     * Durée : on garde la correspondance dont la fin est la plus à droite (souvent en fin de phrase).
     */
    private static long parsePotionBoostDurationMs(String stripped) {
        int bestEnd = -1;
        long bestMs = -1L;

        Matcher m = POTION_DURATION_FR_MINSEC.matcher(stripped);
        while (m.find()) {
            if (m.end() >= bestEnd) {
                bestEnd = m.end();
                bestMs = (Long.parseLong(m.group(1)) * 60L + Long.parseLong(m.group(2))) * 1000L;
            }
        }
        m = POTION_DURATION_MMSS.matcher(stripped);
        while (m.find()) {
            if (m.end() >= bestEnd) {
                bestEnd = m.end();
                bestMs = (Long.parseLong(m.group(1)) * 60L + Long.parseLong(m.group(2))) * 1000L;
            }
        }
        m = POTION_DURATION_M_MIN.matcher(stripped);
        while (m.find()) {
            if (m.end() >= bestEnd) {
                bestEnd = m.end();
                bestMs = (Long.parseLong(m.group(1)) * 60L + Long.parseLong(m.group(2))) * 1000L;
            }
        }
        m = POTION_DURATION_COLON.matcher(stripped);
        while (m.find()) {
            if (m.end() >= bestEnd) {
                bestEnd = m.end();
                bestMs = (Long.parseLong(m.group(1)) * 60L + Long.parseLong(m.group(2))) * 1000L;
            }
        }
        m = POTION_DURATION_FR_PENDANT.matcher(stripped);
        while (m.find()) {
            if (m.end() >= bestEnd) {
                bestEnd = m.end();
                bestMs = Long.parseLong(m.group(1)) * 60L * 1000L;
            }
        }
        m = POTION_DURATION_MIN.matcher(stripped);
        while (m.find()) {
            if (m.end() >= bestEnd) {
                bestEnd = m.end();
                bestMs = Long.parseLong(m.group(1)) * 60L * 1000L;
            }
        }
        m = POTION_DURATION_SEC.matcher(stripped);
        while (m.find()) {
            if (m.end() >= bestEnd) {
                bestEnd = m.end();
                bestMs = Long.parseLong(m.group(1)) * 1000L;
            }
        }

        if (bestMs >= 0L) {
            return bestMs;
        }
        return 5L * 60L * 1000L;
    }

    private static String formatPotionCountdown(PotionSlot slot) {
        if (slot.endMs == 0L) {
            return "--";
        }
        long now = System.currentTimeMillis();
        if (now >= slot.endMs) {
            return "--";
        }
        return formatEventRemaining(slot.endMs);
    }

    private static boolean isPotionSlotActive(PotionSlot slot, long nowMs) {
        return slot.endMs > nowMs;
    }

    private static List<PotionSlot> activePotionSlotsOrdered(long nowMs) {
        ArrayList<PotionSlot> out = new ArrayList<>();
        for (PotionSlot slot : POTION_SLOTS.values()) {
            if (isPotionSlotActive(slot, nowMs)) {
                out.add(slot);
            }
        }
        return out;
    }

    private static int potionSectionLineHeight(MinecraftClient client) {
        return client.textRenderer.fontHeight + 2;
    }

    private static String potionSlotLineText(PotionSlot slot) {
        return slot.label + "  " + potionPercentVisible(slot) + "  " + formatPotionCountdown(slot);
    }

    /** Hauteur du bloc Potion docké : titre + une ligne par boost actif uniquement. */
    private static int potionSectionDockedHeight(MinecraftClient client) {
        int active = activePotionSlotsOrdered(System.currentTimeMillis()).size();
        int slotH = potionSectionLineHeight(client);
        if (active == 0) {
            return 2 + 7;
        }
        return 2 + 8 + active * (slotH + 1);
    }

    /** Largeur minimale pour afficher la ligne la plus longue (panneau flottant Potion). */
    private static int potionSectionMinContentWidth(MinecraftClient client) {
        long now = System.currentTimeMillis();
        int max = client.textRenderer.getWidth(Text.literal("Potion"));
        for (PotionSlot slot : activePotionSlotsOrdered(now)) {
            max = Math.max(max, client.textRenderer.getWidth(Text.literal(potionSlotLineText(slot))));
        }
        return max + 2 * CARD_INSET + CARD_TEXT_X + 8;
    }

    private static int floatingPotionPanelWidth(MinecraftClient client, int idx) {
        int saved = hudSectionFloatWidth(idx);
        int needed = Math.max(MIN_CARD_WIDTH, potionSectionMinContentWidth(client));
        int w = Math.max(saved, needed);
        if (w > hudSectionFloatW[idx]) {
            hudSectionFloatW[idx] = w;
        }
        return w;
    }

    private static int hudSectionDisplayWidth(MinecraftClient client, int idx) {
        if (idx == 6 && showHudPotions) {
            return floatingPotionPanelWidth(client, idx);
        }
        return hudSectionFloatWidth(idx);
    }

    private static int drawPotionSectionContent(DrawContext context, MinecraftClient client, int x, int line, int width,
            int right, int layoutWidth, boolean floatSolid, int[] potRowHolder) {
        int potionTop = line;
        context.fill(x + CARD_INSET, potionTop, right - CARD_INSET, potionTop + 7,
                hudLayerArgb(0x66301810, floatSolid));
        context.drawText(client.textRenderer,
                Text.literal(trimHudSectionLine(client, "Potion", layoutWidth)),
                x + CARD_TEXT_X, potionTop - 2, 0xFFB388FF, false);
        int potRow = potionTop + 8;
        int slotH = potionSectionLineHeight(client);
        long potNow = System.currentTimeMillis();
        for (PotionSlot slot : activePotionSlotsOrdered(potNow)) {
            context.fill(x + CARD_INSET, potRow - 1, right - CARD_INSET, potRow + slotH - 1,
                    hudLayerArgb(0x44281830, floatSolid));
            context.drawBorder(x + CARD_INSET, potRow - 1, width - 2 * CARD_INSET, slotH,
                    hudLayerArgb(0x88604080, floatSolid));
            String potionText = potionSlotLineText(slot);
            context.drawText(client.textRenderer,
                    Text.literal(trimHudSectionLine(client, potionText, layoutWidth)),
                    x + CARD_TEXT_X, potRow, 0xFFE1BEE7, false);
            potRow += slotH + 1;
        }
        if (potRowHolder != null) {
            potRowHolder[0] = potRow;
        }
        return potRow;
    }

    /** Pourcentage affiché : repasse à {@code --%} dès que le boost est terminé. */
    private static String potionPercentVisible(PotionSlot slot) {
        long now = System.currentTimeMillis();
        if (slot.endMs <= 0L || now >= slot.endMs) {
            return "--%";
        }
        return slot.percentDisplay;
    }

    private static void updateEventChain() {
        long now = System.currentTimeMillis();
        if (eventEndMs > 0L && eventEndMs <= now && nextEventEndMs == 0L) {
            nextEventEndMs = now + 5L * 60L * 1000L;
        }
    }

    private static boolean wordBoundaryMatch(String asciiLowerLine, String word) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE);
        return p.matcher(asciiLowerLine).find();
    }

    private static String formatEventRemaining(long endMs) {
        long now = System.currentTimeMillis();
        if (endMs <= now || endMs == 0L) {
            return "00m00s";
        }
        long remainingMs = endMs - now;
        long totalSeconds = Math.max(0L, remainingMs / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return "%02dm%02ds".formatted(minutes, seconds);
    }

    private static String formatDoubleEventTagsSegment() {
        StringBuilder sb = new StringBuilder();
        if (doubleMoneyActive) {
            sb.append(' ').append(STATS.get("money").label());
        }
        if (doubleExpActive) {
            sb.append(' ').append(STATS.get("exp").label());
        }
        if (doubleGensDropActive) {
            sb.append(" GENS DROP");
        }
        return sb.toString();
    }

    /** Une seule ligne : event double + tags + compteur (carte large). */
    private static String formatDoubleEventLineWide() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("event double");
        sb.append(formatDoubleEventTagsSegment());
        sb.append(' ');
        sb.append(formatDoubleEventMainTimer(now));
        return sb.toString();
    }

    private static String formatDoubleEventTimerRow() {
        return formatDoubleEventMainTimer(System.currentTimeMillis());
    }

    private static String formatDoubleProchainLineWide() {
        long now = System.currentTimeMillis();
        if (doubleProchainEndMs == 0L || now >= doubleProchainEndMs) {
            return "Prochain Event: --";
        }
        return "Prochain Event: " + formatEventRemaining(doubleProchainEndMs);
    }

    private static String formatDoubleProchainTimerRow() {
        long now = System.currentTimeMillis();
        if (doubleProchainEndMs == 0L || now >= doubleProchainEndMs) {
            return "--";
        }
        return formatEventRemaining(doubleProchainEndMs);
    }

    /** Section stats : base vide (réintégration des totaux /h plus tard). */
    private static void drawCard(DrawContext context, MinecraftClient client, int x, int y) {
        long sessionMs = System.currentTimeMillis() - startTimeMs;
        drawCardBody(context, client, x, y, sessionMs);
        drawFloatingHudSections(context, client, sessionMs);
    }

    private static int hudSectionTextMaxWidth(int layoutWidth) {
        return Math.max(8, layoutWidth - CARD_TEXT_X - CARD_INSET);
    }

    private static String trimHudSectionLine(MinecraftClient client, String s, int layoutWidth) {
        return client.textRenderer.trimToWidth(s, hudSectionTextMaxWidth(layoutWidth));
    }

    /**
     * Dessine une section HUD (indices 0–6).
     * {@code potRowHolder} : si non null et section potions, y met la ligne basse des slots.
     */
    private static int drawHudSectionContent(DrawContext context, MinecraftClient client, int idx, int cardTop, int x, int line,
            int width, int right, long sessionMs, int layoutWidth, int[] potRowHolder) {
        String session = formatDuration(sessionMs);
        boolean narrowStats = layoutWidth < NARROW_CARD_BREAKPOINT;
        boolean rewardStacked = layoutWidth < REWARD_STACKED_BREAKPOINT;
        boolean floatSolid = hudSectionFloating[idx];
        int rewardTitleH = 10;
        int rewardPad = 1;
        return switch (idx) {
            case 0 -> {
                int sessionTop = line;
                int sessionBoxH = SESSION_TEXT_DY + SESSION_LINE_AFTER_HEADER;
                context.fill(x + CARD_INSET, sessionTop, right - CARD_INSET, sessionTop + sessionBoxH,
                        hudLayerArgb(0x554F632C, floatSolid));
                context.drawBorder(x + CARD_INSET, sessionTop, width - 2 * CARD_INSET, sessionBoxH,
                        hudLayerArgb(0xAA6A8C4A, floatSolid));
                int textY = sessionTop + SESSION_TEXT_DY;
                context.drawText(client.textRenderer,
                        Text.literal(trimHudSectionLine(client, "Session: " + session, layoutWidth)),
                        x + CARD_TEXT_X, textY, 0xFF59D4A7, true);
                yield sessionTop + sessionBoxH;
            }
            case 1 -> {
                int pFh = client.textRenderer.fontHeight;
                int pLineStep = pFh + 2;
                int prestigeBoxH = rewardTitleH + rewardPad + 2 * pLineStep + rewardPad;
                int prestigeTop = line;
                context.fill(x + CARD_INSET, prestigeTop, right - CARD_INSET, prestigeTop + prestigeBoxH,
                        hudLayerArgb(0x55201828, floatSolid));
                context.drawBorder(x + CARD_INSET, prestigeTop, width - 2 * CARD_INSET, prestigeBoxH,
                        hudLayerArgb(0xAA8A4A6A, floatSolid));
                String pTitleShown = trimHudSectionLine(client, prestigeTimeTitleString(), layoutWidth);
                context.drawText(client.textRenderer, Text.literal(pTitleShown),
                        x + CARD_TEXT_X, prestigeTop + 1, 0xFFFFB2DD, false);
                long nowMs = System.currentTimeMillis();
                int pLine1Y = prestigeTop + rewardTitleH + 2;
                String actuelPart;
                if (!prestigeRunActive) {
                    actuelPart = "--";
                } else if (prestigePaused) {
                    actuelPart = formatPrestigeSeconds(prestigeFrozenElapsedMs);
                } else {
                    actuelPart = formatPrestigeSeconds(nowMs - prestigeStartMs);
                }
                String actuelShown = trimHudSectionLine(client,
                        Text.translatable("feather_world_menu.prestige_time.actuel", actuelPart).getString(), layoutWidth);
                context.drawText(client.textRenderer, Text.literal(actuelShown),
                        x + CARD_TEXT_X, pLine1Y, 0xFFF8BBD0, false);
                int pLine2Y = pLine1Y + pLineStep;
                String dernierPart = prestigeDernierDurationMs >= 0L ? formatPrestigeSeconds(prestigeDernierDurationMs) : "--";
                String dernierShown = trimHudSectionLine(client,
                        Text.translatable("feather_world_menu.prestige_time.dernier", dernierPart).getString(), layoutWidth);
                context.drawText(client.textRenderer, Text.literal(dernierShown),
                        x + CARD_TEXT_X, pLine2Y, 0xFFCE93D8, false);
                yield prestigeTop + prestigeBoxH + SECTION_BLOCK_TAIL_PAD;
            }
            case 2 -> {
                int fh = client.textRenderer.fontHeight;
                List<String> rewardKeys = rewardKeysForHudDisplayList(rewardDisplayZone, false);
                int linesPerEntry = rewardEntryLineCount(hideRewardTenMin, hideRewardHourly);
                int rewardRowStep = rewardStacked ? rewardStackedEntryStepPx(client, linesPerEntry) : rewardWideEntryStepPx(client);
                int farmTimerLineH = fh + 2;
                int rewardBoxH = rewardTitleH + farmTimerLineH + rewardPad + rewardKeys.size() * rewardRowStep + rewardPad;
                int rewardTop = line;
                context.fill(x + CARD_INSET, rewardTop, right - CARD_INSET, rewardTop + rewardBoxH,
                        hudLayerArgb(0x552A2518, floatSolid));
                context.drawBorder(x + CARD_INSET, rewardTop, width - 2 * CARD_INSET, rewardBoxH,
                        hudLayerArgb(0xAA7A6A4A, floatSolid));
                Text rewardTitle = Text.literal("Reward");
                int rewardTitleX = x + CARD_TEXT_X;
                int rewardTitleY = rewardTop + 1;
                String rewardTitleStr = trimHudSectionLine(client, rewardTitle.getString(), layoutWidth);
                context.drawText(client.textRenderer, Text.literal(rewardTitleStr), rewardTitleX, rewardTitleY, 0xFFFFCC80, false);
                int rewardSuffixX = rewardTitleX + client.textRenderer.getWidth(Text.literal(rewardTitleStr)) + 4;
                int recapMaxW = Math.max(8, x + layoutWidth - CARD_INSET - rewardSuffixX);
                String recapShown = client.textRenderer.trimToWidth("(recap 65s)", recapMaxW);
                context.drawText(client.textRenderer, Text.literal(recapShown), rewardSuffixX, rewardTitleY, 0xFF90A4AE, false);
                long rewardNow = System.currentTimeMillis();
                long farmElapsed = rewardFarmSessionElapsedMsForDisplayZone(rewardDisplayZone, rewardNow);
                String farmLineRaw = Text.translatable("feather_world_menu.reward.farm_session_timer",
                        formatPrestigeSeconds(farmElapsed)).getString();
                String farmLineShown = trimHudSectionLine(client, farmLineRaw, layoutWidth);
                int farmTimerY = rewardTop + rewardTitleH + 1;
                context.drawText(client.textRenderer, Text.literal(farmLineShown), x + CARD_TEXT_X, farmTimerY, 0xFF80DEEA, false);
                int rLine = rewardTop + rewardTitleH + farmTimerLineH + 2;
                for (String rk : rewardKeys) {
                    RewardTracker rw = rewardHudTracker(rk, rewardDisplayZone, false);
                    if (rw == null) {
                        continue;
                    }
                    String tenMinDisp = formatSignedCompact(rw.sumGainsLast10Minutes(rewardNow)) + "/10min";
                    String hourDisp = formatSignedCompact(rw.sumGainsLastRollingHour(rewardNow)) + "/h";
                    String sessDisp = "session " + formatSignedCompact(rw.sessionCumulative);
                    if (rewardStacked) {
                        int dy = 0;
                        context.drawText(client.textRenderer,
                                Text.literal(trimHudSectionLine(client, rw.label, layoutWidth)),
                                x + CARD_TEXT_X, rLine + dy, rw.color, false);
                        dy += fh;
                        if (!hideRewardTenMin) {
                            context.drawText(client.textRenderer,
                                    Text.literal(trimHudSectionLine(client, tenMinDisp, layoutWidth)),
                                    x + CARD_TEXT_X, rLine + dy, 0xFFB0BEC5, false);
                            dy += fh;
                        }
                        if (!hideRewardHourly) {
                            context.drawText(client.textRenderer,
                                    Text.literal(trimHudSectionLine(client, hourDisp, layoutWidth)),
                                    x + CARD_TEXT_X, rLine + dy, 0xFFB0BEC5, false);
                            dy += fh;
                        }
                        context.drawText(client.textRenderer,
                                Text.literal(trimHudSectionLine(client, sessDisp, layoutWidth)),
                                x + CARD_TEXT_X, rLine + dy, 0xFF9CCC65, false);
                    } else {
                        StringBuilder sb = new StringBuilder(rw.label);
                        if (!hideRewardTenMin) sb.append("  |  ").append(tenMinDisp);
                        if (!hideRewardHourly) sb.append("  |  ").append(hourDisp);
                        sb.append("  |  ").append(sessDisp);
                        context.drawText(client.textRenderer,
                                Text.literal(trimHudSectionLine(client, sb.toString(), layoutWidth)),
                                x + CARD_TEXT_X, rLine, rw.color, false);
                    }
                    rLine += rewardRowStep;
                }
                yield rewardTop + rewardBoxH + SECTION_BLOCK_TAIL_PAD;
            }
            case 3 -> {
                int fh = client.textRenderer.fontHeight;
                List<String> afkKeys = rewardKeysForHudDisplayList(afkDisplayZone, true);
                int linesPerEntryAfk = rewardEntryLineCount(hideAfkTenMin, hideAfkHourly);
                int afkRowStep = rewardStacked ? rewardStackedEntryStepPx(client, linesPerEntryAfk) : rewardWideEntryStepPx(client);
                int idleExtra = !afkRewardLoggingActive ? (fh + 2) : 0;
                int farmTimerLineH = fh + 2;
                int afkBoxH = rewardTitleH + farmTimerLineH + rewardPad + idleExtra + afkKeys.size() * afkRowStep + rewardPad;
                int afkTop = line;
                context.fill(x + CARD_INSET, afkTop, right - CARD_INSET, afkTop + afkBoxH,
                        hudLayerArgb(0x551E2838, floatSolid));
                context.drawBorder(x + CARD_INSET, afkTop, width - 2 * CARD_INSET, afkBoxH,
                        hudLayerArgb(0xAA5C6A8A, floatSolid));
                Text afkTitle = Text.translatable("feather_world_menu.section.afk");
                int afkTitleX = x + CARD_TEXT_X;
                int afkTitleY = afkTop + 1;
                String afkTitleStr = trimHudSectionLine(client, afkTitle.getString(), layoutWidth);
                context.drawText(client.textRenderer, Text.literal(afkTitleStr), afkTitleX, afkTitleY, 0xFF80CBC4, false);
                if (afkDisplayZone == RewardDisplayZone.LAC) {
                    int afkSuffixX = afkTitleX + client.textRenderer.getWidth(Text.literal(afkTitleStr)) + 4;
                    int afkRecapMax = Math.max(8, x + layoutWidth - CARD_INSET - afkSuffixX);
                    String afkRecapShown = client.textRenderer.trimToWidth("(recap 1min30s)", afkRecapMax);
                    context.drawText(client.textRenderer, Text.literal(afkRecapShown), afkSuffixX, afkTitleY, 0xFF90A4AE, false);
                }
                long afkNow = System.currentTimeMillis();
                long afkFarmElapsed = afkFarmSessionElapsedMsForDisplayZone(afkDisplayZone, afkNow);
                String afkFarmLineRaw = Text.translatable("feather_world_menu.reward.farm_session_timer",
                        formatPrestigeSeconds(afkFarmElapsed)).getString();
                String afkFarmLineShown = trimHudSectionLine(client, afkFarmLineRaw, layoutWidth);
                int afkFarmTimerY = afkTop + rewardTitleH + 1;
                context.drawText(client.textRenderer, Text.literal(afkFarmLineShown), x + CARD_TEXT_X, afkFarmTimerY, 0xFF80DEEA, false);
                int aLine = afkTop + rewardTitleH + farmTimerLineH + 2;
                if (!afkRewardLoggingActive) {
                    String idleShown = trimHudSectionLine(client,
                            Text.translatable("feather_world_menu.afk.status_idle").getString(), layoutWidth);
                    context.drawText(client.textRenderer, Text.literal(idleShown),
                            x + CARD_TEXT_X, aLine, 0xFF78909C, false);
                    aLine += fh + 2;
                }
                for (String rk : afkKeys) {
                    RewardTracker rw = rewardHudTracker(rk, afkDisplayZone, true);
                    if (rw == null) {
                        continue;
                    }
                    String tenMinDisp = formatSignedCompact(rw.sumGainsLast10Minutes(afkNow)) + "/10min";
                    String hourDisp = formatSignedCompact(rw.sumGainsLastRollingHour(afkNow)) + "/h";
                    String sessDisp = "session " + formatSignedCompact(rw.sessionCumulative);
                    if (rewardStacked) {
                        int dy = 0;
                        context.drawText(client.textRenderer,
                                Text.literal(trimHudSectionLine(client, rw.label, layoutWidth)),
                                x + CARD_TEXT_X, aLine + dy, rw.color, false);
                        dy += fh;
                        if (!hideAfkTenMin) {
                            context.drawText(client.textRenderer,
                                    Text.literal(trimHudSectionLine(client, tenMinDisp, layoutWidth)),
                                    x + CARD_TEXT_X, aLine + dy, 0xFFB0BEC5, false);
                            dy += fh;
                        }
                        if (!hideAfkHourly) {
                            context.drawText(client.textRenderer,
                                    Text.literal(trimHudSectionLine(client, hourDisp, layoutWidth)),
                                    x + CARD_TEXT_X, aLine + dy, 0xFFB0BEC5, false);
                            dy += fh;
                        }
                        context.drawText(client.textRenderer,
                                Text.literal(trimHudSectionLine(client, sessDisp, layoutWidth)),
                                x + CARD_TEXT_X, aLine + dy, 0xFF9CCC65, false);
                    } else {
                        StringBuilder sb = new StringBuilder(rw.label);
                        if (!hideAfkTenMin) sb.append("  |  ").append(tenMinDisp);
                        if (!hideAfkHourly) sb.append("  |  ").append(hourDisp);
                        sb.append("  |  ").append(sessDisp);
                        context.drawText(client.textRenderer,
                                Text.literal(trimHudSectionLine(client, sb.toString(), layoutWidth)),
                                x + CARD_TEXT_X, aLine, rw.color, false);
                    }
                    aLine += afkRowStep;
                }
                yield afkTop + afkBoxH + SECTION_BLOCK_TAIL_PAD;
            }
            case 4 -> {
                int farmBoxH = 26;
                int farmBoxTop = line;
                int farmBoxBottom = farmBoxTop + farmBoxH;
                context.fill(x + CARD_INSET, farmBoxTop, right - CARD_INSET, farmBoxBottom,
                        hudLayerArgb(0x55253116, floatSolid));
                context.drawBorder(x + CARD_INSET, farmBoxTop, width - 2 * CARD_INSET, farmBoxH,
                        hudLayerArgb(0xAA6D8A3D, floatSolid));
                String farmL1 = trimHudSectionLine(client,
                        "Event " + eventZone + ": " + formatEventRemaining(eventEndMs), layoutWidth);
                String farmL2 = trimHudSectionLine(client,
                        "Prochain Event: " + formatEventRemaining(nextEventEndMs), layoutWidth);
                context.drawText(client.textRenderer, Text.literal(farmL1), x + CARD_TEXT_X, farmBoxTop + 3, 0xFFEBD786, false);
                context.drawText(client.textRenderer, Text.literal(farmL2), x + CARD_TEXT_X, farmBoxTop + 14, 0xFFD0E09A, false);
                yield farmBoxBottom + SECTION_BLOCK_TAIL_PAD;
            }
            case 5 -> {
                int doubleBoxH = narrowStats ? 44 : 26;
                int doubleBoxTop = line;
                int doubleBoxBottom = doubleBoxTop + doubleBoxH;
                context.fill(x + CARD_INSET, doubleBoxTop, right - CARD_INSET, doubleBoxBottom,
                        hudLayerArgb(0x55301810, floatSolid));
                context.drawBorder(x + CARD_INSET, doubleBoxTop, width - 2 * CARD_INSET, doubleBoxH,
                        hudLayerArgb(0xAA8D4E6D, floatSolid));
                if (narrowStats) {
                    context.drawText(client.textRenderer,
                            Text.literal(trimHudSectionLine(client, "event double" + formatDoubleEventTagsSegment(), layoutWidth)),
                            x + CARD_TEXT_X, doubleBoxTop + 3, 0xFFFFB74D, false);
                    context.drawText(client.textRenderer,
                            Text.literal(trimHudSectionLine(client, formatDoubleEventTimerRow(), layoutWidth)),
                            x + CARD_TEXT_X, doubleBoxTop + 13, 0xFFFFE0B2, false);
                    context.drawText(client.textRenderer,
                            Text.literal(trimHudSectionLine(client, "Prochain Event:", layoutWidth)),
                            x + CARD_TEXT_X, doubleBoxTop + 23, 0xFFCE93D8, false);
                    context.drawText(client.textRenderer,
                            Text.literal(trimHudSectionLine(client, formatDoubleProchainTimerRow(), layoutWidth)),
                            x + CARD_TEXT_X, doubleBoxTop + 33, 0xFFE1BEE7, false);
                } else {
                    context.drawText(client.textRenderer,
                            Text.literal(trimHudSectionLine(client, formatDoubleEventLineWide(), layoutWidth)),
                            x + CARD_TEXT_X, doubleBoxTop + 3, 0xFFFFB74D, false);
                    context.drawText(client.textRenderer,
                            Text.literal(trimHudSectionLine(client, formatDoubleProchainLineWide(), layoutWidth)),
                            x + CARD_TEXT_X, doubleBoxTop + 14, 0xFFCE93D8, false);
                }
                yield doubleBoxBottom + SECTION_BLOCK_TAIL_PAD;
            }
            case 6 -> drawPotionSectionContent(context, client, x, line, width, right, layoutWidth, floatSolid, potRowHolder);
            default -> line;
        };
    }

    private static void drawFloatingHudSections(DrawContext context, MinecraftClient client, long sessionMs) {
        for (int idx = 0; idx < HUD_SECTION_COUNT; idx++) {
            if (!hudSectionShown(idx) || !hudSectionFloating[idx]) {
                continue;
            }
            int fx = hudSectionFloatX[idx];
            int fy = hudSectionFloatY[idx];
            int w = hudSectionDisplayWidth(client, idx);
            int h = getHudFloatPanelHeight(client, idx);
            if (h <= 0) {
                continue;
            }
            context.fill(fx + 2, fy + 2, fx + w + 2, fy + h + 2, 0x48000000);
            context.fill(fx, fy, fx + w, fy + h, 0xFF213017);
            context.drawBorder(fx, fy, w, h, 0xFFD4BA4A);
            context.drawBorder(fx + 1, fy + 1, w - 2, h - 2, hudLayerArgb(0x884F5E2B, true));
            int right = fx + w;
            context.enableScissor(fx, fy, right, fy + h);
            try {
                int line = fy + CARD_BODY_TOP_INSET;
                drawHudSectionContent(context, client, idx, fy, fx, line, w, right, sessionMs, w, null);
                if (editMode) {
                    drawFloatingSectionCloseButton(context, client, idx);
                    int hLeft = right - RESIZE_HANDLE_INSET;
                    int hTop = fy + h - RESIZE_HANDLE_INSET;
                    context.fill(hLeft, hTop, hLeft + RESIZE_HANDLE_SIZE, hTop + RESIZE_HANDLE_SIZE, 0xFFBCA347);
                    drawDragResizeHintLeftOfSquare(context, client, hLeft, hTop, RESIZE_HANDLE_SIZE, fx + CARD_INSET, 0xFF9E9E9E);
                }
            } finally {
                context.disableScissor();
            }
        }
    }

    private static void drawFloatingSectionCloseButton(DrawContext context, MinecraftClient client, int idx) {
        int left = floatingSectionCloseLeft(client, idx);
        int top = floatingSectionCloseTop(idx);
        context.fill(left, top, left + FLOAT_CLOSE_BTN_SIZE, top + FLOAT_CLOSE_BTN_SIZE, 0xFFB71C1C);
        context.drawBorder(left, top, FLOAT_CLOSE_BTN_SIZE, FLOAT_CLOSE_BTN_SIZE, 0xFFFF8A80);
        String shown = "x";
        int tx = left + (FLOAT_CLOSE_BTN_SIZE - client.textRenderer.getWidth(shown)) / 2;
        int ty = top + (FLOAT_CLOSE_BTN_SIZE - client.textRenderer.fontHeight) / 2 + 1;
        context.drawText(client.textRenderer, Text.literal(shown), tx, ty, 0xFFFFFFFF, false);
    }

    private static void drawMouseInfoTooltip(DrawContext context, MinecraftClient client, int mouseX, int mouseY, String text) {
        drawMouseInfoTooltip(context, client, mouseX, mouseY, text, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Infobulle près du curseur. Si {@code maxRightExclusive} &lt; {@code Integer#MAX_VALUE}, le cadre reste
     * strictement à gauche (ne chevauche pas la zone scrollbar du panneau). Les bornes Y optionnelles
     * gardent le texte dans la bande utile du panneau lorsque le curseur est près du bas ou du haut.
     */
    private static void drawMouseInfoTooltip(DrawContext context, MinecraftClient client, int mouseX, int mouseY, String text,
            int maxRightExclusive, int clipTop, int clipBottom) {
        if (client.getWindow() == null || text == null || text.isEmpty()) {
            return;
        }
        int padX = 4;
        int padY = 3;
        int boxW = client.textRenderer.getWidth(text) + 2 * padX;
        int boxH = client.textRenderer.fontHeight + 2 * padY;
        int maxX = Math.max(2, client.getWindow().getScaledWidth() - boxW - 2);
        int maxY = Math.max(2, client.getWindow().getScaledHeight() - boxH - 2);

        int x = mouseX + 12;
        if (x > maxX) {
            x = mouseX - boxW - 12;
        }
        x = clamp(x, 2, maxX);

        int y = mouseY - boxH - 6;
        if (y < 2) {
            y = mouseY + 12;
        }
        y = clamp(y, 2, maxY);

        if (maxRightExclusive < Integer.MAX_VALUE) {
            int margin = 2;
            int limitRight = maxRightExclusive - margin;
            if (x + boxW > limitRight) {
                x = mouseX - boxW - 12;
            }
            if (x + boxW > limitRight) {
                x = limitRight - boxW;
            }
            int xMaxForPanel = Math.min(maxX, Math.max(2, limitRight - boxW));
            x = clamp(x, 2, xMaxForPanel);
        }

        if (clipTop != Integer.MIN_VALUE && clipBottom != Integer.MAX_VALUE && clipBottom - clipTop > boxH + 4) {
            int innerMinY = clipTop + 1;
            int innerMaxY = clipBottom - boxH - 1;
            if (innerMaxY >= innerMinY) {
                y = clamp(y, Math.max(2, innerMinY), Math.min(maxY, innerMaxY));
            }
        }

        context.fill(x, y, x + boxW, y + boxH, 0xE0202020);
        context.drawBorder(x, y, boxW, boxH, 0xFF90CAF9);
        context.drawText(client.textRenderer, Text.literal(text), x + padX, y + padY, 0xFFF3F6FF, false);
    }

    private static void drawSectionsInfoBadge(DrawContext context, MinecraftClient client, int x, int y) {
        context.drawText(client.textRenderer, Text.literal(SECTIONS_INFO_BADGE_GLYPH), x, y, 0xFFE3F2FD, false);
    }

    /** Clic sur la case 8×8 à gauche d’une ligne section / Informations (repère contenu + scroll). */
    private static boolean sectionsPanelRowCheckboxHit(int mouseX, int adjY, int bxCheck, int checkBox, int rowY) {
        int byCheck = rowY + 2;
        return mouseX >= bxCheck && mouseX <= bxCheck + checkBox
                && adjY >= byCheck && adjY <= byCheck + checkBox;
    }

    private static String sectionsPanelSectionRowInfoTooltip(int sectionIdx) {
        if (sectionIdx < 0 || sectionIdx >= SECTION_PANEL_ROW_INFO_KEYS.length) {
            return "";
        }
        return Text.translatable(SECTION_PANEL_ROW_INFO_KEYS[sectionIdx]).getString();
    }

    /** Petit trait horizontal centré sous le bord du haut : signale la zone de drag du menu / sous-menu. */
    private static void drawDragHandle(DrawContext context, int px, int py, int pw, int color) {
        int handleW = Math.min(48, Math.max(16, pw - 2 * CARD_INSET));
        int handleX = px + (pw - handleW) / 2;
        int handleY = py + 3;
        context.fill(handleX, handleY, handleX + handleW, handleY + 1, color);
    }

    private static void drawCardBody(DrawContext context, MinecraftClient client, int x, int y, long sessionMs) {
        mainCardSettingsBtnRectValid = false;
        mainCardEditerBtnRectValid = false;
        mainCardFermerSaveBtnRectValid = false;
        int width = cardWidth;
        int height = cardHeight;
        int right = x + width;
        int bottom = y + height;

        for (int i = 0; i < HUD_SECTION_COUNT; i++) {
            hudDockHitTop[i] = -1;
        }

        context.fill(x + 2, y + 2, right + 2, bottom + 2, 0x77000000);
        context.fill(x, y, right, bottom, 0xCC213017);
        context.drawBorder(x, y, width, height, 0xFFD4BA4A);
        context.drawBorder(x + 1, y + 1, width - 2, height - 2, 0x884F5E2B);
        drawDragHandle(context, x, y, width, 0xFFD4BA4A);

        int vpTop = mainCardScrollViewportTop(y);
        int vpBottom = bottom - mainCardFooterReservePx(client);
        int scissorRight = right - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
        int maxMainScroll = mainCardScrollMax(client, y, bottom, width);

        int docLine = CARD_BODY_TOP_INSET;
        int[] potScratch = {-1};
        boolean anyDockedSectionYet = false;
        int lineEndScreen = y + CARD_BODY_TOP_INSET - mainCardScrollPx;
        context.enableScissor(x, vpTop, scissorRight, vpBottom);
        try {
            for (int idx = 0; idx < HUD_SECTION_COUNT; idx++) {
                if (!hudSectionShown(idx) || hudSectionFloating[idx]) {
                    continue;
                }
                if (anyDockedSectionYet) {
                    docLine += CARD_SECTION_STACK_GAP;
                }
                anyDockedSectionYet = true;
                int screenLine = y + docLine - mainCardScrollPx;
                int hitTop = screenLine;
                lineEndScreen = drawHudSectionContent(context, client, idx, y, x, screenLine, width, right, sessionMs, width,
                        idx == 6 ? potScratch : null);
                int hh = getHudSectionHeight(client, idx, width);
                hudDockHitTop[idx] = hitTop;
                hudDockHitBottom[idx] = hitTop + hh - 1;
                docLine += getHudSectionHeight(client, idx, width);
            }
        } finally {
            context.disableScissor();
        }

        boolean dockedPotions = showHudPotions && !hudSectionFloating[6];
        int footReserve = mainCardFooterReservePx(client);
        int footY = dockedPotions && potScratch[0] >= 0
                ? Math.min(bottom - footReserve, potScratch[0] + 2)
                : Math.min(bottom - footReserve, lineEndScreen + 2);
        drawMainCardFooterWrapped(context, client, x, footY);
        if (editMode) {
            int hLeft = right - RESIZE_HANDLE_INSET;
            int hTop = bottom - RESIZE_HANDLE_INSET;
            context.fill(hLeft, hTop, hLeft + RESIZE_HANDLE_SIZE, hTop + RESIZE_HANDLE_SIZE, 0xFFBCA347);
            drawDragResizeHintLeftOfSquare(context, client, hLeft, hTop, RESIZE_HANDLE_SIZE, x + CARD_INSET, 0xFFA0A0A0);
        }
        int trackX = right - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
        drawVerticalScrollbar(context, trackX, vpTop, vpBottom, mainCardScrollPx, maxMainScroll, 0x55303030, 0xFFD4BA4A);
    }

    /** Ligne titre Prestige : case + libellé ; clear/pause sur la ligne suivante. */
    private static void drawPrestigeSubmenuTitleRow(DrawContext context, MinecraftClient client, int sx, int ry, Text rowTitle,
            int scrollTrackLeft, int sectionIdx) {
        drawSectionsSubmenuRowTitleWithInfoBadge(context, client, sx, ry, Text.literal(prestigeTimeTitleString()), scrollTrackLeft, sectionIdx);
    }

    /** Ligne « ↳ » + boutons « clear » / « pause »|« resume » (minuscules, texte collé au bord gauche de la case). */
    private static void drawPrestigeSubmenuActionsRow(DrawContext context, MinecraftClient client, int sx, int ry) {
        int btnTop = ry + 1;
        int btnH = SECTIONS_ROW_H - 2;
        int branchW = client.textRenderer.getWidth(Text.literal(SECTIONS_REWARD_MONEY_BRANCH));
        int branchX = sx + CARD_INSET;
        int yText = ry + 2;
        context.drawText(client.textRenderer, Text.literal(SECTIONS_REWARD_MONEY_BRANCH), branchX, yText, 0xFFB0BEC5, false);
        int clearW = prestigeSubRowClearBtnW(client);
        int clearLeft = branchX + branchW + 2;
        int pauseLeft = clearLeft + clearW + SECTIONS_PRESTIGE_BTN_GAP;
        drawSubmenuTightLowercaseBtn(context, client, clearLeft, btnTop, clearW, btnH, "clear");
        String pauseLabel = prestigePaused ? "resume" : "pause";
        int pauseW = prestigeSubRowPauseBtnWForLabel(client, pauseLabel);
        drawSubmenuTightLowercaseBtn(context, client, pauseLeft, btnTop, pauseW, btnH, pauseLabel);
    }

    /**
     * Ligne « ↳ cacher : » + /10min + /h + « money » : tout est cadré depuis la gauche du panneau (ne suit pas le bord droit).
     */
    private static void drawSubmenuCacherAndMoneyRow(DrawContext context, MinecraftClient client, int sx, int ry,
            boolean tenMinHidden, boolean hourHidden, boolean hideMoneyRow) {
        RewardAfkCacherSubLayout lay = layoutRewardAfkCacherSubRow(client, sx);
        int btnTop = ry + 1;
        int btnH = SECTIONS_ROW_H - 2;

        Text cacherPrefix = Text.literal(SECTIONS_REWARD_CACHER_PREFIX);
        int yText = ry + 2;
        context.drawText(client.textRenderer, Text.literal(SECTIONS_REWARD_MONEY_BRANCH), lay.branchX(), yText, 0xFFB0BEC5, false);
        context.drawText(client.textRenderer, cacherPrefix, lay.prefixX(), yText, 0xFFB0BEC5, false);

        drawCacherToggleBtn(context, client, lay.tenMinLeft(), btnTop, sectionsCacherTenMinBtnW(client), btnH, "/10min", tenMinHidden);
        drawCacherToggleBtn(context, client, lay.hourLeft(), btnTop, sectionsCacherHourBtnW(client), btnH, "/h", hourHidden);

        int moneyLeft = sectionsMoneyBtnLeft(lay.hourLeft(), sectionsCacherHourBtnW(client));
        int moneyW = sectionsMoneyBtnW(client);
        drawSubmenuMoneyRowToggleBtn(context, client, moneyLeft, btnTop, moneyW, btnH, !hideMoneyRow);
    }

    private record EnchantOptionButtonLayout(int optionIndex, int x, int y, int width, int height) {
        private int right() {
            return x + width;
        }

        private int bottom() {
            return y + height;
        }
    }

    private record RewardAfkCacherSubLayout(int branchX, int prefixX, int tenMinLeft, int hourLeft) {
    }

    private record RewardAfkClearSubLayout(int branchX, int prefixX, int tenMinLeft, int hourlyLeft, int resetLeft) {
    }

    private static RewardAfkClearSubLayout layoutRewardAfkClearSubRow(MinecraftClient client, int sx) {
        int left = sx + CARD_INSET;
        int branchW = client.textRenderer.getWidth(Text.literal(SECTIONS_REWARD_MONEY_BRANCH));
        int branchX = left;
        int prefixW = client.textRenderer.getWidth(Text.literal(SECTIONS_REWARD_CLEAR_PREFIX));
        int prefixX = branchX + branchW + 2;
        int tenW = sectionsRewardClearTenMinBtnW(client);
        int hourW = sectionsRewardClearHourlyBtnW(client);
        int resetBtnW = sectionsRewardResetBtnW(client);
        int tenMinLeft = prefixX + prefixW + SECTIONS_REWARD_CLEAR_PREFIX_GAP;
        int hourlyLeft = tenMinLeft + tenW + SECTIONS_REWARD_RESET_BTN_GAP;
        int resetLeft = hourlyLeft + hourW + SECTIONS_REWARD_RESET_BTN_GAP;
        return new RewardAfkClearSubLayout(branchX, prefixX, tenMinLeft, hourlyLeft, resetLeft);
    }

    private static RewardAfkCacherSubLayout layoutRewardAfkCacherSubRow(MinecraftClient client, int sx) {
        int left = sx + CARD_INSET;
        int branchW = client.textRenderer.getWidth(Text.literal(SECTIONS_REWARD_MONEY_BRANCH));
        int branchX = left;
        int prefixW = client.textRenderer.getWidth(Text.literal(SECTIONS_REWARD_CACHER_PREFIX));
        int prefixX = branchX + branchW + 2;
        int tenW = sectionsCacherTenMinBtnW(client);
        int hourW = sectionsCacherHourBtnW(client);
        int tenMinLeft = prefixX + prefixW + SECTIONS_REWARD_CLEAR_PREFIX_GAP;
        int hourLeft = tenMinLeft + tenW + SECTIONS_REWARD_RESET_BTN_GAP;
        return new RewardAfkCacherSubLayout(branchX, prefixX, tenMinLeft, hourLeft);
    }

    /** Texte « money » dans une case même hauteur que /10min et /h ; 1 px entre bord gauche et le « m ». */
    private static void drawSubmenuMoneyRowToggleBtn(DrawContext context, MinecraftClient client, int x, int y, int w, int h,
            boolean moneyRowVisible) {
        int fill = moneyRowVisible ? 0x5566BB6A : 0x55333333;
        int border = moneyRowVisible ? 0xFF33691E : 0xFF888888;
        context.fill(x, y, x + w, y + h, fill);
        context.drawBorder(x, y, w, h, border);
        int ty = y + (h - client.textRenderer.fontHeight) / 2 + 1;
        context.drawText(client.textRenderer, Text.literal("money"), x + 1, ty, 0xFFE8EAF6, false);
    }

    /** Bouton style sous-menu (fond indigo) ; libellé minuscule collé au bord gauche interne. */
    private static void drawSubmenuTightLowercaseBtn(DrawContext context, MinecraftClient client, int x, int y, int w, int h, String label) {
        context.fill(x, y, x + w, y + h, 0x554356AC);
        context.drawBorder(x, y, w, h, 0xFF7986CB);
        int ty = y + (h - client.textRenderer.fontHeight) / 2 + 1;
        context.drawText(client.textRenderer, Text.literal(label), x + 1, ty, 0xFFE8EAF6, false);
    }

    private static int sectionsMoneyBtnW(MinecraftClient client) {
        return client.textRenderer.getWidth(Text.literal("money")) + 2;
    }

    private static int sectionsMoneyBtnLeft(int cacherHourLeft, int cacherHourBtnW) {
        return cacherHourLeft + cacherHourBtnW + 4;
    }

    private static int prestigeSubRowClearBtnW(MinecraftClient client) {
        return client.textRenderer.getWidth(Text.literal("clear")) + 2;
    }

    /** Largeur du bouton pause pour le libellé affiché (évite le vide à droite quand c’est « pause »). */
    private static int prestigeSubRowPauseBtnWForLabel(MinecraftClient client, String pauseOrResumeLabel) {
        return client.textRenderer.getWidth(Text.literal(pauseOrResumeLabel)) + 2;
    }

    /** Largeur mini du bouton pause / resume (pour {@link #minSectionsPanelWidth}). */
    private static int prestigeSubRowPauseBtnW(MinecraftClient client) {
        int a = client.textRenderer.getWidth(Text.literal("pause"));
        int b = client.textRenderer.getWidth(Text.literal("resume"));
        return Math.max(a, b) + 2;
    }

    private static void drawCacherToggleBtn(DrawContext context, MinecraftClient client, int x, int y, int w, int h, String label, boolean active) {
        int fill = active ? 0x5566BB6A : 0x55333333;
        int border = active ? 0xFF33691E : 0xFF888888;
        context.fill(x, y, x + w, y + h, fill);
        context.drawBorder(x, y, w, h, border);
        int tx = x + (w - client.textRenderer.getWidth(label)) / 2;
        int ty = y + (h - client.textRenderer.fontHeight) / 2 + 1;
        context.drawText(client.textRenderer, Text.literal(label), tx, ty, 0xFFE8EAF6, false);
    }

    private static int enchantOptionsWorldTitleY(int innerTop) {
        return innerTop + 1;
    }

    private static int enchantTopBtnH() {
        return SECTIONS_ROW_H - 2;
    }

    private static int enchantPanelScrollTrackLeft(int panelLeft, int panelWidth) {
        return panelLeft + panelWidth - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
    }

    private static int minEnchantPanelWidth(MinecraftClient client) {
        int buttonsW = sectionsEnchantCumulsBtnW(client) + ENCHANT_OPTIONS_TOP_BTN_GAP
                + sectionsEnchantTopSortBtnW(client) + ENCHANT_OPTIONS_TOP_BTN_GAP
                + sectionsEnchantResetBtnW(client);
        return Math.max(MIN_ENCHANT_PANEL_W, 2 * CARD_INSET + SCROLLBAR_TRACK_W + SCROLLBAR_PAD + buttonsW + 6);
    }

    private static int enchantPanelScrollContentTop(MinecraftClient client, int innerTop, int innerLeft, int maxRightExclusive) {
        EnchantOptionsTopBtnRowLayout row = layoutEnchantTopButtonRow(client, innerLeft, innerTop, maxRightExclusive);
        return row.bottom() + 3;
    }

    private static String enchantResetButtonLabel() {
        return Text.translatable("feather_world_menu.enchant.reset_button").getString();
    }

    private static int enchantOptionsEnchantButtonsTop(MinecraftClient client, int innerTop) {
        return enchantOptionsWorldTitleY(innerTop) + client.textRenderer.fontHeight + 3;
    }

    private static final int ENCHANT_OPTIONS_TOP_BTN_GAP = 4;

    private static int sectionsEnchantCumulsBtnW(MinecraftClient client) {
        return client.textRenderer.getWidth(Text.translatable("feather_world_menu.enchant.cumuls_button")) + SECTIONS_BTN_TEXT_PAD;
    }

    private static int sectionsEnchantTopSortBtnW(MinecraftClient client) {
        int wProc = client.textRenderer.getWidth(Text.translatable("feather_world_menu.enchant.top_proc_button"));
        int wCumuls = client.textRenderer.getWidth(Text.translatable("feather_world_menu.enchant.top_cumuls_button"));
        return Math.max(wProc, wCumuls) + SECTIONS_BTN_TEXT_PAD;
    }

    private static int sectionsEnchantResetBtnW(MinecraftClient client) {
        return client.textRenderer.getWidth(Text.literal(enchantResetButtonLabel())) + SECTIONS_BTN_TEXT_PAD;
    }

    private record EnchantOptionsCumulsBtnLayout(int left, int top, int width, int height) {
        int right() {
            return left + width;
        }

        int bottom() {
            return top + height;
        }
    }

    private record EnchantOptionsTopSortBtnLayout(int left, int top, int width, int height) {
        int right() {
            return left + width;
        }

        int bottom() {
            return top + height;
        }
    }

    private record EnchantOptionsResetBtnLayout(int left, int top, int width, int height) {
        int right() {
            return left + width;
        }

        int bottom() {
            return top + height;
        }
    }

    private record EnchantOptionsTopBtnRowLayout(EnchantOptionsCumulsBtnLayout cumuls, EnchantOptionsTopSortBtnLayout topSort,
            EnchantOptionsResetBtnLayout reset) {
        int bottom() {
            return Math.max(Math.max(cumuls.bottom(), topSort.bottom()), reset.bottom());
        }
    }

    private static EnchantOptionsTopBtnRowLayout layoutEnchantTopButtonRow(MinecraftClient client, int innerLeft, int topY,
            int maxRightExclusive) {
        int h = enchantTopBtnH();
        int gap = ENCHANT_OPTIONS_TOP_BTN_GAP;
        int available = Math.max(0, maxRightExclusive - innerLeft);
        int cumulsW = sectionsEnchantCumulsBtnW(client);
        int topSortFullW = sectionsEnchantTopSortBtnW(client);
        int resetW = sectionsEnchantResetBtnW(client);
        int minTopSortW = client.textRenderer.getWidth(Text.literal("top :")) + SECTIONS_BTN_TEXT_PAD;

        int singleRowTotal = cumulsW + gap + topSortFullW + gap + resetW;
        if (singleRowTotal <= available) {
            EnchantOptionsCumulsBtnLayout cumuls = new EnchantOptionsCumulsBtnLayout(innerLeft, topY, cumulsW, h);
            int topLeft = cumuls.right() + gap;
            EnchantOptionsTopSortBtnLayout topSort = new EnchantOptionsTopSortBtnLayout(topLeft, topY, topSortFullW, h);
            int resetLeft = topSort.right() + gap;
            EnchantOptionsResetBtnLayout reset = new EnchantOptionsResetBtnLayout(resetLeft, topY, resetW, h);
            return new EnchantOptionsTopBtnRowLayout(cumuls, topSort, reset);
        }

        int topSortShrunkW = Math.min(topSortFullW, Math.max(minTopSortW, available - cumulsW - gap - gap - resetW));
        int shrunkRowTotal = cumulsW + gap + topSortShrunkW + gap + resetW;
        if (shrunkRowTotal <= available) {
            EnchantOptionsCumulsBtnLayout cumuls = new EnchantOptionsCumulsBtnLayout(innerLeft, topY, cumulsW, h);
            int topLeft = cumuls.right() + gap;
            EnchantOptionsTopSortBtnLayout topSort = new EnchantOptionsTopSortBtnLayout(topLeft, topY, topSortShrunkW, h);
            int resetLeft = topSort.right() + gap;
            EnchantOptionsResetBtnLayout reset = new EnchantOptionsResetBtnLayout(resetLeft, topY, resetW, h);
            return new EnchantOptionsTopBtnRowLayout(cumuls, topSort, reset);
        }

        int row2Y = topY + h + 2;
        int topSortRowW = Math.min(topSortFullW, Math.max(minTopSortW, available - cumulsW - gap));
        EnchantOptionsCumulsBtnLayout cumuls = new EnchantOptionsCumulsBtnLayout(innerLeft, topY, cumulsW, h);
        EnchantOptionsTopSortBtnLayout topSort = new EnchantOptionsTopSortBtnLayout(cumuls.right() + gap, topY, topSortRowW, h);
        int resetRowW = Math.min(resetW, available);
        EnchantOptionsResetBtnLayout reset = new EnchantOptionsResetBtnLayout(innerLeft, row2Y, resetRowW, h);
        return new EnchantOptionsTopBtnRowLayout(cumuls, topSort, reset);
    }

    private static void drawEnchantTopButtonRow(DrawContext context, MinecraftClient client, int innerLeft, int topY,
            int maxRightExclusive) {
        EnchantOptionsTopBtnRowLayout row = layoutEnchantTopButtonRow(client, innerLeft, topY, maxRightExclusive);
        EnchantOptionsCumulsBtnLayout cumuls = row.cumuls();
        drawCacherToggleBtn(context, client, cumuls.left(), cumuls.top(), cumuls.width(), cumuls.height(),
                Text.translatable("feather_world_menu.enchant.cumuls_button").getString(), enchantShowCumulativeSums);
        EnchantOptionsTopSortBtnLayout topSort = row.topSort();
        Text sortLabel = enchantPanelSortByCumuls
                ? Text.translatable("feather_world_menu.enchant.top_cumuls_button")
                : Text.translatable("feather_world_menu.enchant.top_proc_button");
        String sortShown = client.textRenderer.trimToWidth(sortLabel.getString(), Math.max(8, topSort.width() - 4));
        drawCacherToggleBtn(context, client, topSort.left(), topSort.top(), topSort.width(), topSort.height(), sortShown, true);
        EnchantOptionsResetBtnLayout reset = row.reset();
        drawCacherToggleBtn(context, client, reset.left(), reset.top(), reset.width(), reset.height(), enchantResetButtonLabel(), true);
    }

    private static int computeEnchantOptionsContentHeight(MinecraftClient client) {
        if (!isEnchantSectionVisibleForCurrentWorld()) {
            return 0;
        }
        int innerTop = 0;
        int buttonsTop = enchantOptionsEnchantButtonsTop(client, innerTop);
        int buttonsRight = Math.max(CARD_INSET + 1, enchantOptionsWidth - SCROLLBAR_TRACK_W - SCROLLBAR_PAD - 4);
        List<EnchantOptionButtonLayout> chips = layoutEnchantLacOptionButtons(client, CARD_INSET, buttonsTop, buttonsRight);
        int bottom = enchantOptionsWorldTitleY(innerTop) + client.textRenderer.fontHeight;
        for (EnchantOptionButtonLayout chip : chips) {
            bottom = Math.max(bottom, chip.bottom());
        }
        return bottom + 2;
    }

    private static int computeEnchantPanelContentHeight(MinecraftClient client) {
        int innerTop = 0;
        int innerLeft = CARD_INSET;
        int scrollTrackLeft = enchantPanelScrollTrackLeft(0, enchantWidth);
        int contentTop = enchantPanelScrollContentTop(client, innerTop, innerLeft, scrollTrackLeft);
        int titleY = contentTop + 1;
        int entriesTop = titleY + client.textRenderer.fontHeight + 4;
        int entriesRight = Math.max(CARD_INSET + 1, enchantWidth - SCROLLBAR_TRACK_W - SCROLLBAR_PAD - 4);
        List<EnchantOptionButtonLayout> entries = layoutEnchantPanelEntries(client, CARD_INSET, entriesTop, entriesRight);
        int bottom = Math.max(enchantTopBtnH() + 3, titleY + client.textRenderer.fontHeight);
        for (EnchantOptionButtonLayout entry : entries) {
            bottom = Math.max(bottom, entry.bottom());
        }
        return bottom + 2;
    }

    private static EnchantOptionSet currentEnchantOptionSet() {
        return switch (rewardDisplayZone) {
            case MINE -> ENCHANT_MINE_OPTIONS;
            case CHAMP -> ENCHANT_FARM_OPTIONS;
            case LAC -> ENCHANT_LAC_OPTIONS;
        };
    }

    private static boolean isEnchantSectionVisibleForCurrentWorld() {
        return currentEnchantOptionSet() != null;
    }

    private static String extractSystemChatPayload(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        s = ANSI_ESCAPE.matcher(s).replaceAll("");
        s = LOG_LINE_PREFIX.matcher(s).replaceFirst("").trim();
        s = LEGACY_FORMATTING_CODES.matcher(s).replaceAll("").trim();
        String prefix = "[System] [CHAT] ";
        if (!s.startsWith(prefix)) {
            return "";
        }
        return s.substring(prefix.length()).replace('\u00A0', ' ').trim();
    }

    private static List<EnchantOptionButtonLayout> layoutEnchantLacOptionButtons(MinecraftClient client, int left, int top, int rightExclusive) {
        EnchantOptionSet optionSet = currentEnchantOptionSet();
        if (optionSet == null) {
            return List.of();
        }
        String[] labels = optionSet.labels();
        ArrayList<EnchantOptionButtonLayout> out = new ArrayList<>(labels.length);
        int bodyRight = Math.max(left + 1, rightExclusive);
        int availableW = Math.max(1, bodyRight - left);
        int columns = Math.max(1, Math.min(labels.length,
                (availableW + ENCHANT_OPTIONS_COL_GAP_X) / (ENCHANT_OPTIONS_COL_MIN_W + ENCHANT_OPTIONS_COL_GAP_X)));
        int columnW = Math.max(1, (availableW - (columns - 1) * ENCHANT_OPTIONS_COL_GAP_X) / columns);
        int rowsPerColumn = (int) Math.ceil((double) labels.length / columns);
        for (int i = 0; i < labels.length; i++) {
            int column = i / rowsPerColumn;
            int row = i % rowsPerColumn;
            int x = left + column * (columnW + ENCHANT_OPTIONS_COL_GAP_X);
            int y = top + row * (ENCHANT_OPTIONS_BTN_H + ENCHANT_OPTIONS_BTN_GAP_Y);
            out.add(new EnchantOptionButtonLayout(i, x, y, columnW, ENCHANT_OPTIONS_BTN_H));
        }
        return out;
    }

    private static List<EnchantOptionButtonLayout> layoutEnchantPanelEntries(MinecraftClient client, int left, int top, int rightExclusive) {
        EnchantOptionSet optionSet = currentEnchantOptionSet();
        if (optionSet == null) {
            return List.of();
        }
        String[] labels = optionSet.labels();
        boolean[] optionEnabled = optionSet.enabled();
        int[] logCounts = optionSet.logCounts();
        ArrayList<EnchantOptionButtonLayout> out = new ArrayList<>(labels.length);
        ArrayList<Integer> visibleIndices = new ArrayList<>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            if (!optionEnabled[i]) {
                visibleIndices.add(i);
            }
        }
        if (visibleIndices.isEmpty()) {
            return out;
        }
        if (enchantPanelSortByCumuls) {
            double[] logSums = optionSet.logSums();
            visibleIndices.sort((a, b) -> Double.compare(logSums[b], logSums[a]));
        } else {
            visibleIndices.sort((a, b) -> Integer.compare(logCounts[b], logCounts[a]));
        }
        int bodyRight = Math.max(left + 1, rightExclusive);
        int availableW = Math.max(1, bodyRight - left);
        int columns = Math.max(1, Math.min(2,
                (availableW + ENCHANT_PANEL_COL_GAP_X) / (ENCHANT_PANEL_COL_MIN_W + ENCHANT_PANEL_COL_GAP_X)));
        int columnGap = columns > 1 ? ENCHANT_PANEL_COL_GAP_X : 0;
        int width = Math.max(1, (availableW - columnGap) / columns);
        int lineH = scaledEnchantPanelFontHeight(client) + 1;
        int rowH = enchantPanelEntryRowHeight(client, optionSet, visibleIndices, width, lineH);
        int rowsPerColumn = (int) Math.ceil((double) visibleIndices.size() / columns);
        for (int displayIndex = 0; displayIndex < visibleIndices.size(); displayIndex++) {
            int optionIndex = visibleIndices.get(displayIndex);
            int column = displayIndex / rowsPerColumn;
            int row = displayIndex % rowsPerColumn;
            int x = left + column * (width + columnGap);
            int y = top + row * (rowH + ENCHANT_PANEL_ENTRY_GAP_Y);
            out.add(new EnchantOptionButtonLayout(optionIndex, x, y, width, rowH));
        }
        return out;
    }

    private static int enchantPanelEntryRowHeight(MinecraftClient client, EnchantOptionSet optionSet,
            List<Integer> visibleIndices, int columnWidth, int lineH) {
        int twoLineH = 2 * lineH + ENCHANT_PANEL_ENTRY_STATS_GAP;
        for (int idx : visibleIndices) {
            if (!enchantPanelEntryStatsText(optionSet, idx).isEmpty()) {
                return twoLineH;
            }
        }
        return lineH;
    }

    private static String enchantPanelEntryProcPart(EnchantOptionSet optionSet, int idx) {
        int logHits = optionSet.logCounts()[idx];
        return logHits > 0 ? " +" + logHits : "";
    }

    private static String enchantPanelEntryCumulPart(EnchantOptionSet optionSet, int idx) {
        if (!enchantShowCumulativeSums) {
            return "";
        }
        return formatEnchantCumulDisplaySuffix(
                optionSet.logSumsByType()[idx], optionSet.logSums()[idx], optionSet.labels()[idx]);
    }

    private static String enchantPanelEntryStatsText(EnchantOptionSet optionSet, int idx) {
        return enchantPanelEntryProcPart(optionSet, idx) + enchantPanelEntryCumulPart(optionSet, idx);
    }

    private static void drawEnchantOptionButton(DrawContext context, MinecraftClient client, EnchantOptionButtonLayout chip) {
        EnchantOptionSet optionSet = currentEnchantOptionSet();
        if (optionSet == null) {
            return;
        }
        String[] labels = optionSet.labels();
        boolean[] optionEnabled = optionSet.enabled();
        boolean visibleInEnchant = !optionEnabled[chip.optionIndex()];
        int fill = visibleInEnchant ? 0x5566BB6A : 0x55333333;
        int border = visibleInEnchant ? 0xFF66BB6A : 0xFF90A4AE;
        context.fill(chip.x(), chip.y(), chip.right(), chip.bottom(), fill);
        context.drawBorder(chip.x(), chip.y(), chip.width(), chip.height(), border);
        String shown = client.textRenderer.trimToWidth(labels[chip.optionIndex()],
                Math.max(8, chip.width() - 2 * ENCHANT_OPTIONS_BTN_TEXT_PAD_X));
        int textW = client.textRenderer.getWidth(shown);
        int textX = chip.x() + (chip.width() - textW) / 2;
        int textY = chip.y() + (chip.height() - client.textRenderer.fontHeight) / 2 + 1;
        context.drawText(client.textRenderer, Text.literal(shown), textX, textY, visibleInEnchant ? 0xFFE8EAF6 : 0xFFB0BEC5, false);
    }

    private static int scaledEnchantPanelFontHeight(MinecraftClient client) {
        return Math.max(1, Math.round(client.textRenderer.fontHeight * ENCHANT_PANEL_TEXT_SCALE));
    }

    private static int scaledEnchantPanelTextWidth(MinecraftClient client, String text) {
        return Math.max(1, Math.round(client.textRenderer.getWidth(text) * ENCHANT_PANEL_TEXT_SCALE));
    }

    private static String trimScaledEnchantPanelTextToWidth(MinecraftClient client, String text, int maxScreenWidth) {
        if (text == null || text.isEmpty() || maxScreenWidth <= 0) {
            return "";
        }
        int maxUnscaledWidth = Math.max(1, (int) Math.floor(maxScreenWidth / ENCHANT_PANEL_TEXT_SCALE));
        String shown = client.textRenderer.trimToWidth(text, maxUnscaledWidth);
        while (!shown.isEmpty() && scaledEnchantPanelTextWidth(client, shown) > maxScreenWidth && maxUnscaledWidth > 1) {
            maxUnscaledWidth--;
            shown = client.textRenderer.trimToWidth(text, maxUnscaledWidth);
        }
        return shown;
    }

    private static void drawScaledEnchantPanelText(DrawContext context, MinecraftClient client, String text, int x, int y, int color) {
        context.getMatrices().pushMatrix();
        context.getMatrices().translate((float) x, (float) y);
        context.getMatrices().scale(ENCHANT_PANEL_TEXT_SCALE, ENCHANT_PANEL_TEXT_SCALE);
        context.drawText(client.textRenderer, Text.literal(text), 0, 0, color, false);
        context.getMatrices().popMatrix();
    }

    private static String enchantPanelDisplayLabel(String rawLabel) {
        return enchantPanelDisplayLabel(rawLabel, rewardDisplayZone);
    }

    private static String enchantPanelDisplayLabel(String rawLabel, RewardDisplayZone zone) {
        if (rawLabel == null || rawLabel.isEmpty()) {
            return "";
        }
        String upper = rawLabel.toUpperCase(Locale.FRANCE);
        return switch (zone) {
            case LAC -> switch (upper) {
                case "PRISE SPÉCIALE" -> "PRISE SPECIALE";
                case "ÉLÉVATION" -> "ELEVATION";
                case "ÉPÉÉ ÉLÉMENTAIRE" -> "ÉPÉE ÉLÉMENTAIRE";
                case "POSÉIDON" -> "POSEIDON";
                case "GÉNÉRATEUR DE LOSTCOINS" -> "GENERATEUR DE LOSTCOINS";
                default -> upper;
            };
            case MINE, CHAMP -> "GÉNÉRATEUR DE LOSTCOINS".equals(upper) ? "GENERATEUR DE LOSTCOINS" : upper;
        };
    }

    private static List<String> enchantPanelDetectableLabels(String rawLabel) {
        return enchantPanelDetectableLabels(rawLabel, rewardDisplayZone);
    }

    private static List<String> enchantPanelDetectableLabels(String rawLabel, RewardDisplayZone zone) {
        String exactLabel = rawLabel == null ? "" : rawLabel.toUpperCase(Locale.FRANCE);
        String displayLabel = enchantPanelDisplayLabel(rawLabel, zone);
        if (zone == RewardDisplayZone.LAC && "PRISE SPÉCIALE".equals(exactLabel)) {
            return List.of(displayLabel);
        }
        if (displayLabel.equals(exactLabel)) {
            return List.of(exactLabel);
        }
        return List.of(exactLabel, displayLabel);
    }

    private static void drawEnchantPanelEntry(DrawContext context, MinecraftClient client, EnchantOptionButtonLayout entry) {
        EnchantOptionSet optionSet = currentEnchantOptionSet();
        if (optionSet == null) {
            return;
        }
        int idx = entry.optionIndex();
        String label = enchantPanelDisplayLabel(optionSet.labels()[idx]);
        String procPart = enchantPanelEntryProcPart(optionSet, idx);
        String cumulPart = enchantPanelEntryCumulPart(optionSet, idx);
        int textLeft = entry.x() + ENCHANT_OPTIONS_BTN_TEXT_PAD_X;
        int textRight = entry.right() - ENCHANT_OPTIONS_BTN_TEXT_PAD_X;
        int availableW = Math.max(0, textRight - textLeft);
        if (availableW < 10) {
            return;
        }
        int lineH = scaledEnchantPanelFontHeight(client);
        String statsFull = procPart + cumulPart;
        boolean statsBelow = !statsFull.isEmpty();
        int labelY = entry.y();
        if (!statsBelow) {
            labelY += Math.max(0, (entry.height() - lineH) / 2);
        }
        String labelShown = trimScaledEnchantPanelTextToWidth(client, label, availableW);
        if (!labelShown.isEmpty()) {
            drawScaledEnchantPanelText(context, client, labelShown, textLeft, labelY, 0xFFF3E5F5);
        }
        if (!statsBelow) {
            return;
        }
        int statsY = labelY + lineH + ENCHANT_PANEL_ENTRY_STATS_GAP;
        String procShown = trimScaledEnchantPanelTextToWidth(client, procPart, availableW);
        int procW = procShown.isEmpty() ? 0 : scaledEnchantPanelTextWidth(client, procShown);
        if (!procShown.isEmpty()) {
            drawScaledEnchantPanelText(context, client, procShown, textLeft, statsY, 0xFFF3E5F5);
        }
        if (!cumulPart.isEmpty()) {
            String cumulShown = trimEnchantPanelCumulTextToWidth(client, cumulPart, Math.max(0, availableW - procW));
            if (!cumulShown.isEmpty()) {
                drawScaledEnchantPanelText(context, client, cumulShown, textLeft + procW, statsY, 0xFFCE93D8);
            }
        }
    }

    /** Coupe le texte cumul sans retirer les « + » devant les montants. */
    private static String trimEnchantPanelCumulTextToWidth(MinecraftClient client, String text, int maxScreenWidth) {
        String shown = trimScaledEnchantPanelTextToWidth(client, text, maxScreenWidth);
        if (shown.isEmpty() || shown.indexOf('+') >= 0) {
            return shown;
        }
        int digitStart = -1;
        for (int i = 0; i < shown.length(); i++) {
            char c = shown.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == ',') {
                digitStart = i;
                break;
            }
        }
        if (digitStart <= 0) {
            return shown;
        }
        return shown.substring(0, digitStart) + "+" + shown.substring(digitStart);
    }

    private static int sectionsEnchantOptionsBtnW(MinecraftClient client) {
        return client.textRenderer.getWidth(Text.translatable("feather_world_menu.enchant.options_button")) + 2;
    }

    /** Ligne sous-menu Reward / AFK : titre + badge infobulle uniquement (clear sur la ligne « ↳ clear : »). */
    private static void drawSubmenuRewardAfkRow(DrawContext context, MinecraftClient client, int sx, int ry, Text rowTitle,
            int scrollTrackLeft, int sectionIdx) {
        drawSectionsSubmenuRowTitleWithInfoBadge(context, client, sx, ry, rowTitle, scrollTrackLeft, sectionIdx);
    }

    /** Ligne « ↳ clear : » + boutons reset (/10min, /h, all). */
    private static void drawSubmenuRewardAfkClearSubRow(DrawContext context, MinecraftClient client, int sx, int ry) {
        RewardAfkClearSubLayout lay = layoutRewardAfkClearSubRow(client, sx);
        int btnTop = ry + 1;
        int btnH = SECTIONS_ROW_H - 2;

        int clearTenMinBtnW = sectionsRewardClearTenMinBtnW(client);
        int clearHourlyBtnW = sectionsRewardClearHourlyBtnW(client);
        int resetBtnW = sectionsRewardResetBtnW(client);

        Text clearPrefix = Text.literal(SECTIONS_REWARD_CLEAR_PREFIX);
        int yText = ry + 2;
        context.drawText(client.textRenderer, Text.literal(SECTIONS_REWARD_MONEY_BRANCH), lay.branchX(), yText, 0xFFB0BEC5, false);
        context.drawText(client.textRenderer, clearPrefix, lay.prefixX(), yText, 0xFFB0BEC5, false);

        context.fill(lay.tenMinLeft(), btnTop, lay.tenMinLeft() + clearTenMinBtnW, btnTop + btnH, 0x554386AC);
        context.drawBorder(lay.tenMinLeft(), btnTop, clearTenMinBtnW, btnH, 0xFF79C0CB);
        Text clearTenMinBtn = Text.translatable("feather_world_menu.section.reward_clear_10min");
        int clearTenMinW = client.textRenderer.getWidth(clearTenMinBtn);
        int clearTenMinX = lay.tenMinLeft() + (clearTenMinBtnW - clearTenMinW) / 2;
        int clearTenMinY = btnTop + (btnH - client.textRenderer.fontHeight) / 2 + 1;
        context.drawText(client.textRenderer, clearTenMinBtn, clearTenMinX, clearTenMinY, 0xFFE8EAF6, false);

        context.fill(lay.hourlyLeft(), btnTop, lay.hourlyLeft() + clearHourlyBtnW, btnTop + btnH, 0x55436CAC);
        context.drawBorder(lay.hourlyLeft(), btnTop, clearHourlyBtnW, btnH, 0xFF79A0CB);
        Text clearHourlyBtn = Text.translatable("feather_world_menu.section.reward_clear_hourly");
        int clearHourlyW = client.textRenderer.getWidth(clearHourlyBtn);
        int clearHourlyX = lay.hourlyLeft() + (clearHourlyBtnW - clearHourlyW) / 2;
        int clearHourlyY = btnTop + (btnH - client.textRenderer.fontHeight) / 2 + 1;
        context.drawText(client.textRenderer, clearHourlyBtn, clearHourlyX, clearHourlyY, 0xFFE8EAF6, false);

        context.fill(lay.resetLeft(), btnTop, lay.resetLeft() + resetBtnW, btnTop + btnH, 0x554356AC);
        context.drawBorder(lay.resetLeft(), btnTop, resetBtnW, btnH, 0xFF7986CB);
        Text clearBtn = Text.translatable("feather_world_menu.section.reward_reset");
        int clearW = client.textRenderer.getWidth(clearBtn);
        int clearX = lay.resetLeft() + (resetBtnW - clearW) / 2;
        int clearY = btnTop + (btnH - client.textRenderer.fontHeight) / 2 + 1;
        context.drawText(client.textRenderer, clearBtn, clearX, clearY, 0xFFE8EAF6, false);
    }

    private static void drawEnchantSubmenuOptionsRow(DrawContext context, MinecraftClient client, int sx, int ry) {
        int branchX = sx + CARD_INSET;
        int yText = ry + 2;
        int btnLeft = branchX + client.textRenderer.getWidth(Text.literal(SECTIONS_REWARD_MONEY_BRANCH)) + 2;
        int btnTop = ry + 1;
        int btnH = SECTIONS_ROW_H - 2;
        int btnW = sectionsEnchantOptionsBtnW(client);
        int fill = enchantOptionsMenuEnabled ? 0x555C6BC0 : 0x55333333;
        int border = enchantOptionsMenuEnabled ? 0xFF9FA8DA : 0xFF888888;
        Text label = Text.translatable("feather_world_menu.enchant.options_button");

        context.drawText(client.textRenderer, Text.literal(SECTIONS_REWARD_MONEY_BRANCH), branchX, yText, 0xFFB0BEC5, false);
        context.fill(btnLeft, btnTop, btnLeft + btnW, btnTop + btnH, fill);
        context.drawBorder(btnLeft, btnTop, btnW, btnH, border);
        context.drawText(client.textRenderer, label, btnLeft + 1, btnTop + (btnH - client.textRenderer.fontHeight) / 2 + 1, 0xFFE8EAF6, false);
    }

    /** Ligne Informations : case à gauche + titre + badge infobulle ; clic case ouvre {@link ModInfoScreen}. */
    private static void drawInformationsSubmenuRow(DrawContext context, MinecraftClient client, int sx, int ry, int scrollTrackLeft) {
        sectionsInformationsBadgeHitValid = false;
        int box = 8;
        int bx = sx + CARD_INSET;
        int by = ry + 2;
        if (informationsRowEnabled) {
            context.fill(bx, by, bx + box, by + box, 0xFF66BB6A);
            context.drawBorder(bx, by, box, box, 0xFF33691E);
        } else {
            context.fill(bx, by, bx + box, by + box, 0x55333333);
            context.drawBorder(bx, by, box, box, 0xFF888888);
        }
        Text rowTitle = Text.translatable("feather_world_menu.section.informations");
        int labelX = sectionsInfoBadgeLeftX(sx);
        int usableRight = scrollTrackLeft - 4;
        String tipStr = Text.translatable("feather_world_menu.section.informations.info").getString();
        int badgeRes = tipStr.isBlank() ? 0 : SECTIONS_INFO_BADGE_GAP + sectionsInfoBadgeWidth(client);
        int maxTitle = Math.max(8, usableRight - labelX - badgeRes);
        String titleShown = client.textRenderer.trimToWidth(rowTitle.getString(), maxTitle);
        context.drawText(client.textRenderer, Text.literal(titleShown), labelX, ry + 2, 0xFFECEFF1, false);
        int tw = client.textRenderer.getWidth(Text.literal(titleShown));
        if (badgeRes > 0) {
            int bi = labelX + tw + SECTIONS_INFO_BADGE_GAP;
            int bt = sectionsInfoBadgeTopInRow(client, ry);
            drawSectionsInfoBadge(context, client, bi, bt);
            sectionsInformationsBadgeHitLeft = bi;
            sectionsInformationsBadgeHitTop = bt;
            sectionsInformationsBadgeHitWidth = sectionsInfoBadgeWidth(client);
            sectionsInformationsBadgeHitHeight = client.textRenderer.fontHeight;
            sectionsInformationsBadgeHitValid = true;
        }
    }

    private static void drawSectionsPanel(DrawContext context, MinecraftClient client) {
        int sx = sectionsPosX;
        int sy = sectionsPosY;
        int sw = sectionsWidth;
        int sh = sectionsHeight;
        int sr = sx + sw;
        int sb = sy + sh;
        int rowTop = sy + SECTIONS_HEADER_H + 2;

        context.fill(sx + 2, sy + 2, sr + 2, sb + 2, 0x77000000);
        context.fill(sx, sy, sr, sb, 0xDD1A237E);
        context.drawBorder(sx, sy, sw, sh, 0xFF90CAF9);
        context.drawBorder(sx + 1, sy + 1, sw - 2, sh - 2, 0x66448AFF);
        drawDragHandle(context, sx, sy, sw, 0xFF90CAF9);

        int innerTop = sy + SECTIONS_HEADER_H + 2;
        int innerBottom = sb - SECTIONS_PANEL_FOOTER_H;
        int scrollTrackLeft = sr - SCROLLBAR_TRACK_W - SCROLLBAR_PAD;
        int maxSecScroll = computeSectionsPanelScrollMax(client, sy, sh);

        Text submenuTitle = Text.translatable("feather_world_menu.submenu_title");
        int submenuTitleMax = Math.max(12, scrollTrackLeft - (sx + CARD_TEXT_X) - 4);
        String submenuTitleShown = client.textRenderer.trimToWidth(submenuTitle.getString(), submenuTitleMax);
        context.drawText(client.textRenderer, Text.literal(submenuTitleShown), sx + CARD_TEXT_X, sy + 4, 0xFFE3F2FD, false);

        context.enableScissor(sx + 1, innerTop, scrollTrackLeft, innerBottom);
        clearSectionsPanelInfoBadgeHits();
        boolean matrixPushed = false;
        try {
            context.getMatrices().pushMatrix();
            matrixPushed = true;
            context.getMatrices().translate(0f, (float) -sectionsScrollPx);

            boolean[] sectionOn = new boolean[SECTIONS_SECTION_ROWS];
            sectionOn[0] = showHudSession;
            sectionOn[1] = showHudPrestige;
            sectionOn[2] = showHudReward;
            sectionOn[3] = showHudAfk;
            sectionOn[4] = showHudFarm;
            sectionOn[5] = showHudDouble;
            sectionOn[6] = showHudPotions;
            sectionOn[SECTIONS_LOG_ROW_INDEX] = logsMenuEnabled;
            sectionOn[SECTIONS_ENCHANT_ROW_INDEX] = enchantMenuEnabled;
            sectionOn[SECTIONS_AUTOFISH_ROW_INDEX] = autoFishLogTriggerEnabled;

            for (int i = 0; i < SECTIONS_SECTION_ROWS; i++) {
                int ry = rowTop + sectionRowIndex(i) * SECTIONS_ROW_H;
                int box = 8;
                int bx = sx + CARD_INSET;
                int by = ry + 2;
                if (sectionOn[i]) {
                    context.fill(bx, by, bx + box, by + box, 0xFF66BB6A);
                    context.drawBorder(bx, by, box, box, 0xFF33691E);
                } else {
                    context.fill(bx, by, bx + box, by + box, 0x55333333);
                    context.drawBorder(bx, by, box, box, 0xFF888888);
                }
                Text rowTitle = Text.translatable(SECTION_PANEL_ROW_KEYS[i]);
                if (i == 1) {
                    drawPrestigeSubmenuTitleRow(context, client, sx, ry, rowTitle, scrollTrackLeft, i);
                    if (showHudPrestige) {
                        drawPrestigeSubmenuActionsRow(context, client, sx, ry + SECTIONS_ROW_H);
                    }
                } else if (i == 2) {
                    drawSubmenuRewardAfkRow(context, client, sx, ry, rowTitle, scrollTrackLeft, i);
                    if (showHudReward) {
                        drawSubmenuCacherAndMoneyRow(context, client, sx, ry + SECTIONS_ROW_H, hideRewardTenMin, hideRewardHourly, hideRewardMoneyRow);
                        drawSubmenuRewardAfkClearSubRow(context, client, sx, ry + 2 * SECTIONS_ROW_H);
                    }
                } else if (i == 3) {
                    drawSubmenuRewardAfkRow(context, client, sx, ry, rowTitle, scrollTrackLeft, i);
                    if (showHudAfk) {
                        drawSubmenuCacherAndMoneyRow(context, client, sx, ry + SECTIONS_ROW_H, hideAfkTenMin, hideAfkHourly, hideAfkMoneyRow);
                        drawSubmenuRewardAfkClearSubRow(context, client, sx, ry + 2 * SECTIONS_ROW_H);
                    }
                } else if (i == SECTIONS_ENCHANT_ROW_INDEX) {
                    drawSectionsSubmenuRowTitleWithInfoBadge(context, client, sx, ry, rowTitle, scrollTrackLeft, i);
                    if (enchantMenuEnabled) {
                        drawEnchantSubmenuOptionsRow(context, client, sx, ry + SECTIONS_ROW_H);
                    }
                } else {
                    drawSectionsSubmenuRowTitleWithInfoBadge(context, client, sx, ry, rowTitle, scrollTrackLeft, i);
                }
            }

            int infoRowY = sectionsInformationsRowTop(rowTop);
            drawInformationsSubmenuRow(context, client, sx, infoRowY, scrollTrackLeft);
            int keyHeaderY = infoRowY + SECTIONS_ROW_H + SECTIONS_KEY_HEADER_GAP;
            int keyBtnW = sectionsKeyBtnW(client);
            int keyBtnLeft = sr - CARD_INSET - keyBtnW;
            int touchesMax = Math.max(8, keyBtnLeft - (sx + CARD_TEXT_X) - 6);
            context.drawText(client.textRenderer, Text.literal(client.textRenderer.trimToWidth("Touches", touchesMax)), sx + CARD_TEXT_X, keyHeaderY, 0xFFB3E5FC, false);
            int[] keyCodes = {
                    FeatherWorldMenuClient.getToggleVisibleKeyCode(),
                    FeatherWorldMenuClient.getPrestigePauseKeyCode()
            };
            int keyRowsStartY = keyHeaderY + SECTIONS_KEY_ROW_H;
            for (int ki = 0; ki < SECTIONS_KEY_BINDING_LABELS.length; ki++) {
                int ky = keyRowsStartY + ki * SECTIONS_KEY_ROW_H;
                int keyLabelMax = Math.max(8, keyBtnLeft - (sx + CARD_TEXT_X) - 4);
                String keyLabelShown = client.textRenderer.trimToWidth(SECTIONS_KEY_BINDING_LABELS[ki], keyLabelMax);
                context.drawText(client.textRenderer, Text.literal(keyLabelShown), sx + CARD_TEXT_X, ky + 1, 0xFFECEFF1, false);
                int btnTop = ky;
                int btnH = SECTIONS_KEY_ROW_H - 1;
                int bg = captureBindingIndex == ki ? 0xAA1565C0 : 0x55394979;
                int border = captureBindingIndex == ki ? 0xFFE3F2FD : 0xFF7986CB;
                context.fill(keyBtnLeft, btnTop, keyBtnLeft + keyBtnW, btnTop + btnH, bg);
                context.drawBorder(keyBtnLeft, btnTop, keyBtnW, btnH, border);
                String shown = captureBindingIndex == ki ? "Appuyez..." : keyDisplayName(keyCodes[ki]);
                shown = client.textRenderer.trimToWidth(shown, Math.max(8, keyBtnW - 4));
                int tw = client.textRenderer.getWidth(shown);
                int tx = keyBtnLeft + (keyBtnW - tw) / 2;
                int ty = btnTop + (btnH - client.textRenderer.fontHeight) / 2 + 1;
                context.drawText(client.textRenderer, Text.literal(shown), tx, ty, 0xFFE8EAF6, false);
            }

        } finally {
            if (matrixPushed) {
                context.getMatrices().popMatrix();
            }
            context.disableScissor();
        }

        int shLeft = sr - SECTIONS_RESIZE_VIS_INSET - SECTIONS_RESIZE_VIS_SIZE;
        int shTop = sb - SECTIONS_RESIZE_VIS_INSET - SECTIONS_RESIZE_VIS_SIZE;
        context.fill(shLeft, shTop, shLeft + SECTIONS_RESIZE_VIS_SIZE, shTop + SECTIONS_RESIZE_VIS_SIZE, 0xFF64B5F6);
        drawDragResizeHintLeftOfSquare(context, client, shLeft, shTop, SECTIONS_RESIZE_VIS_SIZE, sx + CARD_INSET, 0xFF90A4AE);
        drawVerticalScrollbar(context, scrollTrackLeft, innerTop, innerBottom, sectionsScrollPx, maxSecScroll, 0x5548486E, 0xFF90CAF9);

        /* Infobulles après la scrollbar : sinon elles passent visuellement sous la piste de défilement. */
        if (client.getWindow() != null) {
            int mouseX = (int) (client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth());
            int mouseY = (int) (client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight());
            int adjY = mouseY + sectionsScrollPx;
            String tooltip = "";
            if (mouseInsideSectionsScrollableViewport(mouseX, mouseY, sx, sy, sw, sh)
                    && mouseX >= sx + 4 && mouseX < scrollTrackLeft - 4) {
                int hoveredInfoRow = -1;
                for (int i = 0; i < SECTIONS_SECTION_ROWS; i++) {
                    if (!sectionsInfoBadgeHitValid[i] || sectionsPanelSectionRowInfoTooltip(i).isBlank()) {
                        continue;
                    }
                    int bl = sectionsInfoBadgeHitLeft[i];
                    int bt = sectionsInfoBadgeHitTop[i];
                    if (mouseX >= bl && mouseX < bl + sectionsInfoBadgeHitWidth[i]
                            && adjY >= bt && adjY < bt + sectionsInfoBadgeHitHeight[i]) {
                        hoveredInfoRow = i;
                        break;
                    }
                }
                if (hoveredInfoRow >= 0) {
                    tooltip = sectionsPanelSectionRowInfoTooltip(hoveredInfoRow);
                } else if (sectionsInformationsBadgeHitValid) {
                    String tipInfo = Text.translatable("feather_world_menu.section.informations.info").getString();
                    int bl = sectionsInformationsBadgeHitLeft;
                    int bt = sectionsInformationsBadgeHitTop;
                    if (!tipInfo.isBlank() && mouseX >= bl && mouseX < bl + sectionsInformationsBadgeHitWidth
                            && adjY >= bt && adjY < bt + sectionsInformationsBadgeHitHeight) {
                        tooltip = tipInfo;
                    }
                }
            }
            if (!tooltip.isEmpty()) {
                drawMouseInfoTooltip(context, client, mouseX, mouseY, tooltip, scrollTrackLeft - 2, innerTop + 1, innerBottom - 1);
            }
        }
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return "%02dh%02dm%02ds".formatted(hours, minutes, seconds);
    }

    private static long parseLongLenient(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double parseDoubleLenient(String s) {
        if (s == null || s.isBlank()) {
            return 0D;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0D;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int hudLayerArgb(int argb, boolean forceOpaque) {
        if (!forceOpaque) {
            return argb;
        }
        return (argb & 0x00FFFFFF) | 0xFF000000;
    }

    private static final List<String> REWARD_KEYS_CHAMP = List.of("money", "exp", "orbes", "cultures");
    private static final List<String> REWARD_KEYS_MINE = List.of("money", "exp", "gemmes", "blocs");
    private static final List<String> REWARD_KEYS_LAC = List.of("money", "exp", "perles", "poissons");

    /** Ordre d’affichage Reward + AFK selon la zone détectée (scoreboard / layout). */
    private static List<String> rewardKeysForZone(RewardDisplayZone z) {
        return switch (z) {
            case CHAMP -> REWARD_KEYS_CHAMP;
            case MINE -> REWARD_KEYS_MINE;
            case LAC -> REWARD_KEYS_LAC;
        };
    }

    /** Clés affichées Reward / AFK : retire « money » selon la case du sous-menu (Reward vs AFK). */
    private static List<String> rewardKeysForHudDisplayList(RewardDisplayZone z, boolean afkSection) {
        List<String> keys = rewardKeysForZone(z);
        boolean hideMoney = afkSection ? hideAfkMoneyRow : hideRewardMoneyRow;
        if (!hideMoney) {
            return keys;
        }
        ArrayList<String> out = new ArrayList<>(keys.size());
        for (String k : keys) {
            if (!"money".equals(k)) {
                out.add(k);
            }
        }
        return out;
    }

    /** Largeur d'un bouton du sous-menu : juste le texte + {@link #SECTIONS_BTN_TEXT_PAD} (1 px de chaque côté). */
    private static int sectionsBtnWidth(MinecraftClient client, Text label) {
        return client.textRenderer.getWidth(label) + SECTIONS_BTN_TEXT_PAD;
    }

    private static int sectionsRewardResetBtnW(MinecraftClient client) {
        return sectionsBtnWidth(client, Text.translatable("feather_world_menu.section.reward_reset"));
    }

    private static int sectionsRewardClearHourlyBtnW(MinecraftClient client) {
        return sectionsBtnWidth(client, Text.translatable("feather_world_menu.section.reward_clear_hourly"));
    }

    private static int sectionsRewardClearTenMinBtnW(MinecraftClient client) {
        return sectionsBtnWidth(client, Text.translatable("feather_world_menu.section.reward_clear_10min"));
    }

    /** Largeur du bouton « /10min » sous-ligne « cacher : » : texte collé au bord (0 px de padding). */
    private static int sectionsCacherTenMinBtnW(MinecraftClient client) {
        return client.textRenderer.getWidth(Text.literal("/10min"));
    }

    /** Largeur du bouton « /h » sous-ligne « cacher : » : texte collé au bord. */
    private static int sectionsCacherHourBtnW(MinecraftClient client) {
        return client.textRenderer.getWidth(Text.literal("/h"));
    }

    /**
     * Rangées (multiples de {@link #SECTIONS_ROW_H}) occupées par une ligne du sous-menu.
     * Prestige (1) : ligne titre + ligne « ↳ » clear/pause si la section est activée.
     * Reward / AFK (2–3) : titre + « cacher » + « clear » si la section est activée.
     */
    private static int sectionSubRowCount(int sectionIdx) {
        if (sectionIdx == 1) {
            return showHudPrestige ? 2 : 1;
        }
        if (sectionIdx == 2) {
            return showHudReward ? 3 : 1;
        }
        if (sectionIdx == 3) {
            return showHudAfk ? 3 : 1;
        }
        if (sectionIdx == SECTIONS_ENCHANT_ROW_INDEX) {
            return enchantMenuEnabled ? 2 : 1;
        }
        return 1;
    }

    private static int sectionRowIndex(int sectionIdx) {
        int row = 0;
        for (int s = 0; s < sectionIdx; s++) {
            row += sectionSubRowCount(s);
        }
        return row;
    }

    /** Total de rangées de 14 px (sections + sous-lignes Prestige, Reward, AFK). */
    private static int sectionRowsTotal() {
        int t = 0;
        for (int s = 0; s < SECTIONS_SECTION_ROWS; s++) {
            t += sectionSubRowCount(s);
        }
        return t;
    }

    /** Nombre de lignes affichées dans une entrée Reward / AFK selon les cases « cacher ». */
    private static int rewardEntryLineCount(boolean tenMinHidden, boolean hourHidden) {
        int n = 4; // libellé, /10min, /h, session
        if (tenMinHidden) n--;
        if (hourHidden) n--;
        return n;
    }

    /** Hauteur d’une entrée Reward/AFK en mode empilé (carte &lt; {@link #REWARD_STACKED_BREAKPOINT}). */
    private static int rewardStackedEntryStepPx(MinecraftClient client, int linesPerEntry) {
        int fh = client.textRenderer.fontHeight;
        return linesPerEntry * fh + 1;
    }

    /** Hauteur d’une entrée en mode ligne unique (évite le chevauchement quand {@code fontHeight} &gt; 9). */
    private static int rewardWideEntryStepPx(MinecraftClient client) {
        return client.textRenderer.fontHeight + 2;
    }

    /** Largeur commune des boutons de raccourcis clavier : max entre « Appuyez... » et les noms de touches actuels. */
    private static int sectionsKeyBtnW(MinecraftClient client) {
        var tr = client.textRenderer;
        int w = tr.getWidth(Text.literal("Appuyez..."));
        int[] keyCodes = {
                FeatherWorldMenuClient.getToggleVisibleKeyCode(),
                FeatherWorldMenuClient.getPrestigePauseKeyCode()
        };
        for (int kc : keyCodes) {
            w = Math.max(w, tr.getWidth(Text.literal(keyDisplayName(kc))));
        }
        return w + SECTIONS_BTN_TEXT_PAD;
    }

    private static int detectPressedKeyCode(long window) {
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS) {
                return key;
            }
        }
        return -1;
    }

    private static void applyBindingCode(int bindingIndex, int code) {
        switch (bindingIndex) {
            case 0 -> FeatherWorldMenuClient.setToggleVisibleKeyCode(code);
            case 1 -> FeatherWorldMenuClient.setPrestigePauseKeyCode(code);
            default -> {
            }
        }
    }

    private static String keyDisplayName(int keyCode) {
        if (keyCode < 0 || keyCode > GLFW.GLFW_KEY_LAST) {
            return "(" + keyCode + ")";
        }
        try {
            return InputUtil.Type.KEYSYM.createFromCode(keyCode).getLocalizedText().getString();
        } catch (RuntimeException e) {
            return "(" + keyCode + ")";
        }
    }

    private enum RewardDisplayZone {
        CHAMP,
        MINE,
        LAC;

        static RewardDisplayZone fromConfig(String raw) {
            if (raw == null || raw.isBlank()) {
                return CHAMP;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return CHAMP;
            }
        }
    }

    private static final class StatLine {
        private final String label;
        private final int color;

        private StatLine(String label, int color) {
            this.label = label;
            this.color = color;
        }

        private String label() {
            return label;
        }

        private int color() {
            return color;
        }

        private void reset() {
        }
    }

    /**
     * Une session figée à la sortie du monde / fermeture du client : durée « Session » du HUD
     * et totaux session Reward et AFK (Money / Exp par monde + deux ressources par monde depuis le même
     * suivi que le HUD ; hors Money/Exp les totaux sont globaux session, comme dans le HUD).
     */
    private record LogSessionArchiveEntry(
            long savedAtWallMs,
            long sessionMs,
            double champMoney,
            double champExp,
            double champOrbes,
            double champCultures,
            double mineMoney,
            double mineExp,
            double mineGemmes,
            double mineBlocs,
            double lacMoney,
            double lacExp,
            double lacPerles,
            double lacPoissons,
            double afkChampMoney,
            double afkChampExp,
            double afkChampOrbes,
            double afkChampCultures,
            double afkMineMoney,
            double afkMineExp,
            double afkMineGemmes,
            double afkMineBlocs,
            double afkLacMoney,
            double afkLacExp,
            double afkLacPerles,
            double afkLacPoissons) {

        private static LogSessionArchiveEntry captureAt(long now, long sessionMs) {
            List<String> ck = rewardKeysForZone(RewardDisplayZone.CHAMP);
            List<String> km = rewardKeysForZone(RewardDisplayZone.MINE);
            List<String> kl = rewardKeysForZone(RewardDisplayZone.LAC);
            double cM = rewardMoneyByZone.get(RewardDisplayZone.CHAMP).getSessionCumulative();
            double cE = rewardExpByZone.get(RewardDisplayZone.CHAMP).getSessionCumulative();
            double cO = REWARDS.get(ck.get(2)).getSessionCumulative();
            double cC = REWARDS.get(ck.get(3)).getSessionCumulative();
            double mM = rewardMoneyByZone.get(RewardDisplayZone.MINE).getSessionCumulative();
            double mE = rewardExpByZone.get(RewardDisplayZone.MINE).getSessionCumulative();
            double mG = REWARDS.get(km.get(2)).getSessionCumulative();
            double mB = REWARDS.get(km.get(3)).getSessionCumulative();
            double lM = rewardMoneyByZone.get(RewardDisplayZone.LAC).getSessionCumulative();
            double lE = rewardExpByZone.get(RewardDisplayZone.LAC).getSessionCumulative();
            double lP = REWARDS.get(kl.get(2)).getSessionCumulative();
            double lF = REWARDS.get(kl.get(3)).getSessionCumulative();
            double acM = afkMoneyByZone.get(RewardDisplayZone.CHAMP).getSessionCumulative();
            double acE = afkExpByZone.get(RewardDisplayZone.CHAMP).getSessionCumulative();
            double acO = AFK_REWARDS.get(ck.get(2)).getSessionCumulative();
            double acC = AFK_REWARDS.get(ck.get(3)).getSessionCumulative();
            double amM = afkMoneyByZone.get(RewardDisplayZone.MINE).getSessionCumulative();
            double amE = afkExpByZone.get(RewardDisplayZone.MINE).getSessionCumulative();
            double amG = AFK_REWARDS.get(km.get(2)).getSessionCumulative();
            double amB = AFK_REWARDS.get(km.get(3)).getSessionCumulative();
            double alM = afkMoneyByZone.get(RewardDisplayZone.LAC).getSessionCumulative();
            double alE = afkExpByZone.get(RewardDisplayZone.LAC).getSessionCumulative();
            double alP = AFK_REWARDS.get(kl.get(2)).getSessionCumulative();
            double alF = AFK_REWARDS.get(kl.get(3)).getSessionCumulative();
            return new LogSessionArchiveEntry(now, sessionMs, cM, cE, cO, cC, mM, mE, mG, mB, lM, lE, lP, lF, acM, acE, acO, acC, amM, amE, amG, amB, alM, alE, alP, alF);
        }

        private static LogSessionArchiveEntry readSlot(Properties p, int idx) {
            String b = "logs.session_archive." + idx + ".";
            long saved = parseLongLenient(p.getProperty(b + "savedAt", "0"));
            long sess = parseLongLenient(p.getProperty(b + "sessionMs", "0"));
            double cm = parseDoubleLenient(p.getProperty(b + "CHAMP.money", "0"));
            double ce = parseDoubleLenient(p.getProperty(b + "CHAMP.exp", "0"));
            double co = parseDoubleLenient(p.getProperty(b + "CHAMP.orbes", "0"));
            double cc = parseDoubleLenient(p.getProperty(b + "CHAMP.cultures", "0"));
            double mm = parseDoubleLenient(p.getProperty(b + "MINE.money", "0"));
            double me = parseDoubleLenient(p.getProperty(b + "MINE.exp", "0"));
            double mg = parseDoubleLenient(p.getProperty(b + "MINE.gemmes", "0"));
            double mb = parseDoubleLenient(p.getProperty(b + "MINE.blocs", "0"));
            double lm = parseDoubleLenient(p.getProperty(b + "LAC.money", "0"));
            double le = parseDoubleLenient(p.getProperty(b + "LAC.exp", "0"));
            double lp = parseDoubleLenient(p.getProperty(b + "LAC.perles", "0"));
            double lf = parseDoubleLenient(p.getProperty(b + "LAC.poissons", "0"));
            double acm = parseDoubleLenient(p.getProperty(b + "CHAMP.afk.money", "0"));
            double ace = parseDoubleLenient(p.getProperty(b + "CHAMP.afk.exp", "0"));
            double aco = parseDoubleLenient(p.getProperty(b + "CHAMP.afk.orbes", "0"));
            double acc = parseDoubleLenient(p.getProperty(b + "CHAMP.afk.cultures", "0"));
            double amm = parseDoubleLenient(p.getProperty(b + "MINE.afk.money", "0"));
            double ame = parseDoubleLenient(p.getProperty(b + "MINE.afk.exp", "0"));
            double amg = parseDoubleLenient(p.getProperty(b + "MINE.afk.gemmes", "0"));
            double amb = parseDoubleLenient(p.getProperty(b + "MINE.afk.blocs", "0"));
            double alm = parseDoubleLenient(p.getProperty(b + "LAC.afk.money", "0"));
            double ale = parseDoubleLenient(p.getProperty(b + "LAC.afk.exp", "0"));
            double alp = parseDoubleLenient(p.getProperty(b + "LAC.afk.perles", "0"));
            double alf = parseDoubleLenient(p.getProperty(b + "LAC.afk.poissons", "0"));
            return new LogSessionArchiveEntry(saved, sess, cm, ce, co, cc, mm, me, mg, mb, lm, le, lp, lf, acm, ace, aco, acc, amm, ame, amg, amb, alm, ale, alp, alf);
        }

        private void writeSlot(Properties p, int idx) {
            String b = "logs.session_archive." + idx + ".";
            p.setProperty(b + "savedAt", String.valueOf(savedAtWallMs));
            p.setProperty(b + "sessionMs", String.valueOf(sessionMs));
            p.setProperty(b + "CHAMP.money", String.valueOf(champMoney));
            p.setProperty(b + "CHAMP.exp", String.valueOf(champExp));
            p.setProperty(b + "CHAMP.orbes", String.valueOf(champOrbes));
            p.setProperty(b + "CHAMP.cultures", String.valueOf(champCultures));
            p.setProperty(b + "MINE.money", String.valueOf(mineMoney));
            p.setProperty(b + "MINE.exp", String.valueOf(mineExp));
            p.setProperty(b + "MINE.gemmes", String.valueOf(mineGemmes));
            p.setProperty(b + "MINE.blocs", String.valueOf(mineBlocs));
            p.setProperty(b + "LAC.money", String.valueOf(lacMoney));
            p.setProperty(b + "LAC.exp", String.valueOf(lacExp));
            p.setProperty(b + "LAC.perles", String.valueOf(lacPerles));
            p.setProperty(b + "LAC.poissons", String.valueOf(lacPoissons));
            p.setProperty(b + "CHAMP.afk.money", String.valueOf(afkChampMoney));
            p.setProperty(b + "CHAMP.afk.exp", String.valueOf(afkChampExp));
            p.setProperty(b + "CHAMP.afk.orbes", String.valueOf(afkChampOrbes));
            p.setProperty(b + "CHAMP.afk.cultures", String.valueOf(afkChampCultures));
            p.setProperty(b + "MINE.afk.money", String.valueOf(afkMineMoney));
            p.setProperty(b + "MINE.afk.exp", String.valueOf(afkMineExp));
            p.setProperty(b + "MINE.afk.gemmes", String.valueOf(afkMineGemmes));
            p.setProperty(b + "MINE.afk.blocs", String.valueOf(afkMineBlocs));
            p.setProperty(b + "LAC.afk.money", String.valueOf(afkLacMoney));
            p.setProperty(b + "LAC.afk.exp", String.valueOf(afkLacExp));
            p.setProperty(b + "LAC.afk.perles", String.valueOf(afkLacPerles));
            p.setProperty(b + "LAC.afk.poissons", String.valueOf(afkLacPoissons));
        }

        private boolean isQuickDuplicateOf(LogSessionArchiveEntry last) {
            if (Math.abs(savedAtWallMs - last.savedAtWallMs) > 2500L) {
                return false;
            }
            return sessionMs == last.sessionMs
                    && almostEq(champMoney, last.champMoney)
                    && almostEq(champExp, last.champExp)
                    && almostEq(champOrbes, last.champOrbes)
                    && almostEq(champCultures, last.champCultures)
                    && almostEq(mineMoney, last.mineMoney)
                    && almostEq(mineExp, last.mineExp)
                    && almostEq(mineGemmes, last.mineGemmes)
                    && almostEq(mineBlocs, last.mineBlocs)
                    && almostEq(lacMoney, last.lacMoney)
                    && almostEq(lacExp, last.lacExp)
                    && almostEq(lacPerles, last.lacPerles)
                    && almostEq(lacPoissons, last.lacPoissons)
                    && almostEq(afkChampMoney, last.afkChampMoney)
                    && almostEq(afkChampExp, last.afkChampExp)
                    && almostEq(afkChampOrbes, last.afkChampOrbes)
                    && almostEq(afkChampCultures, last.afkChampCultures)
                    && almostEq(afkMineMoney, last.afkMineMoney)
                    && almostEq(afkMineExp, last.afkMineExp)
                    && almostEq(afkMineGemmes, last.afkMineGemmes)
                    && almostEq(afkMineBlocs, last.afkMineBlocs)
                    && almostEq(afkLacMoney, last.afkLacMoney)
                    && almostEq(afkLacExp, last.afkLacExp)
                    && almostEq(afkLacPerles, last.afkLacPerles)
                    && almostEq(afkLacPoissons, last.afkLacPoissons);
        }

        private static boolean almostEq(double a, double b) {
            return Double.compare(a, b) == 0 || Math.abs(a - b) < 1e-3 + 1e-6 * Math.max(1D, Math.max(Math.abs(a), Math.abs(b)));
        }

        private double champExtra0() {
            return champOrbes;
        }

        private double champExtra1() {
            return champCultures;
        }

        private double mineExtra0() {
            return mineGemmes;
        }

        private double mineExtra1() {
            return mineBlocs;
        }

        private double lacExtra0() {
            return lacPerles;
        }

        private double lacExtra1() {
            return lacPoissons;
        }

        private double afkChampExtra0() {
            return afkChampOrbes;
        }

        private double afkChampExtra1() {
            return afkChampCultures;
        }

        private double afkMineExtra0() {
            return afkMineGemmes;
        }

        private double afkMineExtra1() {
            return afkMineBlocs;
        }

        private double afkLacExtra0() {
            return afkLacPerles;
        }

        private double afkLacExtra1() {
            return afkLacPoissons;
        }
    }

    private record RewardDelta(long timeMs, double amount) {
    }

    private static final class RewardTracker {
        /** Fenêtre /h glissante stricte : au-delà de 1 h, le plus ancien est retiré du total. */
        private static final long ROLLING_HOUR_MS = 3_600_000L;
        /** Fenêtre /10min glissante stricte : au-delà de 10 min, le plus ancien ne compte plus. */
        private static final long ROLLING_10_MIN_MS = 600_000L;

        private final String label;
        private final int color;
        /**
         * Somme des montants lus sur chaque ligne (chaque message = un gain à ajouter : ex. +2 puis +1 ⇒ 3).
         */
        private double sessionCumulative;
        /** Montants horodatés sur 60 min glissantes pour « /h » (chaque entrée = un gain parsé). */
        private final ArrayDeque<RewardDelta> deltasLastHourWindow = new ArrayDeque<>();
        /**
         * Horodatage (ms) du dernier clic sur « /10min » : la somme {@code /10min} ignore tous
         * les gains parsés avant cette date (le {@code /h} reste, lui, inchangé).
         */
        private long tenMinResetMs = 0L;
        /**
         * Horodatage (ms) du dernier clic sur « /h » : la somme {@code /h} ignore tous les gains
         * parsés avant cette date (le {@code /10min} et le total session restent inchangés).
         */
        private long hourResetMs = 0L;

        private RewardTracker(String label, int color) {
            this.label = label;
            this.color = color;
        }

        private void reset() {
            sessionCumulative = 0D;
            deltasLastHourWindow.clear();
            tenMinResetMs = 0L;
            hourResetMs = 0L;
        }

        /** Repart le compteur « /h » à zéro sans toucher au « /10min » ni au total session. */
        private void resetHourly(long now) {
            hourResetMs = now;
        }

        /** Repart le compteur « /10min » à zéro sans toucher au « /h » ni au total session. */
        private void resetTenMinutes(long now) {
            tenMinResetMs = now;
        }

        /**
         * Somme des gains parsés sur la fenêtre glissante d’1 h (entrées &gt; 1 h retirées).
         * Si « /h » a été cliqué, on ignore tous les gains plus anciens que ce reset.
         */
        private double sumGainsLastRollingHour(long now) {
            dropDeltasOlderThan(now - ROLLING_HOUR_MS);
            double sum = 0D;
            for (RewardDelta d : deltasLastHourWindow) {
                if (d.timeMs >= hourResetMs) {
                    sum += d.amount;
                }
            }
            return sum;
        }

        /**
         * Somme des gains sur les 10 dernières minutes glissantes (sous-ensemble de la fenêtre /h).
         * Retire du deque tout gain plus vieux que 1 h (comme {@link #sumGainsLastRollingHour}) pour que le plus ancien
         * sorte bien à la limite de chaque fenêtre.
         * Si « /10min » a été cliqué, on ignore en plus les gains plus anciens que ce reset.
         */
        private double sumGainsLast10Minutes(long now) {
            dropDeltasOlderThan(now - ROLLING_HOUR_MS);
            long cutoff = Math.max(now - ROLLING_10_MIN_MS, tenMinResetMs);
            double sum = 0D;
            for (RewardDelta d : deltasLastHourWindow) {
                if (d.timeMs >= cutoff) {
                    sum += d.amount;
                }
            }
            return sum;
        }

        private void dropDeltasOlderThan(long minTimeKeep) {
            while (!deltasLastHourWindow.isEmpty() && deltasLastHourWindow.peekFirst().timeMs < minTimeKeep) {
                deltasLastHourWindow.pollFirst();
            }
        }

        private void recordValue(double v, long now) {
            sessionCumulative += v;
            deltasLastHourWindow.addLast(new RewardDelta(now, v));
            dropDeltasOlderThan(now - ROLLING_HOUR_MS);
        }

        /** Total session (somme des gains parsés) pour archivage panneau Logs. */
        private double getSessionCumulative() {
            return sessionCumulative;
        }
    }

    private static final class PotionSlot {
        private final String label;
        private String percentDisplay = "--%";
        private long endMs = 0L;
        private String lastDetectedPhrase = "";

        private PotionSlot(String label) {
            this.label = label;
        }

        private void clear() {
            percentDisplay = "--%";
            endMs = 0L;
            lastDetectedPhrase = "";
        }
    }
}
