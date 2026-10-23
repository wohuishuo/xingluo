package app.xingluo.video;

import android.animation.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;

public final class Ui {
    public static final int BG=Color.rgb(250,249,246), INK=Color.rgb(48,43,65), MUTED=Color.rgb(137,131,150), PURPLE=Color.rgb(126,105,189), LILAC=Color.rgb(238,233,248), LINE=Color.rgb(235,231,239);
    public static int dp(Context c,float v) { return (int)(v*c.getResources().getDisplayMetrics().density+.5f); }
    public static GradientDrawable rounded(int color,float radius,Context c) { GradientDrawable d=new GradientDrawable(); d.setColor(color);d.setCornerRadius(dp(c,radius));return d; }
    public static GradientDrawable border(int color,int stroke,float radius,Context c) { GradientDrawable d=rounded(color,radius,c);d.setStroke(dp(c,1),stroke);return d; }
    public static GradientDrawable gradient(int a,int b,float radius,Context c) { GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{a,b});d.setCornerRadius(dp(c,radius));return d; }
    public static TextView text(Context c,String text,int size,int color,boolean bold) {
        TextView t=new TextView(c);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;
    }
    public static LinearLayout column(Context c) { LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l; }
    public static LinearLayout row(Context c) { LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l; }
    public static void pad(View v,int a,int b,int c,int d) { v.setPadding(dp(v.getContext(),a),dp(v.getContext(),b),dp(v.getContext(),c),dp(v.getContext(),d)); }
    public static void gap(LinearLayout l,int height) { l.addView(new View(l.getContext()),new LinearLayout.LayoutParams(1,dp(l.getContext(),height))); }
    public static void click(View v,Runnable action) {
        v.setClickable(true);v.setFocusable(true);
        v.setOnClickListener(x->{x.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action.run();});
        if(v.getBackground()!=null) v.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x207E69BD),v.getBackground(),null));
    }
    public static TextView button(Context c,String title,boolean primary,Runnable action) {
        TextView b=text(c,title,15,primary?Color.WHITE:PURPLE,true);b.setGravity(Gravity.CENTER);pad(b,16,15,16,15);
        b.setBackground(primary?gradient(0xff9580d1,0xff7762b5,18,c):rounded(LILAC,16,c));click(b,action);return b;
    }
    public static void enter(View v) { v.setAlpha(0f);v.setTranslationY(dp(v.getContext(),9));v.animate().alpha(1).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start(); }
    public static final class Icon extends View {
        private final String name;private final int color;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        public Icon(Context c,String name,int color) { super(c);this.name=name;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);canvas.save();float scale=Math.min(getWidth(),getHeight())/24f;canvas.translate((getWidth()-scale*24)/2,(getHeight()-scale*24)/2);canvas.scale(scale,scale);
            p.setColor(color);p.setStrokeWidth(1.7f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            Path path=new Path();
            switch(name) {
                case "star" -> { for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float r=i%2==0?9:4.6f;float x=12+(float)Math.cos(a)*r,y=12+(float)Math.sin(a)*r;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();canvas.drawPath(path,p); }
                case "home" -> {path.moveTo(3,10);path.lineTo(12,3);path.lineTo(21,10);path.moveTo(5,9);path.lineTo(5,21);path.lineTo(10,21);path.lineTo(10,15);path.lineTo(14,15);path.lineTo(14,21);path.lineTo(19,21);path.lineTo(19,9);canvas.drawPath(path,p);}
                case "bag" -> {canvas.drawRoundRect(4,7,20,21,4,4,p);canvas.drawArc(8,2,16,12,180,180,false,p);canvas.drawLine(9,13,15,13,p);}
                case "settings" -> {canvas.drawCircle(12,12,7,p);canvas.drawCircle(12,12,2.5f,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*7,12+(float)Math.sin(a)*7,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,p);}}
                case "paste" -> {canvas.drawRoundRect(5,5,19,21,3,3,p);canvas.drawRoundRect(9,2,15,7,2,2,p);canvas.drawLine(9,12,15,12,p);canvas.drawLine(9,16,13,16,p);}
                case "download" -> {canvas.drawLine(12,3,12,15,p);canvas.drawLine(7,10,12,15,p);canvas.drawLine(17,10,12,15,p);path.moveTo(4,17);path.lineTo(4,21);path.lineTo(20,21);path.lineTo(20,17);canvas.drawPath(path,p);}
                case "link" -> {canvas.save();canvas.rotate(-40,12,12);canvas.drawRoundRect(3,8,13,16,4,4,p);canvas.drawRoundRect(11,8,21,16,4,4,p);canvas.restore();}
                case "check" -> {path.moveTo(5,12);path.lineTo(10,17);path.lineTo(20,6);canvas.drawPath(path,p);}
                case "play" -> {path.moveTo(9,5);path.lineTo(19,12);path.lineTo(9,19);path.close();canvas.drawPath(path,p);}
                case "arrow" -> {canvas.drawLine(4,12,20,12,p);canvas.drawLine(14,6,20,12,p);canvas.drawLine(14,18,20,12,p);}
                case "chevron" -> {path.moveTo(9,5);path.lineTo(16,12);path.lineTo(9,19);canvas.drawPath(path,p);}
                case "close" -> {canvas.drawLine(6,6,18,18,p);canvas.drawLine(18,6,6,18,p);}
                case "globe" -> {canvas.drawCircle(12,12,9,p);canvas.drawOval(8,3,16,21,p);canvas.drawLine(3,12,21,12,p);}
                case "share" -> {canvas.drawCircle(5,12,2.5f,p);canvas.drawCircle(19,5,2.5f,p);canvas.drawCircle(19,19,2.5f,p);canvas.drawLine(7,11,17,6,p);canvas.drawLine(7,13,17,18,p);}
                default -> canvas.drawCircle(12,12,7,p);
            }
            canvas.restore();
        }
    }
}
