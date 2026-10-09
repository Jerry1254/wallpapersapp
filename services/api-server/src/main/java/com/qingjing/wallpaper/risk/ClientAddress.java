package com.qingjing.wallpaper.risk;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only explicitly trusted reverse proxies may supply X-Real-IP. Never trust client JSON or X-Forwarded-For. */
@Component
public class ClientAddress {
    private final Set<String> proxies;
    public ClientAddress(@Value("${qingjing.risk.trusted-proxy-ips:}") String value) {
        proxies = java.util.Arrays.stream(value.split(",")).filter(s -> !s.isBlank())
                .map(ClientAddress::normalize).collect(Collectors.toUnmodifiableSet());
    }
    public String of(HttpServletRequest request) {
        String peer = normalize(request.getRemoteAddr());
        if (proxies.contains(peer)) {
            String supplied = request.getHeader("X-Real-IP");
            if (supplied != null && !supplied.isBlank()) return normalize(supplied);
        }
        return peer;
    }
    public static String normalize(String value) {
        if (value == null || value.isBlank() || value.length() > 45
                || !value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Invalid IP address");
        try {
            if (!value.contains(":")) {
                String[] octets = value.split("\\.", -1);
                if (octets.length != 4) throw new IllegalArgumentException("Invalid IPv4");
                for (String octet : octets) if (octet.isEmpty() || Integer.parseInt(octet) > 255) throw new IllegalArgumentException("Invalid IPv4");
            }
            return InetAddress.getByName(value).getHostAddress();
        } catch (java.net.UnknownHostException | NumberFormatException error) {
            throw new IllegalArgumentException("Invalid IP address");
        }
    }
}
