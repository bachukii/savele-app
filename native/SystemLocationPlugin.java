package ge.kadastr.savele;

import android.Manifest;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.content.Context;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;

/** Reads Android providers directly, including positions supplied by Cube mock location. */
@CapacitorPlugin(name = "SystemLocation", permissions = {
    @Permission(alias = "location", strings = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
})
public class SystemLocationPlugin extends Plugin implements LocationListener {
    private LocationManager manager;
    private boolean watching;
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
            manager = (LocationManager)getContext().getSystemService(Context.LOCATION_SERVICE);
            try {
                manager.removeUpdates(this);
                boolean enabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                if (!enabled) { call.reject("ჩართეთ Android-ის მდებარეობა და Cube-ის Mock Location"); return; }
                watching = true;
                for (String provider : manager.getAllProviders()) {
                    if (manager.isProviderEnabled(provider)) manager.requestLocationUpdates(provider, 500L, 0f, this, Looper.getMainLooper());
                }
                call.resolve();
            } catch (Exception e) { watching = false; manager.removeUpdates(this); call.reject(e.getMessage()); }
        });
    }
    @PluginMethod public void stop(PluginCall call) {
        watching = false;
        if (manager != null) manager.removeUpdates(this);
        call.resolve();
    }
    @Override public void onLocationChanged(Location loc) {
        if (!watching || Math.abs(System.currentTimeMillis()-loc.getTime()) > 30000) return;
        JSObject data = new JSObject();
        data.put("latitude", loc.getLatitude()); data.put("longitude", loc.getLongitude());
        data.put("accuracy", loc.hasAccuracy() ? loc.getAccuracy() : JSONObjectNull());
        data.put("altitude", loc.hasAltitude() ? loc.getAltitude() : JSONObjectNull());
        data.put("altitudeAccuracy", Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy() ? loc.getVerticalAccuracyMeters() : JSONObjectNull());
        data.put("timestamp", loc.getTime()); data.put("mock", loc.isFromMockProvider()); data.put("provider", loc.getProvider());
        notifyListeners("position", data);
    }
    private Object JSONObjectNull() { return org.json.JSONObject.NULL; }
    @Override public void onProviderDisabled(String provider) {
        if (watching && manager != null && !manager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            JSObject e = new JSObject(); e.put("message", "Android-ის მდებარეობა გამორთულია"); notifyListeners("error", e);
        }
    }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override protected void handleOnDestroy() { watching = false; if (manager != null) manager.removeUpdates(this); }
}
