/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import java.util.Collection;
import java.util.Map;

/** Never guess an owner when a scale is linked to multiple openScale profiles. */
final class Hs5ProfileBindings {
    static int automaticUser(String mac, Map<String, ?> settings, Collection<Integer> existingUsers) {
        Object selected = settings.get("auto_user_id");
        if (selected instanceof Integer) {
            int id = (Integer)selected;
            return existingUsers.contains(id) && linked(mac, id, settings) ? id : -1;
        }
        int found = -1;
        for (int id : existingUsers) {
            if (!linked(mac, id, settings)) continue;
            if (found != -1) throw new IllegalArgumentException("Select your user in HS5 measurements and tap Connect my scale profile to enable automatic sync.");
            found = id;
        }
        return found;
    }

    private static boolean linked(String mac, int user, Map<String, ?> settings) {
        Object id = settings.get(mac + ".user." + user);
        return id instanceof Integer && (Integer)id != -1;
    }
}
