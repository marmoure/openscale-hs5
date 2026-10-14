/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.health.openscale.hs5;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class Hs5SyncLoopTest {
    private static final class Timer implements Hs5SyncLoop.Scheduler {
        final Queue<Runnable> pending = new ArrayDeque<>();
        long lastDelay;
        public void after(long delay, Runnable action) { lastDelay = delay; pending.add(action); }
        public void cancel() { pending.clear(); }
        void fire() { pending.remove().run(); }
    }
    private static final class Session implements Hs5SyncLoop.Session {
        int runs, cancellations;
        Runnable duringRun = () -> {};
        public void run() { runs++; duringRun.run(); }
        public void cancel() { cancellations++; }
    }
    private static final class Fixture {
        final Timer timer = new Timer();
        final Queue<Runnable> worker = new ArrayDeque<>();
        final Session session = new Session();
        final Hs5SyncLoop loop = new Hs5SyncLoop(timer, worker::add, () -> session, 20_000);
        void poll() { timer.fire(); worker.remove().run(); }
    }
    @Test public void startsImmediatelyAndWaitsBetweenCompletedAttempts() {
        Fixture f = new Fixture();
        f.loop.start(); f.loop.start();
        assertEquals(1, f.timer.pending.size()); assertEquals(0, f.timer.lastDelay);
        f.poll();
        assertEquals(1, f.session.runs); assertEquals(20_000, f.timer.lastDelay);
        assertEquals(1, f.timer.pending.size());
        f.poll(); assertEquals(2, f.session.runs);
    }
    @Test public void leavingBeforeWorkerStartsDoesNotContactScale() {
        Fixture f = new Fixture(); f.loop.start(); f.timer.fire(); f.loop.stop();
        f.worker.remove().run();
        assertEquals(0, f.session.runs); assertTrue(f.timer.pending.isEmpty());
    }
    @Test public void leavingDuringSyncCancelsSocketAndDoesNotRetry() {
        Fixture f = new Fixture(); f.session.duringRun = f.loop::stop;
        f.loop.start(); f.poll();
        assertEquals(1, f.session.cancellations); assertTrue(f.timer.pending.isEmpty());
    }
    @Test public void rapidReopenDiscardsOldQueuedWork() {
        Fixture f = new Fixture(); f.loop.start(); f.timer.fire();
        f.loop.stop(); f.loop.start(); f.timer.fire();
        f.worker.remove().run(); assertEquals(0, f.session.runs);
        f.worker.remove().run(); assertEquals(1, f.session.runs);
        assertEquals(1, f.timer.pending.size());
    }
    @Test public void staleCompletionCannotScheduleOverNewForegroundSession() {
        Fixture f = new Fixture();
        f.session.duringRun = () -> { f.loop.stop(); f.loop.start(); };
        f.loop.start(); f.poll();
        assertEquals(1, f.timer.pending.size()); assertEquals(0, f.timer.lastDelay);
    }
    @Test public void failedAttemptStillAllowsLaterRetry() {
        Fixture f = new Fixture();
        f.session.duringRun = () -> { throw new IllegalStateException("Network changed"); };
        f.loop.start(); assertThrows(IllegalStateException.class, f::poll);
        assertEquals(1, f.timer.pending.size()); assertEquals(20_000, f.timer.lastDelay);
    }
    @Test public void stopRemovesPendingRetryAndReopenChecksImmediately() {
        Fixture f = new Fixture(); f.loop.start(); f.poll(); f.loop.stop();
        assertTrue(f.timer.pending.isEmpty());
        f.loop.start(); assertEquals(0, f.timer.lastDelay); f.poll();
        assertEquals(2, f.session.runs);
    }
}
