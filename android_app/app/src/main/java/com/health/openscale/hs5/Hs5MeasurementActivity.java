/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.*;
import com.health.openscale.core.data.User;
import com.health.openscale.core.data.GenderType;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreground HS5 measurements over the phone's Wi-Fi network, stored in openScale. */
public final class Hs5MeasurementActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> actions = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private final List<Hs5WifiClient.Device> devices = new ArrayList<>();
    private Spinner userChoice, deviceChoice;
    private TextView output;
    private Button cancel;
    private volatile Hs5WifiClient active;
    private volatile boolean canceled;
    private boolean running;
    private SharedPreferences preferences;
    private Hs5MeasurementStore store;
    private interface Work { void run() throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("hs5_measurements", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad); scroll.addView(body); setContentView(scroll);
        TextView title = new TextView(this); title.setText("HS5 measurements"); title.setTextSize(24); body.addView(title);
        TextView intro = new TextView(this);
        intro.setText("Use the same Wi-Fi as the scale. After linking your profile, open openScale after weighing to sync automatically. Keep the scale awake and the app open until your reading appears. You can also sync manually here.");
        body.addView(intro);
        Switch automatic = new Switch(this);
        automatic.setText("Sync automatically while openScale is open");
        automatic.setChecked(preferences.getBoolean("auto_sync", true));
        automatic.setOnCheckedChangeListener((button, enabled) -> preferences.edit().putBoolean("auto_sync", enabled).apply());
        body.addView(automatic);
        TextView automaticStatus = new TextView(this);
        String last = preferences.getString("auto_last_status", "Automatic sync starts when you return to openScale.");
        long attempted = preferences.getLong("auto_last_attempt", 0);
        automaticStatus.setText(attempted == 0 ? last : "Last automatic check ("
                + java.text.DateFormat.getDateTimeInstance().format(new Date(attempted)) + "): " + last);
        body.addView(automaticStatus);
        userChoice = new Spinner(this); body.addView(userChoice);
        deviceChoice = new Spinner(this); body.addView(deviceChoice);
        action(body, "Find scale on Wi-Fi", () -> runTask(this::discover));
        action(body, "Find scale by IP address", this::findByAddress);
        action(body, "Connect my scale profile", this::chooseProfile);
        action(body, "Sync stored measurements", () -> collect(true));
        action(body, "Live measurement (experimental)", () -> collect(false));
        cancel = new Button(this); cancel.setText("Stop"); cancel.setEnabled(false);
        cancel.setOnClickListener(v -> { canceled = true; Hs5WifiClient client = active; if (client != null) client.close(); });
        body.addView(cancel);
        action(body, "Open measurements", () -> startActivity(new Intent().setClassName(this, "com.health.openscale.MainActivity")));
        output = new TextView(this); output.setTextIsSelectable(true); body.addView(output);
        runTask(() -> {
            store = new Hs5MeasurementStore(this);
            List<User> loaded = store.users();
            main.post(() -> {
                users.clear(); users.addAll(loaded);
                List<String> labels = new ArrayList<>(); for (User u : users) labels.add(u.getName());
                userChoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
                String mac = preferences.getString("device_mac", ""), ip = preferences.getString("device_ip", "");
                if (mac.matches("[0-9A-F]{12}") && ip.matches("[0-9.]+")) {
                    try { devices.add(new Hs5WifiClient.Device(InetAddress.getByName(ip), mac)); refreshDevices(); }
                    catch (UnknownHostException ignored) {}
                }
                status(users.isEmpty() ? "Create your user profile in openScale first." : "Ready to find your scale.");
            });
        });
    }
    private void action(LinearLayout body, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run());
        actions.add(button); body.addView(button);
    }
    private void refreshDevices() {
        deviceChoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, devices));
    }
    private void status(String text) {
        android.util.Log.i("HS5Wifi", text);
        main.post(() -> { if (isDestroyed()) return; if (output.length() > 16000) output.setText(""); output.append(text + "\n"); });
    }
    private void busy(boolean value) {
        running = value; for (Button action : actions) action.setEnabled(!value);
        userChoice.setEnabled(!value); deviceChoice.setEnabled(!value); cancel.setEnabled(value);
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void runTask(Work work) {
        if (running) return;
        canceled = false; busy(true);
        worker.submit(() -> {
            try { work.run(); }
            catch (Exception error) { status(canceled ? "Stopped." : "Could not complete: " + error.getMessage()); }
            finally {
                Hs5WifiClient client = active; if (client != null) client.close(); active = null;
                main.post(() -> { if (!isDestroyed()) busy(false); });
            }
        });
    }
    private Network wifi() throws IOException {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network;
        }
        throw new IOException("Connect your phone to the same Wi-Fi as the scale.");
    }
    private Hs5WifiClient client(Network network, User user) throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        try {
            network.bindSocket(socket); socket.bind(new InetSocketAddress(8000));
            Hs5WifiClient result = new Hs5WifiClient(socket, new Hs5WifiClient.Listener() {
                public void status(String text) { Hs5MeasurementActivity.this.status(text); }
                public void reading(Hs5WifiProtocol.Reading reading) throws IOException {
                    if (user == null) throw new IOException("No selected openScale user");
                    boolean saved = store.save(user, reading);
                    status(saved ? "Saved " + reading.weight + " kg to " + user.getName() + " in openScale."
                            : "Reading already saved; duplicate skipped.");
                }
            });
            active = result;
            if (canceled) { result.close(); throw new IOException("Canceled"); }
            return result;
        } catch (IOException | RuntimeException error) { socket.close(); throw error; }
    }
    private WifiManager.MulticastLock multicast() {
        WifiManager manager = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiManager.MulticastLock lock = manager.createMulticastLock("openScale-HS5");
        lock.setReferenceCounted(false); lock.acquire(); return lock;
    }
    private void discover() throws Exception {
        Network network = wifi(); List<InetAddress> broadcasts = new ArrayList<>();
        broadcasts.add(InetAddress.getByName("255.255.255.255"));
        LinkProperties props = getSystemService(ConnectivityManager.class).getLinkProperties(network);
        if (props != null) for (LinkAddress address : props.getLinkAddresses()) {
            if (!(address.getAddress() instanceof Inet4Address) || address.getPrefixLength() >= 31) continue;
            byte[] ip = address.getAddress().getAddress();
            int value = java.nio.ByteBuffer.wrap(ip).getInt();
            int mask = address.getPrefixLength() == 0 ? 0 : -1 << (32 - address.getPrefixLength());
            broadcasts.add(InetAddress.getByAddress(java.nio.ByteBuffer.allocate(4).putInt(value | ~mask).array()));
        }
        status("Searching for 60 seconds. Tap the scale awake now; do not press SET.");
        WifiManager.MulticastLock lock = multicast();
        try (Hs5WifiClient client = client(network, null)) {
            List<Hs5WifiClient.Device> found = client.discover(broadcasts, 60);
            main.post(() -> { devices.clear(); devices.addAll(found); refreshDevices(); });
            status(found.isEmpty() ? "No HS5 reply. Check the scale’s Wi-Fi symbol and that both devices use the same network. Guest/client isolation can block discovery."
                    : "Select your scale, then connect your scale profile.");
        } finally { lock.release(); }
    }
    private void findByAddress() {
        EditText field = new EditText(this); field.setSingleLine(true); field.setHint("192.168.100.20");
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Scale IP from your router")
                .setMessage("Use the HS5’s local IPv4 address from the router’s connected-device list.")
                .setView(field).setNegativeButton("Cancel", null)
                .setPositiveButton("Find", (dialog, which) -> {
                    String input = field.getText().toString().trim();
                    runTask(() -> {
                        String[] parts = input.split("\\.", -1);
                        if (parts.length != 4) throw new IOException("Enter a numeric IPv4 address.");
                        byte[] octets = new byte[4];
                        for (int i = 0; i < 4; i++) {
                            if (!parts[i].matches("[0-9]{1,3}")) throw new IOException("Invalid IPv4 address.");
                            int value = Integer.parseInt(parts[i]); if (value > 255) throw new IOException("Invalid IPv4 address.");
                            octets[i] = (byte)value;
                        }
                        InetAddress address = InetAddress.getByAddress(octets);
                        if (!address.isSiteLocalAddress()) throw new IOException("Use the scale’s private local address.");
                        WifiManager.MulticastLock lock = multicast();
                        try (Hs5WifiClient client = client(wifi(), null)) {
                            status("Looking for HS5 at " + input + " for 60 seconds. Tap the scale awake now.");
                            List<Hs5WifiClient.Device> found = client.discover(Collections.singletonList(address), 60);
                            main.post(() -> { devices.clear(); devices.addAll(found); refreshDevices(); });
                            status(found.isEmpty() ? "No HS5 reply from " + input + ". Check its address and Wi-Fi connection."
                                    : "Scale found. Connect your scale profile next.");
                        } finally { lock.release(); }
                    });
                }).show();
    }
    private User selectedUser() {
        int index = userChoice.getSelectedItemPosition();
        if (index < 0 || index >= users.size()) { status("Create and select an openScale user first."); return null; }
        return users.get(index);
    }
    private Hs5WifiClient.Device selectedDevice() {
        int index = deviceChoice.getSelectedItemPosition();
        if (index < 0 || index >= devices.size()) { status("Find your scale first."); return null; }
        return devices.get(index);
    }
    private String binding(Hs5WifiClient.Device device, User user) { return device.mac + ".user." + user.getId(); }
    private void remember(Hs5WifiClient.Device device, User user, int id) {
        preferences.edit().putString("device_mac", device.mac).putString("device_ip", device.ip.getHostAddress())
                .putInt(binding(device, user), id).putInt("auto_user_id", user.getId()).apply();
    }
    private void chooseProfile() {
        User user = selectedUser(); Hs5WifiClient.Device device = selectedDevice(); if (user == null || device == null) return;
        runTask(() -> {
            WifiManager.MulticastLock lock = multicast();
            try (Hs5WifiClient client = client(wifi(), user)) {
                int[] ids = client.connect(device);
                int bound = preferences.getInt(binding(device, user), -1);
                for (int i = 0; i < ids.length; i++) if (bound != -1 && ids[i] == bound) {
                    remember(device, user, bound);
                    status("Connected to scale user " + (i + 1) + " for " + user.getName() + ". Ready to measure or sync."); return;
                }
                List<String> labels = new ArrayList<>(); List<Integer> slots = new ArrayList<>();
                int free = -1;
                for (int i = 0; i < ids.length; i++) {
                    if (ids[i] == -1) { if (free < 0) free = i; }
                    else { labels.add("Use my existing scale user " + (i + 1)); slots.add(i); }
                }
                if (free >= 0) { labels.add(0, "Create a new scale profile for " + user.getName()); slots.add(0, -1); }
                final int firstFree = free;
                main.post(() -> {
                    if (isDestroyed()) return;
                    new AlertDialog.Builder(this).setTitle("Choose your HS5 profile")
                            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                                int slot = slots.get(which);
                                if (slot >= 0) {
                                    remember(device, user, ids[slot]);
                                    status("Linked scale user " + (slot + 1) + " to " + user.getName() + ".");
                                } else addProfile(device, user, firstFree);
                            }).setNegativeButton("Cancel", null).show();
                });
            } finally { lock.release(); }
        });
    }
    private void addProfile(Hs5WifiClient.Device device, User user, int requestedSlot) {
        runTask(() -> {
            int id = preferences.getInt(binding(device, user) + ".pending", -1);
            if (id == -1) { id = new SecureRandom().nextInt(Integer.MAX_VALUE - 1) + 1;
                if (!preferences.edit().putInt(binding(device, user) + ".pending", id).commit()) throw new IOException("Cannot save profile identity"); }
            WifiManager.MulticastLock lock = multicast();
            try (Hs5WifiClient client = client(wifi(), user)) {
                int[] ids = client.connect(device); int slot = -1;
                for (int i = 0; i < ids.length; i++) if (ids[i] == id) { slot = i; break; }
                if (slot < 0) {
                    if (ids[requestedSlot] != -1) throw new IOException("That scale slot is occupied. Reconnect your profile.");
                    LocalDate birth = Instant.ofEpochMilli(user.getBirthDate()).atZone(ZoneId.systemDefault()).toLocalDate();
                    int age = Period.between(birth, LocalDate.now()).getYears();
                    client.addUser(requestedSlot, id, age, Math.round(user.getHeightCm()), user.getGender() == GenderType.MALE ? 0 : 1);
                }
                remember(device, user, id);
                status("Profile linked. Weigh yourself, then tap Sync stored measurements while the scale is awake.");
            } finally { lock.release(); }
        });
    }
    private void collect(boolean history) {
        User user = selectedUser(); Hs5WifiClient.Device device = selectedDevice(); if (user == null || device == null) return;
        int id = preferences.getInt(binding(device, user), -1);
        if (id == -1) { status("Connect your scale profile first."); return; }
        runTask(() -> {
            WifiManager.MulticastLock lock = multicast();
            try (Hs5WifiClient client = client(wifi(), user)) {
                int[] ids = client.connect(device); int slot = -1;
                for (int i = 0; i < ids.length; i++) if (ids[i] == id) { slot = i; break; }
                if (slot < 0) throw new IOException("Linked user is no longer on the scale. Reconnect your profile.");
                if (history) client.history(slot, id); else client.measure(slot, id);
            } finally { lock.release(); }
        });
    }
    @Override protected void onDestroy() {
        canceled = true; Hs5WifiClient client = active; if (client != null) client.close();
        worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
