package ge.kadastr.savele;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/**
 * BLE მანძილმზომი (Bosch PLR/GLM, Leica DISTO და სხვ.): საკუთარი მარტივი GATT კლიენტი.
 * მიზეზი: მზა პლაგინის connect() ელოდება MTU-ს პასუხს და status-ის შეცდომებს "timeout"-ად აჩვენებს.
 * აქ MTU არ მოითხოვება, ყველა მოვლენა ლოგდება და ფორმატი მარტივია.
 *
 * JS: connect({address, profiles:[{svc,ch,init:"c0 55 02 01 00 1a"}]}) -> {services:[...]}, disconnect()
 * მოვლენები: "log" {msg}, "data" {svc,ch,hex}, "disconnected" {status}
 */
@CapacitorPlugin(name = "DistoBle")
public class DistoBlePlugin extends Plugin {

    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<BluetoothGattDescriptor> descQ = new ArrayDeque<BluetoothGattDescriptor>();
    private volatile BluetoothGatt gatt;
    private volatile PluginCall pending;
    private volatile boolean ready = false;
    private JSArray profiles = new JSArray();
    private JSArray servicesJson = new JSArray();
    private Runnable timeoutTask;

    private void log(String m) {
        JSObject o = new JSObject();
        o.put("msg", m);
        notifyListeners("log", o);
    }

