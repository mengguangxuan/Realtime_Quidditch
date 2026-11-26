package services.users.utils

import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val secureRandom = SecureRandom()

private[users] def normalizeUsername(username: String): String =
  username.trim.toLowerCase(Locale.ROOT)

private[users] def validCredentials(username: String, password: String): Boolean =
  username.matches("[a-z0-9_]{3,24}") && password.length >= 8 && password.length <= 128

private[users] def passwordDigest(password: String, salt: Array[Byte]): Array[Byte] =
  val spec = PBEKeySpec(password.toCharArray, salt, 600000, 256)
  try SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded
  finally spec.clearPassword()

private[users] def randomBytes(size: Int): Array[Byte] =
  val bytes = new Array[Byte](size)
  secureRandom.nextBytes(bytes)
  bytes
