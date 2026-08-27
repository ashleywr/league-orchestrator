package com.sanhiruzu.leagueorchestrator.provider;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.minecraft.server.MinecraftServer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The mandatory server-thread gate for calls into optional providers.
 *
 * <p>Callers may prepare immutable intents on any thread, but must turn them
 * into a {@link ServerAction} without reading Minecraft or another mod. This
 * class never invokes provider code outside {@link #tick(MinecraftServer)}.
 * It uses a due-time heap instead of FIFO requeues, so delayed work cannot
 * starve ready work.</p>
 */
public final class ProviderIntegrationCoordinator {
    private static final int MAX_PENDING = 1_024;
    private static final long FAILURE_BACKOFF_TICKS = 200L;
    private static final long CIRCUIT_BREAK_TICKS = 1_200L;
    private static final int CIRCUIT_BREAK_FAILURES = 3;
    private static final long SERVER_ACTION_BUDGET_NANOS = 5_000_000L;

    private static final Object LOCK = new Object();
    private static final PriorityQueue<ScheduledAction> SCHEDULED = new PriorityQueue<>(
            Comparator.comparingLong(ScheduledAction::notBeforeTick).thenComparingLong(ScheduledAction::sequence));
    private static final Set<String> KEYS = new HashSet<>();
    private static final HashMap<OperationKey, ProviderHealth> HEALTH = new HashMap<>();
    private static long tick;
    private static long sequence;
    private static long session;
    private static long rejectedCapacity;
    private static long completed;
    private static long degraded;

    private ProviderIntegrationCoordinator() {}

    /**
     * Adds a prepared immutable request. A false result means the durable
     * owner must retain the desired state and try again from a later lifecycle
     * event; requests are never silently discarded.
     */
    public static boolean submit(ServerAction action) {
        synchronized (LOCK) {
            if (KEYS.contains(action.key())) return true;
            if (KEYS.size() >= MAX_PENDING) {
                rejectedCapacity++;
                return false;
            }
            KEYS.add(action.key());
            SCHEDULED.add(new ScheduledAction(action, tick, session, sequence++));
            return true;
        }
    }

    /** Called exactly once from the server end-tick callback. */
    public static void tick(MinecraftServer server) {
        if (!server.isSameThread()) {
            LeagueOrchestrator.LOGGER.error("Rejected League provider execution off the server thread.");
            return;
        }
        ScheduledAction scheduled;
        long now;
        synchronized (LOCK) {
            now = ++tick;
            scheduled = SCHEDULED.peek();
            if (scheduled == null || scheduled.notBeforeTick() > now) return;
            SCHEDULED.poll();
            if (scheduled.session() != session) return;
        }

        ServerAction action = scheduled.action();
        OperationKey operation = new OperationKey(action.providerId(), action.operationId());
        ProviderHealth health;
        synchronized (LOCK) {
            health = HEALTH.computeIfAbsent(operation, ignored -> new ProviderHealth());
            if (health.openUntilTick > now) {
                rescheduleLocked(action, health.openUntilTick);
                return;
            }
        }

        ActionResult result;
        long startedAt = System.nanoTime();
        try {
            result = action.execute(server);
        } catch (Throwable throwable) {
            synchronized (LOCK) {
                health.failures++;
                health.openUntilTick = now + (health.failures >= CIRCUIT_BREAK_FAILURES
                        ? CIRCUIT_BREAK_TICKS : FAILURE_BACKOFF_TICKS);
                degraded++;
            }
            try {
                action.onFailure(server, throwable);
            } catch (Throwable callbackFailure) {
                LeagueOrchestrator.LOGGER.error("League provider {} action {} could not record its degraded state.",
                        action.providerId(), action.key(), callbackFailure);
            }
            LeagueOrchestrator.LOGGER.warn("League provider {} operation {} action {} failed; automatic replay is disabled.",
                    action.providerId(), action.operationId(), action.key(), throwable);
            removeKey(action.key());
            return;
        }
        long elapsed = System.nanoTime() - startedAt;
        if (elapsed > SERVER_ACTION_BUDGET_NANOS) {
            LeagueOrchestrator.LOGGER.warn("League provider {} operation {} action {} used {} ms on the server thread.",
                    action.providerId(), action.operationId(), action.key(), elapsed / 1_000_000.0);
        }

        if (result == ActionResult.COMPLETE || result == ActionResult.DROP || result == ActionResult.DEGRADED) {
            synchronized (LOCK) {
                if (result == ActionResult.COMPLETE) completed++;
                if (result == ActionResult.DEGRADED) degraded++;
                if (result == ActionResult.COMPLETE) health.failures = 0;
            }
            removeKey(action.key());
            return;
        }
        synchronized (LOCK) {
            rescheduleLocked(action, now + result.retryDelayTicks());
        }
    }

    public static Status status() {
        synchronized (LOCK) {
            long circuits = HEALTH.values().stream().filter(value -> value.openUntilTick > tick).count();
            return new Status(KEYS.size(), MAX_PENDING, rejectedCapacity, completed, degraded, circuits);
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            SCHEDULED.clear();
            KEYS.clear();
            HEALTH.clear();
            tick = 0L;
            sequence = 0L;
            rejectedCapacity = 0L;
            completed = 0L;
            degraded = 0L;
            session++;
        }
    }

    private static void removeKey(String key) {
        synchronized (LOCK) {
            KEYS.remove(key);
        }
    }

    private static void rescheduleLocked(ServerAction action, long notBeforeTick) {
        SCHEDULED.add(new ScheduledAction(action, notBeforeTick, session, sequence++));
    }

    public interface ServerAction {
        String key();

        String providerId();

        /** A provider-local operation name; circuit breakers are scoped here, not provider-wide. */
        default String operationId() {
            return "default";
        }

        /** Runs on the server thread only. */
        ActionResult execute(MinecraftServer server) throws Exception;

        /**
         * Runs on the server thread after an unexpected error. Implementations
         * must persist a degraded state instead of blindly replaying a possibly
         * side-effecting provider operation.
         */
        default void onFailure(MinecraftServer server, Throwable throwable) {}
    }

    public enum ActionResult {
        COMPLETE(0),
        DROP(0),
        DEGRADED(0),
        RETRY_SHORT(40),
        RETRY_LONG(200);

        private final long retryDelayTicks;

        ActionResult(long retryDelayTicks) {
            this.retryDelayTicks = retryDelayTicks;
        }

        long retryDelayTicks() {
            return retryDelayTicks;
        }
    }

    public record Status(int pending, int capacity, long rejectedCapacity, long completed, long degraded,
                         long openCircuits) {}

    private record ScheduledAction(ServerAction action, long notBeforeTick, long session, long sequence) {}

    private record OperationKey(String providerId, String operationId) {}

    private static final class ProviderHealth {
        private int failures;
        private long openUntilTick;
    }
}
