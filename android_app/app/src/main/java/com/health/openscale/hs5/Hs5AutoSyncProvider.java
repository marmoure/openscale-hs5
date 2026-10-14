/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** Registers lifecycle callbacks without starting a service or doing work at boot. */
public final class Hs5AutoSyncProvider extends ContentProvider {
    @Override public boolean onCreate() {
        Application app = (Application)getContext().getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Hs5AutoSync(app));
        return true;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
