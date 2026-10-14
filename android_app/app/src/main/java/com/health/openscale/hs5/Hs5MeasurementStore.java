/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import com.health.openscale.core.data.User;
import com.health.openscale.core.database.DatabaseProviderEntryPoint;
import com.health.openscale.core.database.DatabaseRepository;
import dagger.hilt.android.EntryPointAccessors;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.flow.FlowKt;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.List;

/** Shared durable storage for manual and automatic collection. Call from a worker. */
final class Hs5MeasurementStore {
    private static final Object SAVE_LOCK = new Object();
    private final Context context;
    private final DatabaseRepository repository;

    Hs5MeasurementStore(Context context) {
        this.context = context.getApplicationContext();
        repository = EntryPointAccessors.fromApplication(this.context, DatabaseProviderEntryPoint.class).databaseRepository();
    }

    List<User> users() throws InterruptedException {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> FlowKt.first(repository.getAllUsers(), continuation));
    }

    boolean save(User user, Hs5WifiProtocol.Reading reading) throws IOException {
        synchronized (SAVE_LOCK) { return saveLocked(user, reading); }
    }

    private int existing(Uri uri, long timestamp, float weight) throws Exception {
        try (Cursor c = context.getContentResolver().query(uri, new String[]{"_ID", "datetime", "values_json"}, null, null, null)) {
            if (c == null) throw new IOException("Cannot verify openScale storage");
            while (c.moveToNext()) if (c.getLong(1) == timestamp) {
                JSONArray values = new JSONArray(c.getString(2));
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.getJSONObject(i);
                    if ("builtin.weight".equals(value.optString("identity")) && Math.abs(value.getDouble("value") - weight) < 0.05) return c.getInt(0);
                }
                throw new IOException("A different measurement already exists at this timestamp; HS5 record kept in local journal.");
            }
        }
        return -1;
    }
    private boolean saveLocked(User user, Hs5WifiProtocol.Reading reading) throws IOException {
        try {
            JSONObject journal = new JSONObject().put("userId", user.getId()).put("timestamp", reading.timestamp)
                    .put("weightKg", reading.weight).put("bodyFatPercent", reading.fat).put("waterPercent", reading.water)
                    .put("muscleKg", reading.muscleKg).put("boneKg", reading.boneKg)
                    .put("visceralFat", reading.visceralFat).put("dailyCalories", reading.dailyCalories);
            try (FileOutputStream out = context.openFileOutput("hs5-readings.jsonl", Context.MODE_APPEND)) {
                out.write((journal + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.getFD().sync();
            }
            Uri uri = Uri.parse("content://" + context.getPackageName() + ".provider/measurements/" + user.getId());
            if (existing(uri, reading.timestamp, reading.weight) > 0) { return false; }
            ContentValues values = new ContentValues(); values.put("datetime", reading.timestamp); values.put("weight", reading.weight);
            if (reading.fat > 0) values.put("fat", reading.fat);
            if (reading.water > 0) values.put("water", reading.water);
            if (reading.muscleKg > 0) values.put("muscle", reading.muscleKg / reading.weight * 100f);
            JSONArray extra = new JSONArray();
            if (reading.boneKg > 0) extra.put(new JSONObject().put("identity", "builtin.bone").put("value", reading.boneKg));
            if (reading.visceralFat > 0) extra.put(new JSONObject().put("identity", "builtin.visceral_fat").put("value", reading.visceralFat));
            values.put("values_json", extra.toString());
            context.getContentResolver().insert(uri, values);
            int measurementId = existing(uri, reading.timestamp, reading.weight);
            if (measurementId < 0) throw new IOException("openScale did not confirm saving; record kept in local journal.");
            BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                    (scope, continuation) -> repository.recalculateDerivedValuesForMeasurement(measurementId, continuation));
            return true;
        } catch (Exception error) { throw new IOException("Unable to save HS5 reading: " + error.getMessage(), error); }
    }
}
