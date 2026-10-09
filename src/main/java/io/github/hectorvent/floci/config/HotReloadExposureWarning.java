package io.github.hectorvent.floci.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Warns when enabled hot reload lacks the approved directories required to accept requests. */
@ApplicationScoped
public class HotReloadExposureWarning {

    private static final Logger LOG = Logger.getLogger(HotReloadExposureWarning.class);

    private final EmulatorConfig config;

    @Inject
    public HotReloadExposureWarning(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (hotReloadNeedsAllowedPaths(config)) {
            LOG.warn("Lambda hot-reload is enabled without FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ALLOWED_PATHS: "
                    + "requests are rejected until approved code directories are configured");
        }
    }

    static boolean hotReloadNeedsAllowedPaths(EmulatorConfig config) {
        EmulatorConfig.LambdaServiceConfig.HotReload hotReload = config.services().lambda().hotReload();
        return hotReload.enabled() && hotReload.allowedPaths().isEmpty();
    }
}
