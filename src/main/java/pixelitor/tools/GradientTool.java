/*
 * Copyright 2018 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.tools;

import pixelitor.Canvas;
import pixelitor.Composition;
import pixelitor.gui.BlendingModePanel;
import pixelitor.gui.ImageComponent;
import pixelitor.history.History;
import pixelitor.layers.Drawable;
import pixelitor.layers.LayerMask;
import pixelitor.layers.TmpDrawingLayer;
import pixelitor.utils.Cursors;
import pixelitor.utils.debug.DebugNode;

import javax.swing.*;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint.CycleMethod;
import java.awt.Paint;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import static java.awt.MultipleGradientPaint.CycleMethod.NO_CYCLE;
import static java.awt.MultipleGradientPaint.CycleMethod.REFLECT;
import static java.awt.MultipleGradientPaint.CycleMethod.REPEAT;
import static java.awt.RenderingHints.KEY_ANTIALIASING;
import static java.awt.RenderingHints.VALUE_ANTIALIAS_ON;
import static pixelitor.Composition.ImageChangeActions.FULL;
import static pixelitor.tools.GradientTool.GradientToolState.DRAGGING;
import static pixelitor.tools.GradientTool.GradientToolState.INITIAL;

/**
 * The gradient tool
 */
public class GradientTool extends DragTool {
//    private boolean thereWasDragging = false;

    enum GradientToolState {
        INITIAL {
            @Override
            boolean showHandles() {
                return false;
            }
        },
        DRAGGING {
            @Override
            boolean showHandles() {
                return true;
            }
        },
        /**
         * After the dragging was finished,
         * the handles are still shown for a short time
         */
        STILL_HANDLES {
            @Override
            boolean showHandles() {
                return true;
            }
        };

        abstract boolean showHandles();
    }

    GradientToolState state = INITIAL;

    private static final String NO_CYCLE_AS_STRING = "No Cycle";
    private static final String REFLECT_AS_STRING = "Reflect";
    private static final String REPEAT_AS_STRING = "Repeat";
    public static final String[] CYCLE_METHODS = {
            NO_CYCLE_AS_STRING,
            REFLECT_AS_STRING,
            REPEAT_AS_STRING};

    private JComboBox<GradientColorType> colorTypeSelector;
    private JComboBox<GradientType> typeSelector;
    private JComboBox<String> cycleMethodSelector;
    private JCheckBox invertCheckBox;
    private BlendingModePanel blendingModePanel;

    GradientTool() {
        super('g', "Gradient", "gradient_tool_icon.png",
                "<b>click</b> and <b>drag</b> to draw a gradient, <b>Shift-drag</b> to constrain the direction.",
                Cursors.DEFAULT, true, true, true, ClipStrategy.CANVAS);
    }

    @Override
    public void initSettingsPanel() {
        typeSelector = new JComboBox<>(GradientType.values());
        settingsPanel.addWithLabel("Type: ", typeSelector, "gradientTypeSelector");

        // cycle methods cannot be put directly in the JComboBox, because they would be all uppercase
        cycleMethodSelector = new JComboBox<>(CYCLE_METHODS);
        settingsPanel.addWithLabel("Cycling: ", cycleMethodSelector, "gradientCycleMethodSelector");

        settingsPanel.addSeparator();

        colorTypeSelector = new JComboBox<>(GradientColorType.values());
        settingsPanel.addWithLabel("Color: ", colorTypeSelector, "gradientColorTypeSelector");

        invertCheckBox = new JCheckBox();
        settingsPanel.addWithLabel("Invert: ", invertCheckBox, "gradientInvert");

        settingsPanel.addSeparator();

        blendingModePanel = new BlendingModePanel(true);
        settingsPanel.add(blendingModePanel);
    }

    @Override
    public void dragStarted(PMouseEvent e) {

    }

