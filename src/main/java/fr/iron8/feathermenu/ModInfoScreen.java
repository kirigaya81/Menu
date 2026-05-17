package fr.iron8.feathermenu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Texte d’accueil et prérequis du mod, ouvert depuis le sous-menu Paramètres.
 */
public class ModInfoScreen extends Screen {
    private static final String KEY_TITLE = "feather_world_menu.informations.title";
    private static final String KEY_BODY = "feather_world_menu.informations.body";

    private static final int BODY_MAX_WIDTH = 360;
    private static final int PANEL_PAD = 20;
    private static final int TITLE_BODY_GAP = 10;
    /** Voile léger par-dessus le flou in-game (alpha ~30 %). */
    private static final int INGAME_OVERLAY_ARGB = 0x4C101018;
    /** Panneau centré derrière le texte (alpha ~85 %). */
    private static final int PANEL_FILL_ARGB = 0xD9182030;

    private final Screen parent;

    public ModInfoScreen(Screen parent) {
        super(Text.translatable(KEY_TITLE));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int btnW = 200;
        int btnH = 20;
        int x = this.width / 2 - btnW / 2;
        int y = this.height - 36;
        addDrawableChild(ButtonWidget.builder(Text.translatable("feather_world_menu.informations.close"), b -> {
            if (client != null) {
                client.setScreen(parent);
            }
        }).dimensions(x, y, btnW, btnH).build());
    }

    private static String resolvedBodyPlain() {
        String fromI18n = I18n.translate(KEY_BODY);
        if (fromI18n != null && !fromI18n.isBlank() && !fromI18n.equals(KEY_BODY)) {
            return fromI18n;
        }
        return Text.translatable(KEY_BODY).getString();
    }

    private static String resolvedTitlePlain() {
        String fromI18n = I18n.translate(KEY_TITLE);
        if (fromI18n != null && !fromI18n.isBlank() && !fromI18n.equals(KEY_TITLE)) {
            return fromI18n;
        }
        return Text.translatable(KEY_TITLE).getString();
    }

    private List<BodySegment> wrapBodySegments(int maxW) {
        List<BodySegment> out = new ArrayList<>();
        for (String segment : resolvedBodyPlain().split("\r?\n", -1)) {
            if (segment.isEmpty()) {
                out.add(BodySegment.paragraphBreak());
            } else {
                for (OrderedText line : textRenderer.wrapLines(Text.literal(segment), maxW)) {
                    out.add(BodySegment.textLine(line));
                }
            }
        }
        return out;
    }

    private int measureContentHeight(List<BodySegment> segments) {
        int fh = textRenderer.fontHeight;
        int h = fh + TITLE_BODY_GAP;
        for (BodySegment seg : segments) {
            h += seg.height(fh);
        }
        return h;
    }

    /**
     * Flou in-game + léger assombrissement (pas le voile opaque d’origine).
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        if (client != null && client.world != null) {
            applyBlur(context);
            renderInGameBackground(context);
            context.fill(0, 0, this.width, this.height, INGAME_OVERLAY_ARGB);
        } else {
            super.renderBackground(context, mouseX, mouseY, delta);
        }
    }

    @Override
    protected void renderDarkening(DrawContext context) {
        if (client == null || client.world == null) {
            super.renderDarkening(context);
        }
    }

    @Override
    protected void renderDarkening(DrawContext context, int x, int y, int width, int height) {
        if (client == null || client.world == null) {
            super.renderDarkening(context, x, y, width, height);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int maxW = Math.min(BODY_MAX_WIDTH, this.width - 48);
        List<BodySegment> segments = wrapBodySegments(maxW);
        int contentH = measureContentHeight(segments);
        int panelW = maxW + PANEL_PAD * 2;
        int panelH = contentH + PANEL_PAD * 2;
        int footerReserve = 44;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(8, (this.height - footerReserve - panelH) / 2);

        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_FILL_ARGB);
        context.drawBorder(panelX, panelY, panelW, panelH, 0xFF90CAF9);

        int cx = this.width / 2;
        int y = panelY + PANEL_PAD;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(resolvedTitlePlain()), cx, y, 0xFFFFFFFF);
        y += textRenderer.fontHeight + TITLE_BODY_GAP;

        int fh = textRenderer.fontHeight;
        for (BodySegment seg : segments) {
            if (seg.isGap()) {
                y += fh / 2 + 2;
            } else {
                context.drawCenteredTextWithShadow(textRenderer, seg.line(), cx, y, 0xFFE8EAF6);
                y += fh + 2;
            }
        }
    }

    @Override
    public boolean shouldPause() {
        return client != null && client.isIntegratedServerRunning();
    }

    private record BodySegment(OrderedText line, boolean blankLine) {
        static BodySegment textLine(OrderedText line) {
            return new BodySegment(line, false);
        }

        static BodySegment paragraphBreak() {
            return new BodySegment(null, true);
        }

        boolean isGap() {
            return blankLine;
        }

        int height(int fh) {
            return blankLine ? fh / 2 + 2 : fh + 2;
        }
    }
}
