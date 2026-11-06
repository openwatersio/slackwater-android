package io.openwaters.slackwater.android.core.prediction

enum class StationCapabilities(val continuous: Boolean, val measuredCurrent: Boolean) {
    HARMONIC_TIDE(true, false), SUBORDINATE_TIDE(true, false), FITTED_TIDE(true, false),
    TIDE_PREVIEW(false, false), HARMONIC_CURRENT(true, true), SUBORDINATE_CURRENT(true, true),
    FINAL_CURRENT(true, true), BOUNDED_CURRENT(true, true), PROVISIONAL_CURRENT(true, true),
    ONLINE_CURRENT(false, true), DERIVED_TIMING(true, false);

    fun supports(purpose: PredictionPurpose): Boolean = when (purpose) {
        PredictionPurpose.DISPLAY, PredictionPurpose.PRINT -> true
        PredictionPurpose.WIDGET -> this != TIDE_PREVIEW
        PredictionPurpose.ALERT, PredictionPurpose.CALENDAR -> continuous
        PredictionPurpose.QUERY -> this != TIDE_PREVIEW && this != PROVISIONAL_CURRENT && this != DERIVED_TIMING
    }
}
