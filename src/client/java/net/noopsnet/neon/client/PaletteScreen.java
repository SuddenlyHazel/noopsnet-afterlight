// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.noopsnet.neon.ColorMath;
import net.noopsnet.neon.PaletteMenu;
import net.noopsnet.neon.LightSettings;
import net.noopsnet.neon.PaletteSettingsPayload;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.neoforged.neoforge.network.PacketDistributor;

/** Continuous HSV picker, exact hex entry and vivid starter presets. */
public final class PaletteScreen extends AbstractContainerScreen<PaletteMenu> {
    private static final int[] PRESETS = {net.noopsnet.neon.NeonCatalog.color("amber"),
        net.noopsnet.neon.NeonCatalog.color("pink"), net.noopsnet.neon.NeonCatalog.color("lime"),
        net.noopsnet.neon.NeonCatalog.color("cyan"), net.noopsnet.neon.NeonCatalog.color("violet"),
        net.noopsnet.neon.NeonCatalog.color("ice")};
    private float hue, saturation, value;
    private EditBox hex;
    private Button apply;
    private boolean updatingHex;
    private int dragging;
    private boolean lightingTab,joining;
    private int glow,reach,mode;
    private final java.util.List<AbstractWidget> colourWidgets=new java.util.ArrayList<>(),lightingWidgets=new java.util.ArrayList<>();
    private void visibility(){colourWidgets.forEach(w->w.visible=!lightingTab);lightingWidgets.forEach(w->w.visible=lightingTab);}
    private String modeText(){return switch(mode){case 1->"Apply: colour only";case 2->"Apply: lighting only";default->"Apply: colour + lighting";};}
    private String joinText(){return joining?"Joining: automatic":"Joining: fixed shape";}
    private final class SettingSlider extends AbstractSliderButton {
        private final boolean light;
        SettingSlider(int y,boolean light){super(leftPos+16,y,278,20,Component.empty(),light?(reach+1)/16D:glow/4D);this.light=light;updateMessage();}
        @Override protected void updateMessage(){setMessage(Component.literal(light?"Light reach: "+(reach<0?"Auto":reach==0?"0 (off)":Integer.toString(reach)):"Glow: "+glow*25+"%"));}
        @Override protected void applyValue(){if(light){reach=(int)Math.round(value*16)-1;value=(reach+1)/16D;}else{glow=(int)Math.round(value*4);value=glow/4D;}}
    }

