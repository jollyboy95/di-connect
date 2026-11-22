package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AuthenticationImportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = AuthenticationImport.directory(context)

    @Before fun setUp() {
        directory.deleteRecursively()
        DiPlayBootstrap.reset()
    }

    /** Fresh synthetic P-256 pair; no stored accessory identity. */
    private fun pair(): Pair<ByteArray, ByteArray> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keys = generator.generateKeyPair()
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=DiPlay synthetic test only")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.ONE))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(0)))
            setEndDate(Time(Date(4102444800000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keys.public.encoded))
        }.generateTBSCertificate()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.private)
        signer.update(tbs.encoded)
        val certificate = DERSequence(arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signer.sign()))).encoded
        return keys.private.encoded to certificate
    }

    private fun apk(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            for ((name, bytes) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }.toByteArray()

    @Test fun filesInEitherOrderInstallAndLetBootstrapLoad() {
        val (key, certificate) = pair()
        AuthenticationImport.install(context, AuthenticationImport.fromFiles(certificate.copyOf(), key.copyOf()))
        assertTrue(AuthenticationImport.installed(context))
        assertArrayEquals(key, File(directory, "identity.pk8").readBytes())
        assertArrayEquals(certificate, File(directory, "certificate.p7b").readBytes())
        DiPlayBootstrap.ensure(context, MfiTarget.LOCAL)
    }

    @Test fun apkResourcesAreCopiedFromAssets() {
        val (key, certificate) = pair()
        val bytes = apk("assets/offline-mfi/identity.pk8" to key, "assets/offline-mfi/certificate.p7b" to certificate)
        AuthenticationImport.install(context, AuthenticationImport.fromApk(bytes.inputStream()))
        assertArrayEquals(certificate, File(directory, "certificate.p7b").readBytes())
        DiPlayBootstrap.ensure(context, MfiTarget.LOCAL)
    }

    @Test fun apkWithoutResourcesIsRejected() {
        assertThrows(AuthenticationImport.ImportException::class.java) {
            AuthenticationImport.fromApk(apk("assets/other.txt" to byteArrayOf(1)).inputStream())
        }
        assertFalse(AuthenticationImport.installed(context))
    }

    @Test fun twoCertificatesOrTwoKeysAreRejected() {
        val (key, certificate) = pair()
        assertThrows(AuthenticationImport.ImportException::class.java) { AuthenticationImport.fromFiles(certificate, certificate) }
        assertThrows(AuthenticationImport.ImportException::class.java) { AuthenticationImport.fromFiles(key, key) }
    }

    @Test fun mismatchedPairKeepsTheWorkingIdentity() {
        val (key, certificate) = pair()
        AuthenticationImport.install(context, AuthenticationImport.fromFiles(key.copyOf(), certificate.copyOf()))
        val (otherKey, _) = pair()
        assertThrows(AuthenticationImport.ImportException::class.java) {
            AuthenticationImport.install(context, AuthenticationImport.fromFiles(otherKey, certificate.copyOf()))
        }
        assertArrayEquals(key, File(directory, "identity.pk8").readBytes())
        assertFalse(File(context.noBackupFilesDir, "offline-mfi-import").exists())
        DiPlayBootstrap.ensure(context, MfiTarget.LOCAL)
    }
}
