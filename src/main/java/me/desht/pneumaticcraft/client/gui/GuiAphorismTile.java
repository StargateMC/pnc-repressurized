package me.desht.pneumaticcraft.client.gui;

import me.desht.pneumaticcraft.client.util.ClientUtils;
import me.desht.pneumaticcraft.client.util.GuiUtils;
import me.desht.pneumaticcraft.common.config.PNCConfig;
import me.desht.pneumaticcraft.common.core.ModBlocks;
import me.desht.pneumaticcraft.common.network.NetworkHandler;
import me.desht.pneumaticcraft.common.network.PacketAphorismTileUpdate;
import me.desht.pneumaticcraft.common.tileentity.TileEntityAphorismTile;
import me.desht.pneumaticcraft.common.util.DramaSplash;
import me.desht.pneumaticcraft.common.util.PneumaticCraftUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.SharedConstants;
import org.apache.commons.lang3.ArrayUtils;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class GuiAphorismTile extends Screen {
    public final TileEntityAphorismTile tile;
    private String[] textLines;
    public int cursorY;
    public int cursorX;
    public int updateCounter;

    public GuiAphorismTile(TileEntityAphorismTile tile) {
        super(new ItemStack(ModBlocks.APHORISM_TILE.get()).getDisplayName());

        this.tile = tile;
        textLines = tile.getTextLines();
        if (PNCConfig.Client.aphorismDrama && textLines.length == 1 && textLines[0].equals("")) {
            List<String> l = PneumaticCraftUtils.splitString(DramaSplash.getInstance().getSplash(), 20);
            tile.setTextLines(l.toArray(new String[0]));
        }
        NetworkHandler.sendToServer(new PacketAphorismTileUpdate(tile));
    }

    public static void openGui(TileEntityAphorismTile te) {
        if (te instanceof TileEntityAphorismTile) {
            Minecraft.getInstance().displayGuiScreen(new GuiAphorismTile(te));
        }
    }

    @Override
    public void tick() {
        updateCounter++;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        super.render(mouseX, mouseY, partialTicks);

        if (ClientUtils.isKeyDown(GLFW.GLFW_KEY_F1)) {
            GuiUtils.showPopupHelpScreen(this, font,
                    PneumaticCraftUtils.splitString(I18n.format("pneumaticcraft.gui.aphorismTile.helpText"), 40));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean updateTE = false;

        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE:
                NetworkHandler.sendToServer(new PacketAphorismTileUpdate(tile));
                break;
            case GLFW.GLFW_KEY_UP:
                cursorY--;
                if (cursorY < 0) cursorY = textLines.length - 1;
                break;
            case GLFW.GLFW_KEY_DOWN:
            case GLFW.GLFW_KEY_KP_ENTER:
                cursorY++;
                if (cursorY >= textLines.length) cursorY = 0;
                break;
            case GLFW.GLFW_KEY_ENTER:
                cursorY++;
                textLines = insertLine("", cursorY);
//                textLines = ArrayUtils.insert(cursorY, textLines, "");
                updateTE = true;
                break;
            case GLFW.GLFW_KEY_BACKSPACE:
                if (textLines[cursorY].length() > 0) {
                    textLines[cursorY] = textLines[cursorY].substring(0, textLines[cursorY].length() - 1);
                    if (textLines[cursorY].endsWith("\u00a7")) {
                        textLines[cursorY] = textLines[cursorY].substring(0, textLines[cursorY].length() - 1);
                    }
                } else if (textLines.length > 1) {
                    textLines = ArrayUtils.remove(textLines, cursorY);
                    cursorY--;
                    if (cursorY < 0) cursorY = 0;
                }
                updateTE = true;
                break;
            case GLFW.GLFW_KEY_DELETE:
                if (Screen.hasShiftDown()) {
                    textLines = new String[1];
                    textLines[0] = "";
                    cursorY = 0;
                } else {
                    if (textLines.length > 1) {
                        textLines = ArrayUtils.remove(textLines, cursorY);
                        if (cursorY > textLines.length - 1)
                            cursorY = textLines.length - 1;
                    }
                }
                updateTE = true;
                break;
        }
        if (updateTE) tile.setTextLines(textLines);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int keyCode) {
        if (SharedConstants.isAllowedCharacter(ch)) {
            if (Screen.hasAltDown()) {
                if (ch >= 'a' && ch <= 'f' || ch >= 'l' && ch <= 'o' || ch == 'r' || ch >= '0' && ch <= '9') {
                    textLines[cursorY] = textLines[cursorY] + "\u00a7" + ch;
                }
            } else {
                textLines[cursorY] = textLines[cursorY] + ch;
            }
            tile.setTextLines(textLines);
        }
        return super.charTyped(ch, keyCode);
    }

    @Override
    public void init() {
        minecraft.keyboardListener.enableRepeatEvents(true);
    }

    @Override
    public void onClose() {
        minecraft.keyboardListener.enableRepeatEvents(false);

        super.onClose();
    }

    private String[] insertLine(String line, int pos) {
        String[] newLines = new String[textLines.length + 1];

        newLines[pos] = line;
        if (pos > 0) {
            System.arraycopy(textLines, 0, newLines, 0, pos);
        }
        if (pos < textLines.length) {
            System.arraycopy(textLines, pos, newLines, pos + 1, textLines.length - pos);
        }
        return newLines;
    }
}
