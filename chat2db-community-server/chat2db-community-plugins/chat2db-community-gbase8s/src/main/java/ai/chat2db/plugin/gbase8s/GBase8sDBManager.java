package ai.chat2db.plugin.gbase8s;

import ai.chat2db.plugin.generic.GenericDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;

@Slf4j
public class GBase8sDBManager extends GenericDBManager implements IDbManager {

    private static final String URL_PREFIX = "jdbc:gbasedbt-sqli://";
    private static final String SERVER_ATTRIBUTE = "GBASEDBTSERVER";

    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        connectInfo.setUrl(appendServerAttributeIfAbsent(connectInfo.getUrl(), connectInfo.getServiceName()));
        return super.getConnection(connectInfo);
    }

    static String appendServerAttributeIfAbsent(String url, String service) {
        if (StringUtils.isBlank(url) || StringUtils.isBlank(service)
                || !StringUtils.startsWithIgnoreCase(url, URL_PREFIX)) {
            return url;
        }

        int attributesSeparator = findAttributesSeparator(url);
        int querySeparator = url.indexOf('?');
        if (querySeparator >= 0 && (attributesSeparator < 0 || querySeparator < attributesSeparator)) {
            return url;
        }
        String normalizedUrl = normalizeServerAttribute(url, attributesSeparator, service);
        if (normalizedUrl != null) {
            return normalizedUrl;
        }

        // Informix-style URLs start the property list with ':' and separate later properties with ';'.
        String separator;
        if (attributesSeparator == url.length() - 1 || url.endsWith(";")) {
            separator = "";
        } else if (attributesSeparator >= 0) {
            separator = ";";
        } else {
            separator = ":";
        }
        return url + separator + SERVER_ATTRIBUTE + "=" + service;
    }

    private static int findAttributesSeparator(String url) {
        boolean insideIpv6Address = false;
        for (int i = URL_PREFIX.length(); i < url.length(); i++) {
            char current = url.charAt(i);
            if (current == '[') {
                insideIpv6Address = true;
            } else if (current == ']') {
                insideIpv6Address = false;
            } else if (current == '?' && !insideIpv6Address) {
                return -1;
            } else if (current == ':' && !insideIpv6Address
                    && (i == url.length() - 1 || startsAttributeAssignment(url, i + 1))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsAttributeAssignment(String url, int start) {
        if (start >= url.length() || !Character.isLetter(url.charAt(start))) {
            return false;
        }
        for (int i = start + 1; i < url.length(); i++) {
            char current = url.charAt(i);
            if (current == '=') {
                return true;
            }
            if (!Character.isLetterOrDigit(current) && current != '_' && current != '.' && current != '-') {
                return false;
            }
        }
        return false;
    }

    private static String normalizeServerAttribute(String url, int attributesSeparator, String service) {
        if (attributesSeparator < 0 || attributesSeparator == url.length() - 1) {
            return null;
        }
        String[] attributes = url.substring(attributesSeparator + 1).split(";", -1);
        String configuredServer = null;
        boolean serverAttributeFound = false;
        for (String attribute : attributes) {
            int equals = attribute.indexOf('=');
            if (equals > 0 && SERVER_ATTRIBUTE.equalsIgnoreCase(attribute.substring(0, equals))) {
                serverAttributeFound = true;
                if (StringUtils.isNotBlank(attribute.substring(equals + 1))) {
                    configuredServer = attribute.substring(equals + 1);
                }
            }
        }
        if (!serverAttributeFound) {
            return null;
        }

        String effectiveServer = StringUtils.defaultIfBlank(configuredServer, service);
        boolean changed = false;
        for (int i = 0; i < attributes.length; i++) {
            int equals = attributes[i].indexOf('=');
            if (equals > 0
                    && SERVER_ATTRIBUTE.equalsIgnoreCase(attributes[i].substring(0, equals))
                    && StringUtils.isBlank(attributes[i].substring(equals + 1))) {
                attributes[i] = attributes[i].substring(0, equals + 1) + effectiveServer;
                changed = true;
            }
        }
        return changed
                ? url.substring(0, attributesSeparator + 1) + String.join(";", attributes)
                : url;
    }
}
