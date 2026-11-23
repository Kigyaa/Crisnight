package ru.layoutcamera;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class CampaignResultScreen extends Screen {
    private final Campaign.Result result;
    private boolean busy;
    private boolean failed;

    CampaignResultScreen(Campaign.Result result) {
        super(Component.literal(result.victory() ? "Победа" : "Поражение"));
        this.result = result;
    }

    Campaign.Result result() {
        return result;
    }

    @Override
    protected void init() {
        if (!result.victory()) {
            int x = width / 2;
            int y = height / 2 + 20;
            addRenderableWidget(Button.builder(Component.literal("Выход"), button -> act(false))
                .bounds(x - 91, y, 86, 20).build()).active = !busy;
            addRenderableWidget(Button.builder(Component.literal("Рестарт"), button -> act(true))
                .bounds(x + 5, y, 86, 20).build()).active = !busy;
        }
    }

    private void act(boolean restart) {
        busy = true;
        failed = false;
        rebuildWidgets();
        Campaign.actOnDefeat(minecraft, this, restart);
    }

    void actionFailed() {
        busy = false;
        failed = true;
        rebuildWidgets();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xa20b0d11);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = width / 2;
        int y = height / 2;
        int halfWidth = Math.min(138, x - 8);
        int accent = result.victory() ? 0xff8adbb4 : 0xffff909d;
        graphics.fill(x - halfWidth, y - 58, x + halfWidth, y + 53, 0xf320252d);
        graphics.fill(x - halfWidth, y - 58, x + halfWidth, y - 55, accent);
        graphics.centeredText(font, title, x, y - 46, accent);
        String name = font.plainSubstrByWidth(result.entry().title(), halfWidth * 2 - 20);
        graphics.centeredText(font, name, x, y - 29, 0xfff4f7fa);
        if (result.victory()) {
            graphics.centeredText(font, result.progressSaved() ? "Уровень пройден" : "Прогресс не сохранён",
                x, y - 9, 0xffc7dce8);
            graphics.centeredText(font, "Возвращение к месту запуска…", x, y + 14, 0xffc7dce8);
        } else {
            String detail = failed ? "Действие не выполнено. Подробности в чате."
                : "Мобов дошло до финиша: " + Campaign.ESCAPE_LIMIT;
            graphics.centeredText(font, detail, x, y - 7, 0xffc7dce8);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
