package ai.chat2db.community.jcef.update;

import ai.chat2db.community.updater.v2.telemetry.TelemetryTrigger;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * Parameters the renderer sends with an update check.
 *
 * @param trigger           startup, scheduled or manual; drives {@code data.trigger} in the usage report
 * @param offlineActivation true when the product was activated offline, in which case nothing is reported
 */
public record DesktopUpdateCheckContext(TelemetryTrigger trigger, boolean offlineActivation) {

    private static final DesktopUpdateCheckContext DEFAULT =
        new DesktopUpdateCheckContext(TelemetryTrigger.UNKNOWN, false);

    public static DesktopUpdateCheckContext parse(String message) {
        if (message == null || message.isBlank()) {
            return DEFAULT;
        }
        try {
            JSONObject request = JSON.parseObject(message);
            if (request == null) {
                return DEFAULT;
            }
            return new DesktopUpdateCheckContext(TelemetryTrigger.fromWire(request.getString("trigger")),
                request.getBooleanValue("offlineActivation"));
        } catch (RuntimeException exception) {
            return DEFAULT;
        }
    }
}
