package app.piko.tor.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Bundle;
import android.os.Process;
import android.system.ErrnoException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.net.ssl.HttpsURLConnection;

/** Every network operation runs as this app's UID, never shell/root/VPN-provider UID. */
public final class ProbeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override public void onStart() {
        JSONObject result = new JSONObject();
        String operation = arguments.getString("operation", "identity");
        long before = android.os.SystemClock.elapsedRealtime();
        try {
            result.put("operation", operation).put("uid", Process.myUid());
            // onCreate().start() races ActivityThread's remaining application setup.
            // Wait for that main-thread work; never bind sockets to a physical network.
            waitForIdleSync();
            result.put("startup_idle_ms", android.os.SystemClock.elapsedRealtime() - before);
            result.put("startup_active_network", getTargetContext()
                    .getSystemService(ConnectivityManager.class).getActiveNetwork() != null);
            if ("identity".equals(operation)) {
                Context context = getTargetContext();
                Signature[] signatures = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo.getApkContentsSigners();
                if (signatures.length != 1) throw new IllegalStateException("Expected one test signer");
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                result.put("package", context.getPackageName()).put("certificate", hex.toString());
            } else if ("network".equals(operation)) {
                Context context = getTargetContext();
                result.put("internet_permission", context.checkSelfPermission(Manifest.permission.INTERNET)
                        == PackageManager.PERMISSION_GRANTED);
                result.put("target_sdk", context.getApplicationInfo().targetSdkVersion);
                ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
                Network active = connectivity.getActiveNetwork();
                result.put("active_network", active != null);
                NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
                if (capabilities != null) {
                    result.put("internet_capability", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                    result.put("validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                    result.put("not_restricted", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED));
                    result.put("vpn_transport", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                    result.put("wifi_transport", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                    result.put("ethernet_transport", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                }
                LinkProperties links = active == null ? null : connectivity.getLinkProperties(active);
                if (links != null) {
                    result.put("interface", links.getInterfaceName());
                    result.put("private_dns_active", links.isPrivateDnsActive());
                    result.put("private_dns_hostname_configured", links.getPrivateDnsServerName() != null);
                    result.put("dns_server_count", links.getDnsServers().size());
                    int default4 = 0, default6 = 0;
                    for (RouteInfo route : links.getRoutes()) {
                        if (route.isDefaultRoute()) {
                            if (route.getDestination().getAddress() instanceof Inet4Address) default4++;
                            if (route.getDestination().getAddress() instanceof Inet6Address) default6++;
                        }
                    }
                    result.put("ipv4_default_routes", default4).put("ipv6_default_routes", default6);
                }
            } else if ("tor".equals(operation)) {
                HttpsURLConnection connection = (HttpsURLConnection) new URL(
                        "https://check.torproject.org/api/ip?piko_nonce=" + java.util.UUID.randomUUID()).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                try {
                    int code = connection.getResponseCode();
                    result.put("http_status", code);
                    if (code != 200) throw new IllegalStateException("Unexpected HTTP status");
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    try (InputStream input = connection.getInputStream()) {
                        byte[] bytes = new byte[1024];
                        int count;
                        while ((count = input.read(bytes)) != -1) {
                            if (body.size() + count > 8192) throw new IllegalStateException("Response too large");
                            body.write(bytes, 0, count);
                        }
                    }
                    // Deliberately retain no source/exit IP or response body in evidence.
                    JSONObject response = new JSONObject(body.toString("UTF-8"));
                    result.put("is_tor", response.getBoolean("IsTor"));
                } finally { connection.disconnect(); }
            } else if ("dns".equals(operation)) {
                int ipv4 = 0, ipv6 = 0;
                for (InetAddress address : InetAddress.getAllByName("check.torproject.org")) {
                    if (address instanceof Inet4Address) ipv4++;
                    if (address instanceof Inet6Address) ipv6++;
                }
                if (ipv4 + ipv6 == 0) throw new IllegalStateException("Empty DNS answer");
                result.put("ipv4_answers", ipv4).put("ipv6_answers", ipv6);
            } else if ("tcp4".equals(operation) || "tcp6".equals(operation)) {
                InetAddress chosen = null;
                String literal = arguments.getString("address", "");
                if (!literal.isEmpty() && !literal.matches("[0-9a-fA-F:.]+")) {
                    throw new IllegalArgumentException("Only numeric control addresses are accepted");
                }
                InetAddress[] addresses = literal.isEmpty()
                        ? InetAddress.getAllByName("check.torproject.org")
                        : new InetAddress[]{InetAddress.getByName(literal)};
                for (InetAddress address : addresses) {
                    if (("tcp4".equals(operation) && address instanceof Inet4Address)
                            || ("tcp6".equals(operation) && address instanceof Inet6Address)) {
                        chosen = address;
                        break;
                    }
                }
                if (chosen == null) throw new IllegalStateException("Address family unavailable");
                // The host keeps this controlled test destination only in memory, not artifacts.
                result.put("control_address", chosen.getHostAddress());
                try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(chosen, 443), 10000); }
            } else if ("udp".equals(operation) || "local_tcp".equals(operation)) {
                String token = arguments.getString("token", "");
                if (!token.matches("piko-[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid probe token");
                byte[] payload = token.getBytes(StandardCharsets.US_ASCII);
                InetAddress host = InetAddress.getByName("10.0.2.2");
                if ("udp".equals(operation)) {
                    try (DatagramSocket socket = new DatagramSocket()) {
                        socket.setSoTimeout(4000);
                        socket.connect(host, 18081);
                        socket.send(new DatagramPacket(payload, payload.length));
                        byte[] reply = new byte[128];
                        DatagramPacket packet = new DatagramPacket(reply, reply.length);
                        socket.receive(packet);
                        String answer = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.US_ASCII);
                        if (!token.equals(answer)) throw new IllegalStateException("Wrong echo");
                    }
                } else {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(host, 18080), 4000);
                        socket.setSoTimeout(4000);
                        socket.getOutputStream().write(payload);
                        ByteArrayOutputStream answer = new ByteArrayOutputStream();
                        while (answer.size() < payload.length) {
                            int value = socket.getInputStream().read();
                            if (value == -1) break;
                            answer.write(value);
                        }
                        if (!token.equals(answer.toString("US-ASCII"))) throw new IllegalStateException("Wrong echo");
                    }
                }
            } else throw new IllegalArgumentException("Unknown operation");
            result.put("success", true);
        } catch (Exception error) {
            try {
                result.put("success", false).put("error_type", error.getClass().getSimpleName());
                JSONArray causes = new JSONArray();
                Throwable cause = error;
                for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
                    JSONObject detail = new JSONObject().put("type", cause.getClass().getSimpleName());
                    if (cause instanceof ErrnoException) detail.put("errno", ((ErrnoException) cause).errno);
                    String message = cause.getMessage();
                    if (message != null) {
                        message = message.replaceAll("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b", "<IPv4>")
                                .replaceAll("(?i)(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.%]*", "<IPv6>")
                                .replaceAll("piko-[a-f0-9]{32}", "<probe-token>");
                        detail.put("message", message.substring(0, Math.min(240, message.length())));
                    }
                    causes.put(detail);
                }
                result.put("error_causes", causes);
            }
            catch (Exception ignored) { }
        }
        try { result.put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - before); }
        catch (Exception ignored) { }
        Bundle output = new Bundle();
        output.putString("result", result.toString());
        finish(Activity.RESULT_OK, output);
    }
}
