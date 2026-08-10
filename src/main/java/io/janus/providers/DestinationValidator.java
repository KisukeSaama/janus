package io.janus.providers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.Locale;

@Component
public class DestinationValidator {
    private final boolean allowPrivate;
    public DestinationValidator(@Value("${janus.gateway.allow-private-destinations:false}") boolean allowPrivate) { this.allowPrivate=allowPrivate; }
    public URI validate(String value) {
        try {
            var uri=URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !(allowPrivate && "http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException("Provider URL must use HTTPS");
            if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("Provider URL must be an absolute host URL without credentials, query, or fragment");
            if (!allowPrivate) for (var address:InetAddress.getAllByName(uri.getHost())) if (isPrivate(address)) throw new IllegalArgumentException("Provider resolves to a private or local address");
            return uri;
        } catch (UnknownHostException | IllegalArgumentException ex) {
            if (ex instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("Provider host cannot be resolved");
        }
    }
    private boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress()) return true;
        byte[] b=a.getAddress();
        return b.length==16 && ((b[0]&0xff)==0xfc || (b[0]&0xff)==0xfd);
    }
}
