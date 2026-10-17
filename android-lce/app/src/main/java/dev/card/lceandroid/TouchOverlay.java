package dev.card.lceandroid;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public final class TouchOverlay extends View {
    public interface Controls {
        void move(float forward,float strafe);
        void look(float dx,float dy);
        void jump();
        void interact(boolean place);
        void select(int slot);
    }
    private final Controls action;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int movementId=-1,lookId=-1,selected=0;
    private float joystickX,joystickY,forward=0,strafe=0,lastLookX,lastLookY;
    private Bitmap originalGui,originalIcons;
    public TouchOverlay(Context context,Controls controls) {
        super(context);action=controls;setLayerType(View.LAYER_TYPE_SOFTWARE,null);
    }
    public void setOriginalGui(Bitmap gui,Bitmap icons) {
        originalGui=gui;originalIcons=icons;invalidate();
    }
    private void circle(Canvas canvas,float x,float y,float radius,int color) {
        p.setColor(color);p.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,radius,p);
        p.setColor(0x88FFFFFF);p.setStrokeWidth(2);p.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(x,y,radius,p);p.setStyle(Paint.Style.FILL);
    }
    private void text(Canvas canvas,String t,float x,float y,float size) {
        p.setColor(0xffffffff);p.setTextSize(size);p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);canvas.drawText(t,x,y,p);p.setFakeBoldText(false);
    }
    private float hotbarLeft(){
        return (getWidth()-Math.min(380f,getWidth()*.50f))*.5f;
    }
    private float hotbarWidth(){return Math.min(380f,getWidth()*.50f);}
    private float hotbarTop(){return getHeight()-48f;}
    private float[] buttonPos(int i){
        final float w=getWidth(),h=getHeight();
        if(i==0)return new float[]{w-72,h-67}; // jump
        if(i==1)return new float[]{w-191,h-94}; // break
        return new float[]{w-124,h-171};        // place
    }
    @Override public void onDraw(Canvas c) {
        super.onDraw(c);
        final float w=getWidth(),h=getHeight();
        // Original console icon textures are loaded from the user's own res.zip.
        final float crossX=w*.5f,crossY=h*.5f;
        if(originalIcons!=null){
            p.setColor(0xffffffff);
            c.drawBitmap(originalIcons,new Rect(0,0,15,15),
                new RectF(crossX-10,crossY-10,crossX+10,crossY+10),p);
        } else {
            p.setColor(0xff232323);p.setStrokeWidth(4);
            c.drawLine(crossX-9,crossY,crossX+9,crossY,p);c.drawLine(crossX,crossY-9,crossX,crossY+9,p);
            p.setColor(0xffffffff);p.setStrokeWidth(2);
            c.drawLine(crossX-8,crossY,crossX+8,crossY,p);c.drawLine(crossX,crossY-8,crossX,crossY+8,p);
        }
        float left=hotbarLeft(),width=hotbarWidth(),top=hotbarTop();
        p.setColor(0xffffffff);
        if(originalGui!=null){
            c.drawBitmap(originalGui,new Rect(0,0,182,22),new RectF(left,top,left+width,top+width*22/182),p);
            float slot=width/9f;
            c.drawBitmap(originalGui,new Rect(0,22,24,45),
              new RectF(left+selected*slot-3,top-3,left+(selected+1)*slot+3,top+width*23/182+3),p);
        }else{
            p.setColor(0xaa0f1216);c.drawRoundRect(new RectF(left,top,left+width,top+44),8,8,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);
            for(int i=0;i<9;++i){
                p.setColor(i==selected?0xffffffff:0x66888888);
                c.drawRect(left+i*width/9f+1,top+1,left+(i+1)*width/9f-1,top+43,p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        final int[] swatches={0xff83a851,0xff83694d,0xff8d8984,0xff666666,
            0xffc4a26f,0xff866847,0xff4f843e,0xffe7ddb3,0xffae6b52};
        for(int i=0;i<9;++i){
            float cx=left+(i+.5f)*width/9f;
            p.setColor(swatches[i]);c.drawRect(cx-7,top+8,cx+7,top+24,p);
        }
        final float baseX=92,baseY=h-97,r=67;
        circle(c,baseX,baseY,r,0x55505e71);
        circle(c,baseX+strafe*r*.58f,baseY-forward*r*.58f,28,0x99697787);
        final String[] labels={"JUMP","BREAK","PLACE"};
        for(int i=0;i<3;++i){
            float[] pos=buttonPos(i);
            circle(c,pos[0],pos[1],i==0?41:38,0x664b596c);
            text(c,labels[i],pos[0],pos[1]+5,i==0?13:12);
        }
    }
    private void updateJoystick(float x,float y) {
        float dx=x-92,dy=(getHeight()-97)-y;
        float scale=Math.max(1f,(float)Math.sqrt(dx*dx+dy*dy)/67f);
        strafe=Math.max(-1,Math.min(1,dx/67f/scale));
        forward=Math.max(-1,Math.min(1,dy/67f/scale));
        action.move(forward,strafe);
        invalidate();
    }
    private boolean inside(float x,float y,float cx,float cy,float radius){
        float dx=x-cx,dy=y-cy;return dx*dx+dy*dy<radius*radius;
    }
    private void press(float x,float y,int pointerId){
        float top=hotbarTop(),left=hotbarLeft(),width=hotbarWidth();
        if(y>=top-10 && x>=left-5 && x<left+width+5){
            selected=Math.max(0,Math.min(8,(int)((x-left)/width*9f)));
            action.select(selected);invalidate();return;
        }
        for(int i=0;i<3;++i){
            float[] pos=buttonPos(i);
            if(inside(x,y,pos[0],pos[1],48)){
                if(i==0)action.jump();else action.interact(i==2);
                return;
            }
        }
        if(x<getWidth()*.39f && y>getHeight()*.43f && movementId==-1){
            movementId=pointerId;updateJoystick(x,y);return;
        }
        if(lookId==-1){lookId=pointerId;lastLookX=x;lastLookY=y;}
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        int mask=e.getActionMasked(),idx=e.getActionIndex();
        if(mask==MotionEvent.ACTION_DOWN||mask==MotionEvent.ACTION_POINTER_DOWN) {
            press(e.getX(idx),e.getY(idx),e.getPointerId(idx));return true;
        }
        if(mask==MotionEvent.ACTION_MOVE){
            for(int i=0;i<e.getPointerCount();++i){
                int id=e.getPointerId(i),xid=i;
                if(id==movementId)updateJoystick(e.getX(xid),e.getY(xid));
                if(id==lookId){
                    float x=e.getX(xid),y=e.getY(xid);
                    action.look(x-lastLookX,y-lastLookY);
                    lastLookX=x;lastLookY=y;
                }
            }
            invalidate();return true;
        }
        if(mask==MotionEvent.ACTION_UP||mask==MotionEvent.ACTION_POINTER_UP||mask==MotionEvent.ACTION_CANCEL){
            int id=e.getPointerId(idx);
            if(id==movementId||mask==MotionEvent.ACTION_CANCEL){
                movementId=-1;forward=0;strafe=0;action.move(0,0);
            }
            if(id==lookId||mask==MotionEvent.ACTION_CANCEL)lookId=-1;
            invalidate();return true;
        }
        return true;
    }
}
