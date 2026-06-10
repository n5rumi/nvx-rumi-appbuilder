package {{AppPackageName}}.{{ServicePackageName}};

import com.neeve.aep.AepEngine;

/**
 * Hands the running {@link AepEngine} to the JAX-RS resource layer so HTTP
 * handlers can inject requests into the engine. Bound into the Jersey HK2
 * context by {@link HttpServer}; implemented by {@link Main}.
 */
public interface AepEngineProvider {
    AepEngine getEngine();
}
