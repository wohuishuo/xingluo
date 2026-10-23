package app.xingluo.video;
import android.content.*;
import android.net.Uri;

public final class Preferences {
    private final SharedPreferences prefs;
    public Preferences(Context c){prefs=c.getApplicationContext().getSharedPreferences("preferences",Context.MODE_PRIVATE);}
    public int quality(){return prefs.getInt("quality",720);}
    public void quality(int height){if(height!=1080&&height!=720&&height!=480&&height!=360)throw new IllegalArgumentException("画质无效");prefs.edit().putInt("quality",height).apply();}
    public String destination(){return prefs.getString("destination","gallery");}
    public String folder(){return prefs.getString("folder","");}
    public void destination(String value){if(!value.equals("gallery")&&!value.equals("downloads")&&!value.equals("custom"))throw new IllegalArgumentException("保存位置无效");prefs.edit().putString("destination",value).apply();}
    public void folder(Uri uri,String label){prefs.edit().putString("folder",uri.toString()).putString("folderLabel",label).putString("destination","custom").apply();}
    public String locationLabel(){return switch(destination()){case "downloads"->"下载文件夹 · Download / 星落";case "custom"->"自选文件夹 · "+prefs.getString("folderLabel","已授权文件夹");default->"系统相册 · Movies / 星落";};}
}
