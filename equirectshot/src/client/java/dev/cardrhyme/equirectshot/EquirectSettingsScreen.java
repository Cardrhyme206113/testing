package dev.cardrhyme.equirectshot;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class EquirectSettingsScreen extends Screen {
    private final Screen parent;

    public EquirectSettingsScreen(Screen parent) {
        super(Component.literal("EquirectShot"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int width = Math.min(310, this.width - 40);
        int x = (this.width - width) / 2;
        int y = this.height / 2 - 46;

        this.addRenderableWidget(new ResolutionSlider(x, y, width));
        this.addRenderableWidget(new DelaySlider(x, y + 26, width));
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> closeAndSave())
                .bounds(x, y + 62, width, 20)
                .build());
    }

    private void closeAndSave() {
        EquirectShotClient.CONFIG.save();
        if (this.minecraft != null) this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        closeAndSave();
    }

    private static final class ResolutionSlider extends AbstractSliderButton {
        ResolutionSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(),
                    (double) EquirectShotClient.CONFIG.resolutionIndex / (EquirectConfig.WIDTHS.length - 1));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int w = EquirectShotClient.CONFIG.outputWidth();
            setMessage(Component.literal("Resolution: " + w + " × " + (w / 2)));
        }

        @Override
        protected void applyValue() {
            int last = EquirectConfig.WIDTHS.length - 1;
            int index = (int) Math.round(this.value * last);
            EquirectShotClient.CONFIG.resolutionIndex = Math.max(0, Math.min(last, index));
            this.value = (double) EquirectShotClient.CONFIG.resolutionIndex / last;
            updateMessage();
        }
    }

    private static final class DelaySlider extends AbstractSliderButton {
        DelaySlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), EquirectShotClient.CONFIG.settleSeconds / 5.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format(java.util.Locale.ROOT,
                    "Shader settle per face: %.1f s", EquirectShotClient.CONFIG.settleSeconds)));
        }

        @Override
        protected void applyValue() {
            double seconds = Math.round(this.value * 50.0) / 10.0;
            EquirectShotClient.CONFIG.settleSeconds = Math.max(0.0, Math.min(5.0, seconds));
            this.value = EquirectShotClient.CONFIG.settleSeconds / 5.0;
            updateMessage();
        }
    }
}
