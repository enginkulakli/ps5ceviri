package expo.modules.ps5translator.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object DeviceDiscoveryProof {
  private val random = SecureRandom()
  private val identity = Regex("[0-9a-f]{32}")
  private val signature = Regex("[0-9a-f]{64}")

  fun validId(value: String) = identity.matches(value)
  fun randomId() = ByteArray(16).also(random::nextBytes).hex()

  fun keyFromCode(value: String): ByteArray? =
    value.replace(" ", "").replace("-", "").lowercase().takeIf(::validId)?.decode()

  fun sign(key: ByteArray, role: String, session: String): String =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
      .doFinal("ps5-discovery-v1|$role|$session".toByteArray(Charsets.UTF_8)).hex()

  fun verify(key: ByteArray, role: String, session: String, proof: String): Boolean =
    signature.matches(proof) && MessageDigest.isEqual(sign(key, role, session).decode(), proof.decode())

  private fun String.decode() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
