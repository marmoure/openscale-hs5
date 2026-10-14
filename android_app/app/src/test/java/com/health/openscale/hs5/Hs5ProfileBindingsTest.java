/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class Hs5ProfileBindingsTest {
    private static final String MAC = "010203040506";
    @Test public void upgradeUsesTheOnlyExistingLink() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(MAC + ".user.2", 123);
        assertEquals(2, Hs5ProfileBindings.automaticUser(MAC, preferences, Arrays.asList(1, 2)));
    }
    @Test public void unlinkedOrDeletedUsersAreNotChosen() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(MAC + ".user.2", 123);
        preferences.put(MAC + ".user.1.pending", 124);
        assertEquals(-1, Hs5ProfileBindings.automaticUser(MAC, preferences, Arrays.asList(1)));
    }
    @Test public void ambiguousUpgradeRequiresExplicitProfileChoice() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(MAC + ".user.1", 123); preferences.put(MAC + ".user.2", 456);
        assertThrows(IllegalArgumentException.class,
                () -> Hs5ProfileBindings.automaticUser(MAC, preferences, Arrays.asList(1, 2)));
        preferences.put("auto_user_id", 2);
        assertEquals(2, Hs5ProfileBindings.automaticUser(MAC, preferences, Arrays.asList(1, 2)));
    }
    @Test public void staleExplicitSelectionNeverFallsBackToSomeoneElse() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(MAC + ".user.1", 123); preferences.put("auto_user_id", 2);
        assertEquals(-1, Hs5ProfileBindings.automaticUser(MAC, preferences, Arrays.asList(1, 2)));
        assertEquals(-1, Hs5ProfileBindings.automaticUser("060504030201", preferences, Arrays.asList(1)));
    }
}
