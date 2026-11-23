package ru.layoutcamera;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class CampaignScreen extends Screen {
    private static final int PER_PAGE = 6;
    private static final int CARD_HEIGHT = 32;
    private static final int ACCENT = 0xff4bd5f3;
    private final Campaign.Menu menu;
    private final List<LevelButton> cards = new ArrayList<>();
    private int page;
    private boolean launching;
    private boolean launchFailed;

    CampaignScreen(Campaign.Menu menu) {
        super(Component.literal("Карта уровней"));
        page = Math.max(0, (menu.visible().size() - 1) / PER_PAGE);
        this.menu = menu;
    }

    @Override
    protected void init() {
        cards.clear();
        int cardWidth = Math.min(154, (width - 54) / 3);
        int left = 18 + cardWidth / 2;
        int right = width - left;
        int mapHeight = height - 104;
        int top = 52 + CARD_HEIGHT / 2;
        int bottomCenter = Math.max(top, 52 + mapHeight - CARD_HEIGHT / 2);
        int columnStep = cardWidth + Math.max(0, (right - left - 2 * cardWidth) / 4);
        int rowStep = CARD_HEIGHT + Math.max(0, (mapHeight / 2 - CARD_HEIGHT) / 2);
        int first = page * PER_PAGE;
        for (int index = first; index < Math.min(first + PER_PAGE, menu.visible().size()); index++) {
            var entry = menu.visible().get(index);
            int slot = index - first;
            int row = slot / 3;
            int column = row == 0 ? slot % 3 : 2 - slot % 3;
            int x = entry.x() == null ? width / 2 + (column - 1) * columnStep
                : coordinate(left, right, entry.x());
            int y = entry.y() == null ? Math.clamp(52 + mapHeight / 2 + (row * 2 - 1) * rowStep / 2,
                top, bottomCenter) : coordinate(top, bottomCenter, entry.y());
            var card = new LevelButton(x - cardWidth / 2, y - CARD_HEIGHT / 2, cardWidth, entry);
            card.active = !launching;
            cards.add(addRenderableWidget(card));
        }
        int bottom = height - 33;
        addRenderableWidget(Button.builder(Component.literal("Закрыть"), button -> onClose())
            .bounds(width - 86, bottom, 68, 18).build()).active = !launching;
        if (pageCount() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> changePage(-1))
                .bounds(18, bottom, 20, 18).build()).active = !launching && page > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), button -> changePage(1))
                .bounds(89, bottom, 20, 18).build()).active = !launching && page + 1 < pageCount();
        }
    }

    private static int coordinate(int start, int end, double percent) {
        return start + (int) Math.round((end - start) * percent / 100);
    }

    private int pageCount() {
        return Math.max(1, (menu.visible().size() + PER_PAGE - 1) / PER_PAGE);
    }

    private void changePage(int increment) {
        page += increment;
        rebuildWidgets();
    }

    private void launch(Campaign.LevelEntry entry) {
        launching = true;
        launchFailed = false;
        rebuildWidgets();
        Campaign.start(minecraft, this, entry);
    }

    void launchFailed() {
        launching = false;
        launchFailed = true;
        rebuildWidgets();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !launching;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractBlurredBackground(graphics);
        graphics.fillGradient(0, 0, width, height, 0x9410141b, 0xba080b10);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(18, 17, 21, 37, ACCENT);
        graphics.text(font, title, 29, 17, 0xffffffff);
        int completed = Math.max(0, menu.visible().size() - (menu.visible().isEmpty()
            || menu.completed().contains(menu.visible().getLast().stage()) ? 0 : 1));
        graphics.text(font, "Пройдено " + completed, 29, 30, 0xffaabac7);
        graphics.fill(18, 45, width - 18, 46, 0xff434c58);
        graphics.fill(18, height - 44, width - 18, height - 43, 0xff434c58);
        for (int index = 1; index < cards.size(); index++) {
            LevelButton from = cards.get(index - 1);
            LevelButton to = cards.get(index);
            line(graphics, from.centerX(), from.centerY(), to.centerX(), to.centerY(), 2, 0xffc7dce8);
        }
        if (page > 0) {
            LevelButton first = cards.getFirst();
            line(graphics, first.centerX(), 46, first.centerX(), first.centerY(), 2, 0xffc7dce8);
        }
        if (page + 1 < pageCount()) {
            LevelButton last = cards.getLast();
            line(graphics, last.centerX(), last.centerY(), last.centerX(), height - 44, 2, 0xffc7dce8);
        }
        if (pageCount() > 1) {
            graphics.centeredText(font, (page + 1) + " / " + pageCount(), 63, height - 28, 0xffc7dce8);
        }
        if (cards.isEmpty()) {
            graphics.centeredText(font, "Добавь уровни в campaign.json", width / 2, height / 2, 0xffc7dce8);
        } else if (launching || launchFailed) {
            String status = launching ? "Создаётся локальная копия…" : "Запуск не удался. Подробности в чате.";
            graphics.centeredText(font, status, width / 2, height - 55, launchFailed ? 0xffffabb1 : 0xffc7dce8);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private static void line(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int thickness, int color) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x1, y1);
        pose.rotate((float) Math.atan2(y2 - y1, x2 - x1));
        graphics.fill(0, -thickness / 2, (int) Math.ceil(Math.hypot(x2 - x1, y2 - y1)),
            thickness - thickness / 2, color);
        pose.popMatrix();
    }

    private final class LevelButton extends Button {
        private final boolean completed;

        LevelButton(int x, int y, int width, Campaign.LevelEntry entry) {
            super(x, y, width, CARD_HEIGHT, Component.literal(entry.title()), button -> launch(entry), DEFAULT_NARRATION);
            completed = menu.completed().contains(entry.stage());
        }

        private int centerX() {
            return getX() + getWidth() / 2;
        }

        private int centerY() {
            return getY() + CARD_HEIGHT / 2;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int x = getX();
            int y = getY();
            int right = x + getWidth();
            boolean focus = active && isHoveredOrFocused();
            int marker = completed ? 0xff8adbb4 : ACCENT;
            graphics.fill(x + 2, y + 3, right + 2, y + CARD_HEIGHT + 3, 0xff0b0d11);
            graphics.fill(x, y, right, y + CARD_HEIGHT, focus ? 0xffe5f8ff : 0xffcbd5df);
            graphics.fill(x + 1, y + 17, right - 1, y + CARD_HEIGHT - 1, 0xff242b35);
            graphics.fill(x, y, x + 3, y + CARD_HEIGHT, marker);
            for (int row = -5; row <= 5; row++) {
                int halfWidth = 5 - Math.max(0, Math.abs(row) - 2);
                graphics.fill(x + 12 - halfWidth, y + 8 + row, x + 13 + halfWidth, y + 9 + row, 0xff22617b);
            }
            String label = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 25);
            graphics.text(font, label, x + 21, y + 4, 0xff18242c, false);
            String status = completed ? "Пройден" : "Доступен";
            graphics.text(font, status, x + 8, y + 21, marker, false);
            if (focus) {
                graphics.outline(x - 1, y - 1, getWidth() + 2, CARD_HEIGHT + 2, ACCENT);
            }
        }
    }
}
