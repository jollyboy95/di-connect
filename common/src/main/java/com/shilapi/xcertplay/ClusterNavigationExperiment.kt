package com.shilapi.xcertplay

/**
 * Feature flag for the optional independent cluster navigation renderer.
 *
 * The Horizon-Maps architecture study looked at a Mapbox-style 3D cluster map (camera framing,
 * 3D buildings, vehicle locator, lane guidance, GPS smoothing, offline tiles) as inspiration for
 * a Di-Connect cluster navigation mode. Horizon-Maps ships no licence file, so no code has been
 * copied from it, and the Mapbox SDK additionally requires a token and licence this project does
 * not hold; the renderer therefore stays an original, independent implementation.
 *
 * The flag is deliberately off. Any future opt-in renderer must stay behind it until the cluster
 * hardware (GWM H7 display routing, capability detection and configurable mappings) has been
 * validated on real vehicles, and must not change the Android Auto / CarPlay projection engines.
 */
internal object ClusterNavigationExperiment {
    @Volatile
    private var independentNavigationEnabled = false

    /** Off by default; flip only after on-cluster 3D navigation validation. */
    fun enabled(): Boolean = independentNavigationEnabled

    /** Test seam. Production never calls this. */
    fun setEnabled(value: Boolean) {
        independentNavigationEnabled = value
    }
}
