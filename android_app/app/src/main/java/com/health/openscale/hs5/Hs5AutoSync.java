/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.health.openscale.core.data.User;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Executors;

/** Sync on foreground entry, then every 20 seconds after a completed attempt. */
final class Hs5AutoSync implements Application.ActivityLifecycleCallbacks {
    private final Application app;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Hs5SyncLoop loop;
    private Activity resumed;
    private boolean showedWaiting;

    Hs5AutoSync(Application app) {
        this.app = app;
        preferences = app.getSharedPreferences("hs5_measurements", 0);
        loop = new Hs5SyncLoop(new Hs5SyncLoop.Scheduler() {
            public void after(long delay, Runnable action) { timer.postDelayed(action, delay); }
            public void cancel() { timer.removeCallbacksAndMessages(null); }
        }, Executors.newSingleThreadExecutor(), Sync::new, 20_000);
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!activity.getClass().getName().equals("com.health.openscale.MainActivity")) return;
        resumed = activity;
        showedWaiting = false;
        if (preferences.getBoolean("auto_sync", true)) loop.start();
    }
    @Override public void onActivityPaused(Activity activity) {
        if (resumed != activity) return;
        resumed = null;
        loop.stop();
    }
    @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

    private final class Sync implements Hs5SyncLoop.Session {
        private volatile boolean canceled;
        private Hs5WifiClient active;
        private int saved;

        @Override public synchronized void cancel() {
            canceled = true;
            if (active != null) active.close();
        }
        private void checkCanceled() throws IOException {
            if (canceled) throw new IOException("Canceled");
        }
        private synchronized void attach(Hs5WifiClient client) throws IOException {
            if (canceled) { client.close(); throw new IOException("Canceled"); }
            active = client;
        }
        private void status(String message, boolean completed, boolean hint) {
            if (canceled) return;
            preferences.edit().putString("auto_last_status", message)
                    .putLong("auto_last_attempt", System.currentTimeMillis()).apply();
            android.util.Log.i("HS5AutoSync", message);
            main.post(() -> {
                if (resumed == null || canceled) return;
                if (completed || (hint && !showedWaiting)) {
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show();
                    if (hint) showedWaiting = true;
                }
            });
        }
        @Override public void run() {
            try {
                if (!preferences.getBoolean("auto_sync", true)) return;
                Map<String, ?> settings = preferences.getAll();
                String mac = preferences.getString("device_mac", "");
                if (!mac.matches("[0-9A-F]{12}")) return; // No scale linked yet.
                Hs5MeasurementStore store = new Hs5MeasurementStore(app);
                List<User> users = store.users();
                List<Integer> ids = new ArrayList<>();
                for (User user : users) ids.add(user.getId());
                int userId = Hs5ProfileBindings.automaticUser(mac, settings, ids);
                User selected = null;
                for (User user : users) if (user.getId() == userId) selected = user;
                if (selected == null) {
                    status("Link your profile in HS5 measurements to enable automatic sync.", false, true);
                    return;
                }
                User user = selected;
                int scaleId = (Integer)settings.get(mac + ".user." + userId);
                checkCanceled();
                ConnectivityManager manager = app.getSystemService(ConnectivityManager.class);
                Network network = null;
                for (Network candidate : manager.getAllNetworks()) {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(candidate);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { network = candidate; break; }
                }
                if (network == null) {
                    status("HS5: connect to the scale’s Wi-Fi. Sync will retry while openScale is open.", false, true);
                    return;
                }
                List<InetAddress> addresses = discoveryAddresses(manager, network, preferences.getString("device_ip", ""));
                WifiManager.MulticastLock lock = app.getSystemService(WifiManager.class).createMulticastLock("openScale-HS5-auto");
                lock.setReferenceCounted(false);
                lock.acquire();
                try {
                    DatagramSocket socket = new DatagramSocket(null);
                    try {
                        network.bindSocket(socket);
                        socket.bind(new InetSocketAddress(8000));
                        try (Hs5WifiClient client = new Hs5WifiClient(socket, new Hs5WifiClient.Listener() {
                            public void status(String text) { /* Summary only; avoid repeated connection toasts. */ }
                            public void reading(Hs5WifiProtocol.Reading reading) throws IOException {
                                checkCanceled();
                                if (store.save(user, reading)) saved++;
                            }
                        })) {
                            attach(client);
                            Hs5WifiClient.Device device = client.discoverKnown(addresses, 4, mac);
                            checkCanceled();
                            if (device == null) {
                                status("HS5: waiting for the scale. Wake it and keep openScale open.", false, true);
                                return;
                            }
                            preferences.edit().putString("device_ip", device.ip.getHostAddress()).apply();
                            int[] scaleUsers = client.connect(device);
                            checkCanceled();
                            int slot = -1;
                            for (int i = 0; i < scaleUsers.length; i++) if (scaleUsers[i] == scaleId) { slot = i; break; }
                            if (slot < 0) throw new IOException("Reconnect your profile in HS5 measurements.");
                            client.history(slot, scaleId);
                            checkCanceled();
                            preferences.edit().putLong("auto_last_success", System.currentTimeMillis()).apply();
                            status(saved == 0 ? "HS5 is up to date. Checking while openScale is open."
                                    : "HS5: saved " + saved + (saved == 1 ? " new measurement." : " new measurements."), saved > 0, false);
                        }
                    } finally { socket.close(); }
                } finally { lock.release(); }
            } catch (Exception error) {
                status("HS5 sync: " + error.getMessage(), false, true);
            }
        }
    }

    private static List<InetAddress> discoveryAddresses(ConnectivityManager manager, Network network, String remembered) throws IOException {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        if (remembered.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            InetAddress cached = InetAddress.getByName(remembered);
            if (cached.isSiteLocalAddress()) addresses.add(cached);
        }
        addresses.add(InetAddress.getByName("255.255.255.255"));
        LinkProperties properties = manager.getLinkProperties(network);
        if (properties != null) for (LinkAddress address : properties.getLinkAddresses()) {
            if (!(address.getAddress() instanceof Inet4Address) || address.getPrefixLength() >= 31) continue;
            int ip = ByteBuffer.wrap(address.getAddress().getAddress()).getInt();
            int mask = address.getPrefixLength() == 0 ? 0 : -1 << (32 - address.getPrefixLength());
            addresses.add(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip | ~mask).array()));
        }
        return new ArrayList<>(addresses);
    }
}
