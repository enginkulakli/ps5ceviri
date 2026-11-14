package expo.modules.ps5translator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object NetworkUtils {
  /** IPv4 address of the active Wi-Fi (or Ethernet) network, null if none. */
  fun localIpv4(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val candidates = buildList {
      cm.activeNetwork?.let { add(it) }
      @Suppress("DEPRECATION")
      addAll(cm.allNetworks)
    }.distinct()
    for (n in candidates) {
      val caps = cm.getNetworkCapabilities(n) ?: continue
      val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
      if (!lan) continue
      val lp = cm.getLinkProperties(n) ?: continue
      val ip = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
      if (ip != null) return ip.hostAddress
    }
    return null
  }

  fun isOnWifi(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
  }
}
