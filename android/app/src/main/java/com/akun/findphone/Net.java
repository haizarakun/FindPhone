package com.akun.findphone;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;

/** ネットワーク状態の確認(Wi-Fi / VPN)。 */
final class Net {
    private Net() {}

    /** VPNを経由しない素のWi-Fi。無ければ null。 */
    @SuppressWarnings("deprecation")
    static Network wifi(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return n;
            }
        }
        return null;
    }

    /** Wi-FiのIPv4アドレス。未接続なら null。 */
    static String wifiIp(Context c) {
        Network n = wifi(c);
        if (n == null) return null;
        LinkProperties lp = c.getSystemService(ConnectivityManager.class).getLinkProperties(n);
        if (lp == null) return null;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    static boolean vpnActive(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }
}
