package dev.leagueorchestrator.provider;

/** Future bridge for RCT's ordered trainer-series graph. */
public interface RctSeriesProvider extends TrainerProvider {
    boolean supportsSeries(String seriesId);
}
