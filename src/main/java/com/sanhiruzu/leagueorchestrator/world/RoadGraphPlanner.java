package com.sanhiruzu.leagueorchestrator.world;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the sparse minimum-distance town-connectivity tree used for a site's
 * road graph. Pure function of town positions: no SavedData, ServerLevel, or
 * other Minecraft world state, so the resulting shape (which town links to
 * which) can be exercised directly in a test instead of a running world.
 */
public final class RoadGraphPlanner {
    private RoadGraphPlanner() {}

    /**
     * Deterministic minimum spanning tree over {@code townPositions}, starting
     * from the lexicographically-first key and always connecting the globally
     * closest (connected, unconnected) pair next. Ties are broken by
     * (fromKey, toKey) order so the same input always yields the same edges.
     */
    public static List<CampaignRoute> minimumSpanningTree(Map<String, BlockPos> townPositions) {
        Map<String, BlockPos> positions = new TreeMap<>(townPositions);
        List<String> keys = List.copyOf(positions.keySet());
        if (keys.size() < 2) return List.of();

        Set<String> connected = new HashSet<>();
        connected.add(keys.getFirst());
        List<CampaignRoute> routes = new ArrayList<>();

        while (connected.size() < keys.size()) {
            Candidate best = null;
            for (String fromKey : connected) {
                for (String toKey : keys) {
                    if (connected.contains(toKey)) continue;
                    double distanceSquared = positions.get(fromKey).distSqr(positions.get(toKey));
                    Candidate candidate = new Candidate(fromKey, toKey, distanceSquared);
                    if (best == null || candidate.compareTo(best) < 0) best = candidate;
                }
            }
            if (best == null) break;
            routes.add(CampaignRoute.between(best.fromKey(), best.toKey()));
            connected.add(best.toKey());
        }
        return List.copyOf(routes);
    }

    private record Candidate(String fromKey, String toKey, double distanceSquared) implements Comparable<Candidate> {
        @Override
        public int compareTo(Candidate other) {
            int distance = Double.compare(distanceSquared, other.distanceSquared);
            if (distance != 0) return distance;
            int from = fromKey.compareTo(other.fromKey);
            return from != 0 ? from : toKey.compareTo(other.toKey);
        }
    }
}
