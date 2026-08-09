package dev.card.parallaxcapture;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CaptureConfigScreen extends Screen {
    private record Label(String text, int x, int y) {}

    private final Screen parent;
    private CaptureConfig draft;
    private final List<Label> labels = new ArrayList<>();

    private TextFieldWidget widthField;
    private TextFieldWidget heightField;
    private TextFieldWidget gridRadiusField;
    private TextFieldWidget offsetField;
    private TextFieldWidget renderFramesField;
    private TextFieldWidget beautyShaderField;
    private TextFieldWidget depthShaderField;
    private TextFieldWidget warmupDelayField;
    private TextFieldWidget settleField;
    private TextFieldWidget shaderSettleField;
    private TextFieldWidget teleportTimeoutField;

    private String error = "";

    public CaptureConfigScreen(Screen parent) {
        super(Text.literal("Parallax Capture Settings"));
        this.parent = parent;
        this.draft = ParallaxCaptureClient.getConfig().copy();
    }

    @Override
    protected void init() {
        labels.clear();
        int gap = 12;
        int colWidth = Math.min(220, (this.width - 3 * gap) / 2);
        int totalWidth = colWidth * 2 + gap;
        int left = (this.width - totalWidth) / 2;
        int right = left + colWidth + gap;
        int top = 28;
        int row = 26;

        widthField = addField("Capture width", Integer.toString(draft.captureWidth), left, top + row * 0, colWidth);
        heightField = addField("Capture height", Integer.toString(draft.captureHeight), left, top + row * 1, colWidth);
        gridRadiusField = addField("Grid radius (1 = 3x3)", Integer.toString(draft.gridRadius), left, top + row * 2, colWidth);
        offsetField = addField("Offset per step (blocks)", fmt(draft.offsetStepBlocks), left, top + row * 3, colWidth);
        renderFramesField = addField("4K settle/render frames", Integer.toString(draft.highResRenderFrames), left, top + row * 4, colWidth);
        beautyShaderField = addField("Beauty shader (@current allowed)", draft.beautyShader, left, top + row * 5, colWidth);

        depthShaderField = addField("Depth shader filename/folder", draft.depthShader, right, top + row * 0, colWidth);
        warmupDelayField = addField("Delay after teleport before F2 (ms)", Long.toString(draft.warmupDelayMs), right, top + row * 1, colWidth);
        settleField = addField("Settle after F2 (ms)", Long.toString(draft.settleAfterWarmupMs), right, top + row * 2, colWidth);
        shaderSettleField = addField("Shader switch settle (ms)", Long.toString(draft.shaderSwitchSettleMs), right, top + row * 3, colWidth);
        teleportTimeoutField = addField("Teleport timeout (ms)", Long.toString(draft.teleportTimeoutMs), right, top + row * 4, colWidth);

        int boolY = top + row * 6 + 2;
        addDrawableChild(ButtonWidget.builder(toggleText("F2 warm-up", draft.warmupF2), b -> {
            draft.warmupF2 = !draft.warmupF2;
            b.setMessage(toggleText("F2 warm-up", draft.warmupF2));
        }).dimensions(left, boolY, colWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(toggleText("Keep warm-up PNGs", draft.keepWarmupImages), b -> {
            draft.keepWarmupImages = !draft.keepWarmupImages;
            b.setMessage(toggleText("Keep warm-up PNGs", draft.keepWarmupImages));
        }).dimensions(right, boolY, colWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Restore position", draft.restoreOriginalPosition), b -> {
            draft.restoreOriginalPosition = !draft.restoreOriginalPosition;
            b.setMessage(toggleText("Restore position", draft.restoreOriginalPosition));
        }).dimensions(left, boolY + 24, colWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(toggleText("Restore shader", draft.restoreOriginalShader), b -> {
            draft.restoreOriginalShader = !draft.restoreOriginalShader;
            b.setMessage(toggleText("Restore shader", draft.restoreOriginalShader));
        }).dimensions(right, boolY + 24, colWidth, 20).build());

        int buttonY = Math.min(this.height - 26, boolY + 50);
        int buttonWidth = Math.min(110, (totalWidth - 8) / 3);
        int center = this.width / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> applyAndClose())
                .dimensions(center - buttonWidth - 4, buttonY, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset defaults"), b -> resetDefaults())
                .dimensions(center + 4, buttonY, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
                .dimensions(center + buttonWidth + 12, buttonY, buttonWidth, 20).build());
    }

    private TextFieldWidget addField(String label, String value, int x, int y, int width) {
        labels.add(new Label(label, x, y));
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y + 9, width, 16, Text.literal(label));
        field.setMaxLength(256);
        field.setText(value);
        return addDrawableChild(field);
    }

    private void applyAndClose() {
        try {
            CaptureConfig c = draft.copy();
            c.captureWidth = parseInt(widthField, "Capture width");
            c.captureHeight = parseInt(heightField, "Capture height");
            c.gridRadius = parseInt(gridRadiusField, "Grid radius");
            c.offsetStepBlocks = parseDouble(offsetField, "Offset step");
            c.highResRenderFrames = parseInt(renderFramesField, "Render frames");
            c.beautyShader = beautyShaderField.getText().trim();
            c.depthShader = depthShaderField.getText().trim();
            c.warmupDelayMs = parseLong(warmupDelayField, "Warm-up delay");
            c.settleAfterWarmupMs = parseLong(settleField, "Post-F2 settle");
            c.shaderSwitchSettleMs = parseLong(shaderSettleField, "Shader settle");
            c.teleportTimeoutMs = parseLong(teleportTimeoutField, "Teleport timeout");
            c.clamp();

            if (c.depthShader.isBlank() || c.depthShader.startsWith("PUT_")) {
                throw new IllegalArgumentException("Pick/type your depth shader filename first");
            }

            ParallaxCaptureClient.applyConfig(c);
            if (this.client != null) this.client.setScreen(parent);
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
    }

    private void resetDefaults() {
        draft = CaptureConfig.defaults();
        clearAndInit();
        error = "Defaults loaded — press Done to save them.";
    }

    private static int parseInt(TextFieldWidget field, String label) {
        try { return Integer.parseInt(field.getText().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(label + " must be a whole number"); }
    }

    private static long parseLong(TextFieldWidget field, String label) {
        try { return Long.parseLong(field.getText().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(label + " must be a whole number"); }
    }

    private static double parseDouble(TextFieldWidget field, String label) {
        try { return Double.parseDouble(field.getText().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(label + " must be a number"); }
    }

    private static Text toggleText(String name, boolean value) {
        return Text.literal(name + ": " + (value ? "ON" : "OFF"));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 9, 0xFFFFFF);
        for (Label label : labels) {
            context.drawTextWithShadow(this.textRenderer, Text.literal(label.text()), label.x(), label.y(), 0xBFBFBF);
        }
        if (!error.isBlank()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(error), this.width / 2, this.height - 38, 0xFF6666);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
