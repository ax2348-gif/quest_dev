package dev.quest.runtime;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.*;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Dedicated resizable panel, containing only packaged terminal assets. */
public final class TerminalActivity extends Activity {
    private static final String ORIGIN="https://terminal.quest.invalid/";
    private final Handler main=new Handler(Looper.getMainLooper());
    private WebView web;
    private TerminalSession session;
    static void open(Context context,String id){
        if(id==null)id=UUID.randomUUID().toString();
        context.startActivity(new Intent(context,TerminalActivity.class)
            .setData(Uri.parse("quest-terminal://session/"+id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NEW_DOCUMENT));
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        String id=getIntent().getData()==null?UUID.randomUUID().toString():getIntent().getData().getLastPathSegment();
        try {
            startForegroundService(new Intent(this,RuntimeService.class));
            session=TerminalSession.get(this,id);
            setTitle(session.label+" · Quest");
            web=new WebView(this);web.setBackgroundColor(Color.TRANSPARENT);
            WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);
            settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
            settings.setBlockNetworkLoads(true);settings.setDomStorageEnabled(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportMultipleWindows(false);
            web.setWebChromeClient(new WebChromeClient(){
                @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){
                    new android.app.AlertDialog.Builder(TerminalActivity.this)
                        .setMessage(message).setPositiveButton("Continue",(dialog,which)->result.confirm())
                        .setNegativeButton("Cancel",(dialog,which)->result.cancel())
                        .setOnCancelListener(dialog->result.cancel()).show();
                    return true;
                }
            });
            web.addJavascriptInterface(new Bridge(),"QuestTerminal");
            web.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    String url=request.getUrl().toString();
                    if(url.startsWith(ORIGIN)){
                        String file=url.substring(ORIGIN.length());
                        if(file.matches("[a-zA-Z0-9._-]+"))try {
                            String mime=file.endsWith(".js")?"text/javascript":file.endsWith(".css")?"text/css":"text/html";
                            return new WebResourceResponse(mime,"UTF-8",getAssets().open("terminal/"+file));
                        }catch(IOException ignored){}
                    }
                    return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",null,new ByteArrayInputStream(new byte[0]));
                }
            });
            setContentView(web);web.loadUrl(ORIGIN+"index.html");
        }catch(Exception e){
            android.widget.TextView error=new android.widget.TextView(this);
            error.setText("Terminal could not start:\n"+e);error.setTextColor(Color.WHITE);
            error.setBackgroundColor(Color.rgb(15,20,18));error.setPadding(30,30,30,30);setContentView(error);
            android.util.Log.e("QuestTerminal","Start failed",e);
        }
    }
    private final class Bridge {
        @JavascriptInterface public String poll(long seq){
            try{return session.drain(seq);}catch(Exception e){return "{\"error\":"+JSONObject.quote(e.toString())+"}";}
        }
        @JavascriptInterface public void input(String base64){
            if(base64.length()>90000)return;
            try{session.write(Base64.decode(base64,Base64.DEFAULT));}catch(IllegalArgumentException ignored){}
        }
        @JavascriptInterface public void resize(int cols,int rows){session.resize(cols,rows);}
        @JavascriptInterface public String preferences(){
            android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
            return "{\"opacity\":"+prefs.getInt("opacity",82)
                +",\"brightness\":"+prefs.getInt("brightness",100)
                +",\"contrast\":"+prefs.getInt("contrast",100)+"}";
        }
        @JavascriptInterface public void opacity(int value){getPreferences(MODE_PRIVATE).edit().putInt("opacity",Math.max(25,Math.min(100,value))).apply();}
        @JavascriptInterface public void appearance(int brightness,int contrast){
            getPreferences(MODE_PRIVATE).edit()
                .putInt("brightness",Math.max(60,Math.min(140,brightness)))
                .putInt("contrast",Math.max(60,Math.min(180,contrast))).apply();
        }
        @JavascriptInterface public void keyboard(){main.post(()->{
            web.requestFocus();
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(web,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });}
        @JavascriptInterface public void newWindow(){main.post(()->TerminalActivity.open(TerminalActivity.this,null));}
        @JavascriptInterface public String restart(){
            try{session.restart();return "";}catch(Exception e){return e.toString();}
        }
        @JavascriptInterface public void closeWindow(){main.post(()->finishAndRemoveTask());}
        @JavascriptInterface public String endSession(){
            try{TerminalSession.remove(session.id);main.post(()->finishAndRemoveTask());return "";}
            catch(Exception e){return e.toString();}
        }
    }
    @Override public void onDestroy(){
        if(web!=null){web.removeJavascriptInterface("QuestTerminal");web.destroy();}
        // The shell intentionally remains available via the runtime's session list.
        super.onDestroy();
    }
}
