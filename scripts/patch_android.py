"""cap add android-ის შემდეგ: ვამატებთ ჩვენს პლაგინს, MainActivity-ს და Android-ის ნებართვებს."""
import pathlib
import shutil

root = pathlib.Path(__file__).resolve().parent.parent
java_dir = root / "android" / "app" / "src" / "main" / "java" / "ge" / "kadastr" / "savele"
java_dir.mkdir(parents=True, exist_ok=True)
for name in ("NmeaBluetoothPlugin.java", "DistoBlePlugin.java", "SystemLocationPlugin.java", "FileOutPlugin.java", "MainActivity.java"):
    shutil.copy(root / "native" / name, java_dir / name)
    print("copied", name)

manifest = root / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
perms = [
    '<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />',
    '<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />',
    '<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />',
    '<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />',
    '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />',
    '<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />',
    '<uses-permission android:name="android.permission.INTERNET" />',
]
add = ""
for p in perms:
    key = p.split('"')[1]
    if key not in text:
        add += "    " + p + "\n"
text = text.replace("</manifest>", add + "</manifest>")
manifest.write_text(text, encoding="utf-8")
print("manifest patched")


# bluetooth-le: connect() სრულდება მხოლოდ MTU-ს პასუხზე; Bosch PLR/GLM MTU-ზე არ პასუხობს და "Connection timeout" გამოდის.
# ვასწორებთ: კავშირი დასრულებულად ითვლება სერვისების აღმოჩენისთანავე.
ble = root / "node_modules" / "@capacitor-community" / "bluetooth-le" / "android" / "src" / "main" / "java" / "com" / "capacitorjs" / "community" / "plugins" / "bluetoothle" / "Device.kt"
src = ble.read_text(encoding="utf-8")
old = "requestMtu(REQUEST_MTU)"
if src.count(old) != 1:
    raise SystemExit("bluetooth-le Device.kt: unexpected content, patch failed")
ble.write_text(src.replace(old, 'resolve("connect", "Connected.")'), encoding="utf-8")
print("bluetooth-le patched (no MTU wait)")