    private static String hex(byte[] v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", v[i] & 0xff));
        }
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        String[] p = s.trim().split("\\s+");
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) b[i] = (byte) Integer.parseInt(p[i], 16);
        return b;
    }

    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        ready = false;
        descQ.clear();
        if (g != null) {
            try {
                g.disconnect();
            } catch (Exception ignored) {
            }
            try {
                g.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            main.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
    }

    private void fail(String msg) {
        cancelTimeout();
        log("შეცდომა: " + msg);
        PluginCall c = pending;
        pending = null;
        closeGatt();
        if (c != null) {
            c.reject(msg);
        }
    }

    private void succeed() {
        cancelTimeout();
        PluginCall c = pending;
        pending = null;
        ready = true;
        if (c != null) {
            JSObject ret = new JSObject();
            ret.put("services", servicesJson);
            c.resolve(ret);
        }
    }

    @PluginMethod
    public void connect(final PluginCall call) {
        final String address = call.getString("address");
        if (address == null || address.isEmpty()) {
            call.reject("მისამართი არ არის");
            return;
        }
        JSArray pr = call.getArray("profiles");
        profiles = pr != null ? pr : new JSArray();
        closeGatt();
        pending = call;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                    if (ad == null) {
                        fail("Bluetooth არ არის");
                        return;
                    }
                    try {
                        ad.cancelDiscovery();
                    } catch (Exception ignored) {
                    }
                    BluetoothDevice dev = ad.getRemoteDevice(address);
                    log("connectGatt " + address);
                    BluetoothGatt g;
                    if (Build.VERSION.SDK_INT >= 23) {
                        g = dev.connectGatt(getContext(), false, cb, BluetoothDevice.TRANSPORT_LE);
                    } else {
                        g = dev.connectGatt(getContext(), false, cb);
                    }
                    gatt = g;
                    timeoutTask = new Runnable() {
                        @Override
                        public void run() {
                            fail("დრო ამოიწურა (25 წმ) — კავშირი ვერ დამყარდა");
                        }
                    };
                    main.postDelayed(timeoutTask, 25000);
                } catch (SecurityException e) {
                    fail("Bluetooth-ის ნებართვა არ არის მიცემული");
                } catch (Exception e) {
                    fail(String.valueOf(e));
                }
            }
        });
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        cancelTimeout();
        pending = null;
        closeGatt();
        call.resolve();
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int status, int newState) {
            log("state status=" + status + " new=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!g.discoverServices()) {
                                fail("discoverServices ვერ დაიწყო");
                            }
                        } catch (Exception e) {
                            fail(String.valueOf(e));
                        }
                    }
                }, 600);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasReady = ready;
                if (pending != null) {
                    fail("კავშირი გაწყდა (status " + status + ")");
                } else {
                    if (gatt == g) {
                        gatt = null;
                    }
                    try {
                        g.close();
                    } catch (Exception ignored) {
                    }
                    ready = false;
                    if (wasReady) {
                        JSObject o = new JSObject();
                        o.put("status", status);
                        notifyListeners("disconnected", o);
                    }
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            log("services status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("სერვისების აღმოჩენა ვერ მოხერხდა (status " + status + ")");
                return;
            }
            servicesJson = new JSArray();
            descQ.clear();
            try {
                List<BluetoothGattService> list = g.getServices();
                for (BluetoothGattService sv : list) {
                    JSObject so = new JSObject();
                    so.put("uuid", sv.getUuid().toString());
                    JSArray chs = new JSArray();
                    for (BluetoothGattCharacteristic c : sv.getCharacteristics()) {
                        int pr = c.getProperties();
                        JSObject co = new JSObject();
                        co.put("uuid", c.getUuid().toString());
                        StringBuilder ps = new StringBuilder();
                        if ((pr & BluetoothGattCharacteristic.PROPERTY_READ) != 0) ps.append("read,");
                        if ((pr & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) ps.append("write,");
                        if ((pr & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) ps.append("writeNoResp,");
                        if ((pr & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) ps.append("notify,");
                        if ((pr & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) ps.append("indicate,");
                        co.put("props", ps.toString());
                        chs.put(co);
                        boolean notify = (pr & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                        boolean indicate = (pr & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                        if (notify || indicate) {
                            g.setCharacteristicNotification(c, true);
                            BluetoothGattDescriptor d = c.getDescriptor(CCC);
                            if (d != null) {
                                d.setValue(notify
                                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                                descQ.add(d);
                            }
                        }
                    }
                    so.put("chars", chs);
                    servicesJson.put(so);
                }
            } catch (Exception e) {
                fail("სერვისების წაკითხვა: " + e);
                return;
            }
            nextDescriptor(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            log("descriptor " + d.getCharacteristic().getUuid().toString().substring(0, 8) + " status=" + status);
            nextDescriptor(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            log("write " + c.getUuid().toString().substring(0, 8) + " status=" + status);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            JSObject o = new JSObject();
            o.put("svc", c.getService().getUuid().toString());
            o.put("ch", c.getUuid().toString());
            o.put("hex", hex(c.getValue()));
            notifyListeners("data", o);
        }
    };

    private void nextDescriptor(BluetoothGatt g) {
        BluetoothGattDescriptor d = descQ.poll();
        while (d != null) {
            boolean ok = false;
            try {
                ok = g.writeDescriptor(d);
            } catch (Exception e) {
                log("writeDescriptor: " + e);
            }
            if (ok) return;
            d = descQ.poll();
        }
        sendInit(g);
    }

    private void sendInit(BluetoothGatt g) {
        try {
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject p = profiles.getJSONObject(i);
                String init = p.optString("init", "");
                if (init.isEmpty()) continue;
                BluetoothGattService sv = g.getService(UUID.fromString(p.getString("svc")));
                if (sv == null) continue;
                BluetoothGattCharacteristic target = null;
                String want = p.optString("ch", "").toLowerCase();
                for (BluetoothGattCharacteristic c : sv.getCharacteristics()) {
                    int pr = c.getProperties();
                    boolean w = (pr & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
                    if (!w) continue;
                    if (target == null || c.getUuid().toString().toLowerCase().equals(want)) target = c;
                }
                if (target == null) {
                    log("საწყისი ბრძანება: ჩასაწერი მახასიათებელი ვერ მოიძებნა");
                    break;
                }
                int pr = target.getProperties();
                target.setWriteType((pr & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                target.setValue(unhex(init));
                boolean ok = g.writeCharacteristic(target);
                log("საწყისი ბრძანება → " + target.getUuid().toString().substring(0, 8) + " " + (ok ? "გაიგზავნა" : "ვერ გაიგზავნა"));
                break;
            }
        } catch (Exception e) {
            log("sendInit: " + e);
        }
        succeed();
    }
}
