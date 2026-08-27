package com.sanhiruzu.leagueorchestrator.provider;

import com.sanhiruzu.leagueorchestrator.data.RouteProfile;

/** Future bridge that renders only League's saved route graph. */
public interface RouteRenderingProvider extends ProviderAdapter {
    boolean supports(RouteProfile profile);
}
