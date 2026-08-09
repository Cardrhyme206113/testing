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
    private TextFieldWidget beautyShaderField;
    private TextFieldWidget depthShaderField;
    private TextFieldWidget teleportTimeoutField;

    private TextFieldWidget beautyShaderSettleField;
    private TextFieldWidget depthShaderSettleField;
    private TextFieldWidget beautyPreF2Field;
    private TextFieldWidget beautyPostF2Field;
    private TextFieldWidget depthPreF2Field;
    private TextFieldWidget depthPostF2Field;
    private TextFieldWidget finalFramesField;

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
        int colWidth = Math.min(228, (this.width - 3 * gap) / 2);
        int totalWidth = colWidth * 2 + gap;
        int left = (this.width - totalWidth) / 2;
        int right = left + colWidth + gap;
        int top = 24;
        int row = 23;

        widthField = addField("Capture width", Integer.toString(draft.captureWidth), left, top + row * 0, colWidth);
        heightField = addField("Capture height", Integer.toString(draft.captureHeight), left, top + row * 1, colWidth);
        gridRadiusField = addField("Grid radius (1=3x3, 2=5x5)", Integer.toString(draft.gridRadius), left, top + row * 2, colWidth);
        offsetField = addField("Offset per step (blocks)", fmt(draft.offsetStepBlocks), left, top + row * 3, colWidth);
        beautyShaderField = addField("Beauty shader (@current allowed)", draft.beautyShader, left, top + row * 4, colWidth);
        depthShaderField = addField("Depth shader filename/folder", draft.depthShader, left, top + row * 5, colWidth);
        teleportTimeoutField = addField("Teleport timeout (ms)", Long.toString(draft.teleportTimeoutMs), left, top + row * 6, colWidth);

        beautyShaderSettleField = addField("Beauty shader-load settle (ms)", Long.toString(draft.beautyShaderSwitchSettleMs), right, top + row * 0, colWidth);
        depthShaderSettleField = addField("Depth shader-load settle (ms)", Long.toString(draft.depthShaderSwitchSettleMs), right, top + row * 1, colWidth);
        beautyPreF2Field = addField("Beauty target-res BEFORE F2 (ms)", Long.toString(draft.beautyPreF2HighResMs), right, top + row * 2, colWidth);
        beautyPostF2Field = addField("Beauty target-res AFTER F2 (ms)", Long.toString(draft.beautyPostF2SettleMs), right, top + row * 3, colWidth);
        depthPreF2Field = addField("Depth target-res BEFORE F2 (ms)", Long.toString(draft.depthPreF2HighResMs), right, top + row * 4, colWidth);
        depthPostF2Field = addField("Depth target-res AFTER F2 (ms)", Long.toString(draft.depthPostF2SettleMs), right, top + row * 5, colWidth);
        finalFramesField = addField("Final render frames after timer", Integer.toString(draft.finalCaptureRenderFrames), right, top + row * 6, colWidth);

        int boolY = top + row * 7 + 2;
        addDrawableChild(ButtonWidget.builder(toggleText("F2 capture", draft.warmupF2), b -> {
            draft.warmupF2 = !draft.warmupF2;
            b.setMessage(toggleText("F2 capture", draft.warmupF2));
        }).dimensions(left, boolY, colWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(toggleText("Keep F2 images", draft.keepWarmupImages), b -> {
            draft.keepWarmupImages = !draft.keepWarmupImages;
            b.setMessage(toggleText("Keep F2 images", draft.keepWarmupImages));
        }).dimensions(right, boolY, colWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Restore position", draft.restoreOriginalPosition), b -> {
            draft.restoreOriginalPosition = !draft.restoreOriginalPosition;
            b.setMessage(toggleText("Restore position", draft.restoreOriginalPosition));
        }).dimensions(left, boolY + 23, colWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(toggleText("Restore shader", draft.restoreOriginalShader), b -> {
            draft.restoreOriginalShader = !draft.restoreOriginalShader;
            b.setMessage(toggleText("Restore shader", draft.restoreOriginalShader));
        }).dimensions(right, boolY + 23, colWidth, 20).build());

        int buttonY = Math.min(this.height - 24, boolY + 47);
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
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y + 8, width, 15, Text.literal(label));
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
            c.beautyShader = beautyShaderField.getText().trim();
            c.depthShader = depthShaderField.getText().trim();
            c.teleportTimeoutMs = parseLong(teleportTimeoutField, "Teleport timeout");

            c.beautyShaderSwitchSettleMs = parseLong(beautyShaderSettleField, "Beauty shader-load settle");
            c.depthShaderSwitchSettleMs = parseLong(depthShaderSettleField, "Depth shader-load settle");
            c.beautyPreF2HighResMs = parseLong(beautyPreF2Field, "Beauty pre-F2 target-res wait");
            c.beautyPostF2SettleMs = parseLong(beautyPostF2Field, "Beauty post-F2 wait");
            c.depthPreF2HighResMs = parseLong(depthPreF2Field, "Depth pre-F2 target-res wait");
            c.depthPostF2SettleMs = parseLong(depthPostF2Field, "Depth post-F2 wait");
            c.finalCaptureRenderFrames = parseInt(finalFramesField, "Final render frames");
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
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);
        for (Label label : labels) {
            context.drawTextWithShadow(this.textRenderer, Text.literal(label.text()), label.x(), label.y(), 0xBFBFBF);
        }
        if (!error.isBlank()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(error), this.width / 2, this.height - 36, 0xFF6666);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
