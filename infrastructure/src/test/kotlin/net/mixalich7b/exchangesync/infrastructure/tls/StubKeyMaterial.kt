@file:Suppress("DEPRECATION")

package net.mixalich7b.exchangesync.infrastructure.tls

import java.math.BigInteger
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date

internal class StubPrivateKey(
    private val keyAlgorithm: String = "RSA",
) : PrivateKey {
    override fun getAlgorithm(): String = keyAlgorithm

    override fun getFormat(): String = "PKCS#8"

    override fun getEncoded(): ByteArray = byteArrayOf(1)
}

private class StubPublicKey : PublicKey {
    override fun getAlgorithm(): String = "RSA"

    override fun getFormat(): String = "X.509"

    override fun getEncoded(): ByteArray = byteArrayOf(2)
}

internal class StubX509Certificate(
    private val encoded: ByteArray = byteArrayOf(5),
) : X509Certificate() {
    override fun checkValidity() = Unit

    override fun checkValidity(date: Date?) = Unit

    override fun getVersion(): Int = 3

    override fun getSerialNumber(): BigInteger = BigInteger.ONE

    override fun getIssuerDN(): Principal = Principal { "test-issuer" }

    override fun getSubjectDN(): Principal = Principal { "test-subject" }

    override fun getNotBefore(): Date = Date(0)

    override fun getNotAfter(): Date = Date(Long.MAX_VALUE)

    override fun getTBSCertificate(): ByteArray = byteArrayOf(3)

    override fun getSignature(): ByteArray = byteArrayOf(4)

    override fun getSigAlgName(): String = "NONE"

    override fun getSigAlgOID(): String = "0.0"

    override fun getSigAlgParams(): ByteArray = byteArrayOf()

    override fun getIssuerUniqueID(): BooleanArray? = null

    override fun getSubjectUniqueID(): BooleanArray? = null

    override fun getKeyUsage(): BooleanArray? = null

    override fun getBasicConstraints(): Int = -1

    override fun getEncoded(): ByteArray = encoded.copyOf()

    override fun verify(key: PublicKey?) = Unit

    override fun verify(key: PublicKey?, sigProvider: String?) = Unit

    override fun toString(): String = "test-certificate"

    override fun getPublicKey(): PublicKey = StubPublicKey()

    override fun hasUnsupportedCriticalExtension(): Boolean = false

    override fun getCriticalExtensionOIDs(): Set<String>? = null

    override fun getNonCriticalExtensionOIDs(): Set<String>? = null

    override fun getExtensionValue(oid: String?): ByteArray? = null
}
