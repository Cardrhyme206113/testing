package dev.card.parallaxcapture;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.concurrent.CompletableFuture;

/** Shown only when conversion actually needs Python and PATH auto-detection failed. */
public final class PythonPathScreen extends Screen {
    static final String RETRY_AUTO = "\u0000PARALLAX_RETRY_AUTO";

    private final Screen parent;
    private final String initial;
    private final String error;
    private final CompletableFuture<String> result;
    private TextFieldWidget pathField;
    private boolean completed;

    public PythonPathScreen(Screen parent, String initial, String error, CompletableFuture<String> result) {
        super(Text.literal("Python 3 required"));
        this.parent = parent;
        this.initial = initial == null ? "" : initial;
        this.error = error == null ? "" : error;
        this.result = result;
    }

    @Override
    protected void init() {
        int fieldW = Math.min(430, this.width - 40);
        int x = (this.width - fieldW) / 2;
        int y = Math.max(72, this.height / 2 - 40);

        pathField = new TextFieldWidget(this.textRenderer, x, y, fieldW, 20, Text.literal("Python executable"));
        pathField.setMaxLength(1024);
        pathField.setText(initial);
        addDrawableChild(pathField);
        setInitialFocus(pathField);

        int half = (fieldW - 8) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Use this path"), b -> finish(pathField.getText().trim()))
                .dimensions(x, y + 30, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Retry PATH"), b -> finish(RETRY_AUTO))
                .dimensions(x + half + 8, y + 30, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel conversion"), b -> finish(null))
                .dimensions(x, y + 58, fieldW, 20).build());
    }

    private void finish(String value) {
        if (completed) return;
        completed = true;
        result.complete(value);
        if (this.client != null && this.client.currentScreen == this) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 28, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Parallax conversion could not find python3 or python in PATH."),
                this.width / 2, 46, 0xBFBFBF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Enter the full path to a Python 3 executable. It will be saved for next time."),
                this.width / 2, 58, 0xAFAFAF);
        if (!error.isBlank()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(error), this.width / 2,
                    Math.max(72, this.height / 2 - 40) - 14, 0xFF6666);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        finish(null);
    }
}
