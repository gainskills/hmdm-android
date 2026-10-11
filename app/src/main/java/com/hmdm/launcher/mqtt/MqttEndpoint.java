package com.hmdm.launcher.mqtt;

import java.net.URI;
import java.net.URISyntaxException;

/** Pure endpoint resolution shared by startup, configuration updates and recovery. */
public final class MqttEndpoint {
    private MqttEndpoint() {}

    public static String resolve(String configured, String baseUrl, int fallbackPort, boolean fallbackTls) {
        try {
            boolean explicit = configured != null && !configured.trim().isEmpty();
            URI uri = new URI(explicit ? configured.trim() : baseUrl);
            String scheme = uri.getScheme();
            if (!explicit) {
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    throw new IllegalArgumentException("Invalid HMDM scheme");
                scheme = fallbackTls ? "ssl" : "tcp";
            } else if ("mqtt".equalsIgnoreCase(scheme)) scheme = "tcp";
            else if ("mqtts".equalsIgnoreCase(scheme)) scheme = "ssl";
            else if ("tcp".equalsIgnoreCase(scheme)) scheme = "tcp";
            else if ("ssl".equalsIgnoreCase(scheme)) scheme = "ssl";
            else throw new IllegalArgumentException("Unsupported MQTT scheme");
            String host = uri.getHost();
            int port = explicit ? uri.getPort() : fallbackPort;
            if (host == null && uri.getAuthority() != null) {
                String auth = uri.getAuthority();
                if (auth.indexOf('@') >= 0)
                    throw new IllegalArgumentException("MQTT endpoint must not contain user info");
                if (auth.startsWith("[") && auth.indexOf(']') >= 0) {
                    int endBracket = auth.indexOf(']');
                    int colon = auth.indexOf(':', endBracket);
                    host = auth.substring(0, endBracket + 1);
                    if (colon >= 0 && explicit && port == -1) {
                        try { port = Integer.parseInt(auth.substring(colon + 1)); }
                        catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid MQTT port", e);
                        }
                    }
                } else {
                    int colon = auth.lastIndexOf(':');
                    if (colon >= 0) {
                        host = auth.substring(0, colon);
                        if (explicit && port == -1) {
                            try { port = Integer.parseInt(auth.substring(colon + 1)); }
                            catch (NumberFormatException e) {
                                throw new IllegalArgumentException("Invalid MQTT port", e);
                            }
                        }
                    } else {
                        host = auth;
                    }
                }
            }
            if (port == -1) port = "ssl".equals(scheme) ? 8883 : 1883;
            if (host == null || host.isEmpty() || port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid MQTT host or port");
            if (explicit && (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())))
                throw new IllegalArgumentException("MQTT endpoint must contain only scheme, host and port");
            // URI.getHost preserves IPv6 brackets on supported Android/JVM implementations.
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            return scheme + "://" + host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid MQTT endpoint", e);
        }
    }
}
