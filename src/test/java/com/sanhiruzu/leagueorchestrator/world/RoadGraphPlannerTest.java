package com.sanhiruzu.leagueorchestrator.world;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the campaign road-graph shape (which town links to which) against
 * a synthetic town layout. No ServerLevel, SavedData, or running world is
 * involved -- {@link RoadGraphPlanner} is a pure function of positions.
 */
class RoadGraphPlannerTest {
    @Test
    void connectsEveryTownWithExactlyNMinusOneEdges() {
        Map<String, BlockPos> positions = fourTownLayout();

        List<CampaignRoute> routes = RoadGraphPlanner.minimumSpanningTree(positions);

        assertEquals(positions.size() - 1, routes.size(), "a spanning tree over n towns has exactly n-1 edges");
        Set<String> touched = routes.stream()
                .flatMap(route -> Stream.of(route.fromTownKey(), route.toTownKey()))
                .collect(Collectors.toSet());
        assertEquals(positions.keySet(), touched, "every town must be reachable, none left isolated");
    }

    @Test
    void picksTheClosestPairFirstAndBranchesWhereThatIsCheapest() {
        List<CampaignRoute> routes = RoadGraphPlanner.minimumSpanningTree(fourTownLayout());

        // "b" ends up as the hub: it is the closest town to both "a" and "c",
        // and also the closest connected town to "d" once "a"/"c" are
        // attached -- one town with several branches, not a single chain.
        Set<String> edgeIds = routes.stream().map(CampaignRoute::id).collect(Collectors.toSet());
        assertEquals(Set.of("town/a--b", "town/b--c", "town/b--d"), edgeIds);
    }

    @Test
    void isDeterministicForTheSameInput() {
        Map<String, BlockPos> positions = Map.of(
                "north", new BlockPos(0, 64, -500),
                "south", new BlockPos(0, 64, 500),
                "east", new BlockPos(500, 64, 0),
                "west", new BlockPos(-500, 64, 0),
                "center", new BlockPos(10, 64, -10));

        List<String> first = idsOf(RoadGraphPlanner.minimumSpanningTree(positions));
        List<String> second = idsOf(RoadGraphPlanner.minimumSpanningTree(positions));

        assertEquals(first, second);
    }

    @Test
    void aSingleTownProducesNoEdges() {
        assertTrue(RoadGraphPlanner.minimumSpanningTree(Map.of("only", BlockPos.ZERO)).isEmpty());
    }

    private static Map<String, BlockPos> fourTownLayout() {
        return Map.of(
                "a", new BlockPos(0, 64, 0),
                "b", new BlockPos(100, 64, 0),
                "c", new BlockPos(200, 64, 0),
                "d", new BlockPos(100, 64, 150));
    }

    private static List<String> idsOf(List<CampaignRoute> routes) {
        return routes.stream().map(CampaignRoute::id).toList();
    }
}
