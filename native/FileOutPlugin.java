package ge.kadastr.savele;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.OutputStream;

/**
 * ფაილის გატანა Android-ზე: შენახვა „ჩამოტვირთვებში“ (Downloads/Savele), გაზიარება (Viber, WhatsApp, ელფოსტა…) და გახსნა (PDF).
 * WebView-ში blob-ბმულით ჩამოტვირთვა არ მუშაობს, ამიტომ ფაილს აპი თვითონ წერს MediaStore-ში.
 * JS: save({name,mime,data(base64)}), share({...}), view({...})
 */
@CapacitorPlugin(name = "FileOut")
public class FileOutPlugin extends Plugin {

    private Uri store(String name, String mime, String b64) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new Exception("ფაილის შენახვას Android 10 ან უფრო ახალი სჭირდება");
        byte[] data = Base64.decode(b64, Base64.DEFAULT);
        ContentResolver r = getContext().getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Savele");
        Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new Exception("ფაილის შექმნა ვერ მოხერხდა");
        OutputStream o = r.openOutputStream(u);
        if (o == null) throw new Exception("ფაილის ჩაწერა ვერ მოხერხდა");
        try { o.write(data); } finally { o.close(); }
        return u;
    }

    private String mimeOf(PluginCall call) {
        String m = call.getString("mime");
        return (m == null || m.isEmpty()) ? "application/octet-stream" : m;
    }

    @PluginMethod
    public void save(PluginCall call) {
        try {
            String name = call.getString("name", "file");
            store(name, mimeOf(call), call.getString("data", ""));
            JSObject ret = new JSObject();
            ret.put("path", "Download/Savele/" + name);
            call.resolve(ret);
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void share(PluginCall call) {
        try {
            String name = call.getString("name", "file");
            Uri u = store(name, mimeOf(call), call.getString("data", ""));
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mimeOf(call));
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.putExtra(Intent.EXTRA_SUBJECT, name);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(i, name);
            ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(ch);
            call.resolve();
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void view(PluginCall call) {
        try {
            String name = call.getString("name", "file");
            Uri u = store(name, mimeOf(call), call.getString("data", ""));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, mimeOf(call));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try { getActivity().startActivity(i); call.resolve(); }
            catch (ActivityNotFoundException ex) { call.reject("ფაილის გასახსნელი აპი არ არის დაყენებული"); }
        } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
    }
}