    public PaletteScreen(PaletteMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 310; imageHeight = 234;
        setColor(menu.initialColor);glow=menu.initialSettings.glow();reach=menu.initialSettings.reach();joining=menu.initialSettings.joining();mode=menu.initialMode;
    }
    private void setColor(int rgb) {
        float[] hsv = ColorMath.toHsv(rgb); hue = hsv[0]; saturation = hsv[1]; value = hsv[2];
    }
    private int color() { return ColorMath.hsv(hue,saturation,value); }
    @Override protected void init() {
        super.init();colourWidgets.clear();lightingWidgets.clear();
        addRenderableWidget(Button.builder(Component.literal("Colour"),b->{lightingTab=false;visibility();}).bounds(leftPos+16,topPos+28,132,14).build());
        addRenderableWidget(Button.builder(Component.literal("Lighting"),b->{lightingTab=true;hex.setFocused(false);visibility();}).bounds(leftPos+162,topPos+28,132,14).build());
        hex = new EditBox(font,leftPos+62,topPos+158,126,20,Component.translatable("noopsnet_neon.palette.hex"));
        hex.setMaxLength(7); hex.setValue(String.format("#%06X",color()));
        hex.setResponder(text -> {
            if (updatingHex) return;
            Integer parsed = ColorMath.parseHex(text);
            if (parsed != null) setColor(parsed);
            if (apply != null) apply.active = parsed != null;
        });
        colourWidgets.add(addRenderableWidget(hex));
        for (int i = 0; i < PRESETS.length; i++) {
            final int rgb = PRESETS[i];
            colourWidgets.add(addRenderableWidget(Button.builder(Component.literal("●").withColor(rgb), b -> {setColor(rgb); syncHex();})
                .bounds(leftPos+16+i*46,topPos+184,40,18).build()));
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(leftPos+16,topPos+210,126,20).build());
        apply = addRenderableWidget(Button.builder(Component.translatable("noopsnet_neon.palette.apply"), b -> {
            Integer rgb = ColorMath.parseHex(hex.getValue());
            if (rgb != null && minecraft != null && minecraft.gameMode != null) {
                PacketDistributor.sendToServer(new PaletteSettingsPayload(menu.containerId,rgb,new LightSettings(glow,reach,joining),mode));
            }
        }).bounds(leftPos+168,topPos+210,126,20).build());
        lightingWidgets.add(addRenderableWidget(new SettingSlider(topPos+52,false)));
        lightingWidgets.add(addRenderableWidget(new SettingSlider(topPos+96,true)));
        lightingWidgets.add(addRenderableWidget(Button.builder(Component.literal(joinText()),b->{joining=!joining;b.setMessage(Component.literal(joinText()));}).bounds(leftPos+16,topPos+140,278,20).build()));
        lightingWidgets.add(addRenderableWidget(Button.builder(Component.literal(modeText()),b->{mode=(mode+1)%3;b.setMessage(Component.literal(modeText()));}).bounds(leftPos+16,topPos+172,278,20).build()));
        visibility();
    }
    private void syncHex() {
        updatingHex=true; hex.setValue(String.format("#%06X",color())); updatingHex=false;
        apply.active=true;
    }
    @Override protected void renderBg(GuiGraphics g, float delta, int mouseX, int mouseY) {
        int x=leftPos,y=topPos;
        g.fill(x,y,x+imageWidth,y+imageHeight,0xFF302A28);
        g.fill(x,y,x+imageWidth,y+3,0xFFD7B58D);
        g.drawString(font,title,x+16,y+13,0xFFF5E9D7,false);
        if(lightingTab) {
            g.drawString(font,"Surface emission and bloom",x+16,y+77,0xFFC0B4A7,false);
            g.drawString(font,"Auto keeps this fixture’s default reach",x+16,y+121,0xFFC0B4A7,false);
            g.drawString(font,"Joining applies to inlays and light trim",x+16,y+162,0xFFC0B4A7,false);
            g.drawString(font,"Saved on this palette; paint or place to apply",x+16,y+195,0xFFC0B4A7,false);
            return;
        }
        for (int col=0;col<190;col++) {
            g.fillGradient(x+16+col,y+46,x+17+col,y+150,0xFF000000|ColorMath.hsv(hue,col/189F,1),0xFF000000);
        }
        for (int row=0;row<104;row++) {
            g.fill(x+218,y+46+row,x+234,y+47+row,0xFF000000|ColorMath.hsv(row/103F,1,1));
        }
        int sx=x+16+Math.round(saturation*189), sy=y+46+Math.round((1-value)*103);
        g.renderOutline(sx-3,sy-3,7,7,0xFF191614);g.renderOutline(sx-2,sy-2,5,5,0xFFFFFFFF);
        int hy=y+46+Math.round(hue*103);
        g.renderOutline(x+216,hy-2,20,5,0xFFFFFFFF);
        g.fill(x+246,y+46,x+294,y+150,0xFF9A7659);
        g.fill(x+252,y+55,x+288,y+141,0xFF000000|color());
        g.drawString(font,Component.translatable("noopsnet_neon.palette.hex"),x+16,y+164,0xFFF5E9D7,false);
        if (ColorMath.parseHex(hex.getValue()) == null)
            g.drawString(font,Component.translatable("noopsnet_neon.palette.invalid"),x+196,y+164,0xFFFFB5AC,false);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
        super.render(g,mouseX,mouseY,delta); renderTooltip(g,mouseX,mouseY);
    }
    private void pick(double mouseX,double mouseY) {
        if (dragging==1) {
            saturation=(float)Math.clamp((mouseX-leftPos-16)/189D,0,1);
            value=1-(float)Math.clamp((mouseY-topPos-46)/103D,0,1);
        } else if (dragging==2) hue=(float)Math.clamp((mouseY-topPos-46)/103D,0,.999999);
        syncHex();
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if (!lightingTab && button==0 && y>=topPos+46 && y<topPos+150) {
            if (x>=leftPos+16 && x<leftPos+206) dragging=1;
            else if (x>=leftPos+218 && x<leftPos+234) dragging=2;
            if (dragging!=0) {pick(x,y);return true;}
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if (button==0 && dragging!=0) {pick(x,y);return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        if (button==0 && dragging!=0) {dragging=0;return true;}
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        // Typing an inventory hotkey in the hex box must not close the picker.
        if (!lightingTab && hex.isFocused() && key!=256 && key!=258) return hex.keyPressed(key,scan,modifiers) || (key>=32 && key<=348);
        return super.keyPressed(key,scan,modifiers);
    }
}
