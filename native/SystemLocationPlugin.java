package ge.kadastr.savele;

import android.Manifest;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android-ის ყველა მდებარეობის პროვაიდერის პირდაპირი კითხვა, მათ შორის Cube-ის Mock Location.
 * 1) requestLocationUpdates ყველა ჩართულ პროვაიდერზე (პროვაიდერები, რომლებიც მოგვიანებით ჩნდება, ყოველ წამს ემატება);
 * 2) ყოველ წამს getLastKnownLocation ყველა პროვაიდერზე — Mock პროვაიდერის პოზიციაც ასე არ გვრჩება;
 * 3) „diag“ მოვლენა: რომელი პროვაიდერი რას აბრუნებს (ეკრანზე ჩანს დიაგნოსტიკისთვის).
 */
@CapacitorPlugin(name = "SystemLocation", permissions = {
    @Permission(alias = "location", strings = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
})
public class SystemLocationPlugin extends Plugin implements LocationListener {
    private LocationManager manager;
    private boolean watching;
    private final Set<String> requested = new HashSet<>();
    private final Map<String, String> lastSent = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!watching) return;
            refresh();
            handler.postDelayed(this, 1000L);
        }
    };

    @PluginMethod public void start(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "locationPermission");
        } else begin(call);
    }

    @PermissionCallback private void locationPermission(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) call.reject("მდებარეობის ზუსტი ნებართვა არ არის მიცემული");
        else begin(call);
    }

    private void begin(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            manager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            try {
                manager.removeUpdates(this);
                requested.clear();
                lastSent.clear();
                handler.removeCallbacks(tick);
                watching = true;
                refresh();
                handler.postDelayed(tick, 1000L);
                call.resolve();
            } catch (Exception e) {
                watching = false;
                call.reject(String.valueOf(e.getMessage()));
            }
        });
    }

    @PluginMethod public void stop(PluginCall call) {
        watching = false;
        handler.removeCallbacks(tick);
        if (manager != null) { try { manager.removeUpdates(this); } catch (Exception ignored) {} }
        requested.clear();
        call.resolve();
    }

    private boolean isMock(Location l) {
        try { return l.isFromMockProvider(); } catch (Throwable t) { return false; }
    }

    private void refresh() {
        if (manager == null) return;
        List<String> all = manager.getAllProviders();
        com.getcapacitor.JSArray diag = new com.getcapacitor.JSArray();
        boolean anyEnabled = false;
        for (String p : all) {
            JSObject d = new JSObject();
            d.put("name", p);
            boolean en = false;
            try { en = manager.isProviderEnabled(p); } catch (Exception ignored) {}
            d.put("enabled", en);
            if (en) anyEnabled = true;
            try {
                if (en && !requested.contains(p)) {
                    manager.requestLocationUpdates(p, 500L, 0f, this, Looper.getMainLooper());
                    requested.add(p);
                }
                Location l = manager.getLastKnownLocation(p);
                if (l != null) {
                    long age = Math.abs(System.currentTimeMillis() - l.getTime());
                    d.put("acc", l.hasAccuracy() ? (double) l.getAccuracy() : -1.0);
                    d.put("age", age / 1000);
                    d.put("mock", isMock(l));
                    String key = l.getTime() + "|" + l.getLatitude() + "|" + l.getLongitude();
                    if ((isMock(l) || !key.equals(lastSent.get(p))) && (isMock(l) || age < 120000)) {
                        lastSent.put(p, key);
                        emit(l);
                    }
                }
            } catch (SecurityException e) {
                d.put("err", "ნებართვა");
            } catch (Exception e) {
                d.put("err", String.valueOf(e.getMessage()));
            }
            diag.put(d);
        }
        JSObject ev = new JSObject();
        ev.put("providers", diag);
        notifyListeners("diag", ev);
        if (!anyEnabled) {
            JSObject e = new JSObject();
            e.put("message", "Android-ის მდებარეობა გამორთულია");
            notifyListeners("error", e);
        }
    }

    private Object nul() { return org.json.JSONObject.NULL; }

    private void emit(Location loc) {
        JSObject data = new JSObject();
        data.put("latitude", loc.getLatitude());
        data.put("longitude", loc.getLongitude());
        data.put("accuracy", loc.hasAccuracy() ? (Object) Double.valueOf(loc.getAccuracy()) : nul());
        data.put("altitude", loc.hasAltitude() ? (Object) Double.valueOf(loc.getAltitude()) : nul());
        data.put("altitudeAccuracy", Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy() ? (Object) Double.valueOf(loc.getVerticalAccuracyMeters()) : nul());
        data.put("timestamp", loc.getTime());
        data.put("mock", isMock(loc));
        data.put("provider", loc.getProvider());
        data.put("age", Math.abs(System.currentTimeMillis() - loc.getTime()) / 1000);
        notifyListeners("position", data);
    }

    @Override public void onLocationChanged(Location loc) {
        if (!watching) return;
        if (!isMock(loc) && Math.abs(System.currentTimeMillis() - loc.getTime()) > 120000) return;
        lastSent.put(loc.getProvider(), loc.getTime() + "|" + loc.getLatitude() + "|" + loc.getLongitude());
        emit(loc);
    }

    @Override public void onProviderDisabled(String provider) { requested.remove(provider); }
    @Override public void onProviderEnabled(String provider) { requested.remove(provider); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override protected void handleOnDestroy() {
        watching = false;
        handler.removeCallbacks(tick);
        if (manager != null) { try { manager.removeUpdates(this); } catch (Exception ignored) {} }
    }
}
