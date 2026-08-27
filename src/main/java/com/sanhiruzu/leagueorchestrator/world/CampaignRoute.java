package com.sanhiruzu.leagueorchestrator.world;

import net.minecraft.nbt.CompoundTag;

/** A saved, undirected edge selected by League Orchestrator's campaign plan. */
record CampaignRoute(String id, String fromTownKey, String toTownKey) {
    static CampaignRoute between(String firstTownKey, String secondTownKey) {
        if (firstTownKey.compareTo(secondTownKey) <= 0) {
            return new CampaignRoute("town/" + firstTownKey + "--" + secondTownKey, firstTownKey, secondTownKey);
        }
        return new CampaignRoute("town/" + secondTownKey + "--" + firstTownKey, secondTownKey, firstTownKey);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("from", fromTownKey);
        tag.putString("to", toTownKey);
        return tag;
    }

    static CampaignRoute load(CompoundTag tag) {
        return new CampaignRoute(tag.getString("id"), tag.getString("from"), tag.getString("to"));
    }
}
