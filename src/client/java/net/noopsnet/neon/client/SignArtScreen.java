// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import net.noopsnet.neon.*;

/** Local draft: nothing changes in the world until the server accepts Save. */
public final class SignArtScreen extends AbstractContainerScreen<SignArtMenu> {
    private ArtCanvas canvas;
    private boolean original;
    private EditBox hex;
    private int cell,gx,gy,rx,brush,tool,drag=-1,lastX=-1,lastY=-1;
    private float hue,saturation,value;
    private boolean syncingHex;
    public SignArtScreen(SignArtMenu menu,Inventory inventory,Component title) {
        super(menu,inventory,title);brush=menu.initialColor;
        float[] hsv=ColorMath.toHsv(brush);hue=hsv[0];saturation=hsv[1];value=hsv[2];
    }
    private SignArt stock() {
        int[] pixels=new int[SignArt.PIXELS];
        var id=ResourceLocation.fromNamespaceAndPath(NoopsnetNeon.ID,"textures/block/amber_"+(menu.blade?"metro":"market")+".png");
        try(var stream=minecraft.getResourceManager().open(id);var image=NativeImage.read(stream)) {
            for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
                boolean opaque=false;
                for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++)if((image.getPixelRGBA(x*2+dx,y*2+dy)>>>24)>127)opaque=true;
                if(opaque)pixels[x+32*y]=0xFF000000|menu.initialColor;
            }
        } catch(java.io.IOException ignored) { /* Empty draft if a resource pack removes the stock art. */ }
        return new SignArt(pixels);
    }
    @Override protected void init() {
        cell=Math.max(3,Math.min(8,Math.min((height-84)/32,(width-148)/32)));
        imageWidth=32*cell+128;imageHeight=Math.max(228,32*cell+68);
        super.init();gx=leftPos+10;gy=topPos+28;rx=gx+32*cell+12;
        if(canvas==null){original=menu.initialArt==null;canvas=new ArtCanvas(original?stock():menu.initialArt);}
        hex=new EditBox(font,rx,gy,96,18,Component.literal("Brush hex colour"));hex.setMaxLength(7);
        hex.setValue(String.format("#%06X",brush));hex.setResponder(text->{Integer c=ColorMath.parseHex(text);if(c!=null&&!syncingHex){brush=c;float[] hsv=ColorMath.toHsv(c);hue=hsv[0];saturation=hsv[1];value=hsv[2];}});
        addRenderableWidget(hex);
        button("Draw",rx,gy+94,46,()->tool=0);button("Erase",rx+50,gy+94,46,()->tool=1);
        button("Fill",rx,gy+116,46,()->tool=2);button("Pick",rx+50,gy+116,46,()->tool=3);
        button("Undo",rx,gy+138,46,()->{canvas.undo();original=false;});
        button("Clear",rx+50,gy+138,46,()->{canvas.clear();original=false;});
        button("Original",rx,gy+160,96,()->{canvas.replace(stock());original=true;});
        int bottom=topPos+imageHeight-25;
        button("Save",gx,bottom,74,()->PacketDistributor.sendToServer(new SignArtPayload(menu.containerId,original?null:canvas.art())));
        button("Cancel",gx+80,bottom,74,this::onClose);
    }
    private void button(String text,int x,int y,int width,Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(text),b->action.run()).bounds(x,y,width,18).build());
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF28232C);
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+2,0xFFFF42A4);
        g.drawString(font,title,leftPos+10,topPos+10,0xFFF7EAF8,false);
        for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
            int p=canvas.pixel(x,y),X=gx+x*cell,Y=gy+y*cell;
            g.fill(X,Y,X+cell,Y+cell,p==0?((x+y)%2==0?0xFF352F3B:0xFF423A49):p);
            if(cell>=5){g.fill(X,Y,X+cell,Y+1,0x33202020);g.fill(X,Y,X+1,Y+cell,0x33202020);}
        }
        for(int x=0;x<96;x++)g.fill(rx+x,gy+28,rx+x+1,gy+38,0xFF000000|ColorMath.hsv(x/95F,1,1));
        for(int x=0;x<96;x++)g.fillGradient(rx+x,gy+46,rx+x+1,gy+86,0xFF000000|ColorMath.hsv(hue,x/95F,1),0xFF000000);
        g.renderOutline(rx+(int)(saturation*95)-2,gy+46+(int)((1-value)*39)-2,5,5,0xFFFFFFFF);
        g.renderOutline(rx+(int)(hue*95)-1,gy+26,3,14,0xFFFFFFFF);
        String hint=switch(tool){case 1->"Eraser";case 2->"Fill area";case 3->"Pick a pixel";default->"Draw / right: erase";};
        g.drawString(font,hint,gx,gy+32*cell+5,0xFFD8C2DA,false);
        if(menu.status.get()!=0)g.drawString(font,Component.translatable(menu.status.get()==3?"noopsnet_neon.art.stale":"noopsnet_neon.art.denied"),leftPos+10,topPos+imageHeight+3,0xFFFF9CA9,false);
    }
    @Override protected void renderLabels(GuiGraphics g,int mx,int my) {}
    @Override public void render(GuiGraphics g,int mx,int my,float partial) { super.render(g,mx,my,partial);renderTooltip(g,mx,my); }
    private boolean inGrid(double x,double y){return x>=gx&&y>=gy&&x<gx+32*cell&&y<gy+32*cell;}
    private void focusCanvas(){setFocused(null);hex.setFocused(false);}
    private void brushFromHsv(){brush=ColorMath.hsv(hue,saturation,value);syncingHex=true;hex.setValue(String.format("#%06X",brush));syncingHex=false;}
    private void colourDrag(double x,double y,int kind) {
        if(kind==1)hue=Math.clamp((float)(x-rx)/95,0,1);
        else {saturation=Math.clamp((float)(x-rx)/95,0,1);value=1-Math.clamp((float)(y-gy-46)/39,0,1);}
        brushFromHsv();
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(button==0&&x>=rx&&x<rx+96&&y>=gy+28&&y<gy+38){focusCanvas();drag=1;colourDrag(x,y,drag);return true;}
        if(button==0&&x>=rx&&x<rx+96&&y>=gy+46&&y<gy+86){focusCanvas();drag=2;colourDrag(x,y,drag);return true;}
        if(inGrid(x,y)&&(button==0||button==1)) {
            focusCanvas();
            int px=(int)(x-gx)/cell,py=(int)(y-gy)/cell;
            if(tool==3&&button==0){int c=canvas.pixel(px,py);if(c!=0)hex.setValue(String.format("#%06X",c&0xFFFFFF));return true;}
            int color=button==1||tool==1?0:0xFF000000|brush;
            if(tool==2&&button==0)canvas.fill(px,py,color);
            else {canvas.beginStroke();canvas.line(px,py,px,py,color);drag=3;lastX=px;lastY=py;}
            original=false;return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(drag==1||drag==2){colourDrag(x,y,drag);return true;}
        if(drag==3) {
            if(inGrid(x,y)){int px=(int)(x-gx)/cell,py=(int)(y-gy)/cell;canvas.line(lastX<0?px:lastX,lastY<0?py:lastY,px,py,button==1||tool==1?0:0xFF000000|brush);lastX=px;lastY=py;}
            else {lastX=-1;lastY=-1;}return true;
        }
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){drag=-1;lastX=-1;lastY=-1;return super.mouseReleased(x,y,button);}
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(hex.isFocused()&&key!=256&&key!=258)return hex.keyPressed(key,scan,modifiers)||(key>=32&&key<=348);
        if(!hex.isFocused()&&hasControlDown()&&key==90){canvas.undo();original=false;return true;}
        return super.keyPressed(key,scan,modifiers);
    }
}
