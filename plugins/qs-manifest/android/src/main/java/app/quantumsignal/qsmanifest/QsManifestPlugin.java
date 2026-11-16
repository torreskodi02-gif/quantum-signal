package app.quantumsignal.qsmanifest;

import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * No-op plugin class. This plugin exists only so its AndroidManifest.xml
 * (foreground service, receiver, permissions) is merged into the app by
 * Capacitor at build time. It exposes no JS API.
 */
@CapacitorPlugin(name = "QsManifest")
public class QsManifestPlugin extends Plugin {
}