    @Override
    public void ongoingDrag(PMouseEvent e) {
        state = DRAGGING;  // the gradient will be drawn only when the mouse is released
        e.getIC().repaint();
    }

    @Override
    public void dragFinished(PMouseEvent e) {
        if (state == DRAGGING) {
            Composition comp = e.getComp();

            History.addImageEdit(getName(), comp);
            drawGradient(comp.getActiveDrawable(),
                    getType(),
                    getGradientColorType(),
                    getCycleType(),
                    blendingModePanel.getComposite(),
                    userDrag.toImDrag(),
                    invertCheckBox.isSelected()
            );

            state = INITIAL;
            comp.imageChanged(FULL);
        }
    }

    private CycleMethod getCycleType() {
        return getCycleMethodFromString((String) cycleMethodSelector.getSelectedItem());
    }

    private GradientColorType getGradientColorType() {
        return (GradientColorType) colorTypeSelector.getSelectedItem();
    }

    private GradientType getType() {
        return (GradientType) typeSelector.getSelectedItem();
    }

    @Override
    public boolean mouseClicked(PMouseEvent e) {
        if (super.mouseClicked(e)) {
            return true;
        }
        state = INITIAL;
        return false;
    }

    public static void drawGradient(Drawable dr, GradientType gradientType,
                                    GradientColorType colorType,
                                    CycleMethod cycleMethod,
                                    Composite composite,
                                    ImDrag imDrag, boolean invert) {
        if (imDrag.isClick()) {
            return;
        }

        Graphics2D g;
        int width;
        int height;
        if (dr instanceof LayerMask) {
            BufferedImage subImage = dr.getCanvasSizedSubImage();
            g = subImage.createGraphics();
            width = subImage.getWidth();
            height = subImage.getHeight();
        } else {
            TmpDrawingLayer tmpDrawingLayer = dr.createTmpDrawingLayer(composite);
            g = tmpDrawingLayer.getGraphics();
            width = tmpDrawingLayer.getWidth();
            height = tmpDrawingLayer.getHeight();
        }
        dr.getComp().applySelectionClipping(g, null);
        // repeated gradients are still jaggy
        g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);

        Color startColor = colorType.getStartColor(invert);
        Color endColor = colorType.getEndColor(invert);
        assert startColor != null;
        assert endColor != null;
        Color[] colors = {startColor, endColor};

        Paint gradient = gradientType.getGradient(imDrag, colors, cycleMethod);

        g.setPaint(gradient);

        g.fillRect(0, 0, width, height);
        g.dispose();
        dr.mergeTmpDrawingLayerDown();
        dr.updateIconImage();
    }

    @Override
    public void paintOverImage(Graphics2D g2, Canvas canvas, ImageComponent ic, AffineTransform componentTransform, AffineTransform imageTransform) {
        if (state.showHandles()) {
            userDrag.drawGradientToolHelper(g2);
        }
    }

    private static CycleMethod getCycleMethodFromString(String s) {
        switch (s) {
            case NO_CYCLE_AS_STRING:
                return NO_CYCLE;
            case REFLECT_AS_STRING:
                return REFLECT;
            case REPEAT_AS_STRING:
                return REPEAT;
        }
        throw new IllegalStateException("should not get here");
    }

    public void setupMaskDrawing(boolean editMask) {
        if (editMask) {
            blendingModePanel.setEnabled(false);
        } else {
            blendingModePanel.setEnabled(true);
        }
    }

    @Override
    public DebugNode getDebugNode() {
        DebugNode node = super.getDebugNode();

        node.addString("Type", getType().toString());
        node.addString("Cycling", getCycleType().toString());
        node.addQuotedString("Color", getGradientColorType().toString());
        node.addBoolean("Invert", invertCheckBox.isSelected());
        node.addFloat("Opacity", blendingModePanel.getOpacity());
        node.addQuotedString("Blending Mode", blendingModePanel.getBlendingMode().toString());

        return node;
    }
}
