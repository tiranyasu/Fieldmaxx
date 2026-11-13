package com.fieldmaxx.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

private const val MEMORY_KIB = 19456
private const val ITERATIONS = 2
private const val PARALLELISM = 1
private const val HASH_BYTES = 32
private val random = SecureRandom()
private val b64 = Base64.getEncoder().withoutPadding()
private val unb64 = Base64.getDecoder()

private fun argon2(pw: String, salt: ByteArray, m: Int, t: Int, p: Int, len: Int): ByteArray {
    val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withSalt(salt).withMemoryAsKB(m).withIterations(t).withParallelism(p).build()
    val out = ByteArray(len)
    Argon2BytesGenerator().apply { init(params) }.generateBytes(pw.toByteArray(Charsets.UTF_8), out)
    return out
}

/** Argon2id, encoded as `$argon2id$v=19$m=..,t=..,p=..$salt$hash` so the parameters can change later. */
fun hashPassword(pw: String): String {
    val salt = ByteArray(16).also(random::nextBytes)
    val hash = argon2(pw, salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_BYTES)
    return "\$argon2id\$v=19\$m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
}

fun verifyPassword(hash: String, pw: String): Boolean = try {
    val parts = hash.split("$")
    require(parts.size == 6 && parts[1] == "argon2id")
    val (m, t, p) = parts[3].split(",").map { it.substringAfter("=").toInt() }
    val expected = unb64.decode(parts[5])
    val actual = argon2(pw, unb64.decode(parts[4]), m, t, p, expected.size)
    MessageDigest.isEqual(expected, actual)
} catch (_: Exception) {
    false
}
