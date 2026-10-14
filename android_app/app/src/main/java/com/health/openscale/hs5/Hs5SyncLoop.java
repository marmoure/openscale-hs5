/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import java.util.concurrent.Executor;

/** Foreground-only polling. A canceled session can never schedule another poll. */
final class Hs5SyncLoop {
    interface Scheduler { void after(long milliseconds, Runnable action); void cancel(); }
    interface Session { void run(); void cancel(); }
    interface Factory { Session create(); }

    private final Scheduler scheduler;
    private final Executor worker;
    private final Factory factory;
    private final long interval;
    private long generation;
    private boolean running;
    private Session active;

    Hs5SyncLoop(Scheduler scheduler, Executor worker, Factory factory, long interval) {
        this.scheduler = scheduler; this.worker = worker; this.factory = factory; this.interval = interval;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        schedule(++generation, 0);
    }

    synchronized void stop() {
        running = false;
        generation++;
        scheduler.cancel();
        if (active != null) active.cancel();
        active = null;
    }

    private void schedule(long token, long delay) {
        scheduler.after(delay, () -> worker.execute(() -> poll(token)));
    }

    private void poll(long token) {
        Session session;
        synchronized (this) {
            if (!running || token != generation) return;
            session = factory.create();
            active = session;
        }
        try { session.run(); }
        finally {
            synchronized (this) {
                if (active == session) active = null;
                if (running && token == generation) schedule(token, interval);
            }
        }
    }
}
