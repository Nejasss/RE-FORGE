package org.bimz.reforge.signing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

data class SigningResult(
    val success: Boolean,
    val signedApkPath: String?,
    val signerAlias: String,
    val subjectDn: String,
    val message: String,
    val updateIdentityWarning: String = "Changing the signing certificate prevents installing over an existing app signed with a different key."
)

class ApkSigningEngine(private val context: Context) {

    private val androidKeystoreProvider = "AndroidKeyStore"
    private val localDebugAlias = "reforge_local_debug_signer"

    suspend fun signWithLocalDebugKey(unsignedApk: File, signedOutputApk: File): SigningResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val (privateKey, cert) = getOrCreateAndroidKeystoreIdentity()
                val signerConfig = ApkSigner.SignerConfig.Builder(
                    localDebugAlias,
                    privateKey,
                    listOf(cert)
                ).build()

                val signer = ApkSigner.Builder(listOf(signerConfig))
                    .setInputApk(unsignedApk)
                    .setOutputApk(signedOutputApk)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setCreatedBy("RE FORGE Local Signer")
                    .build()

                signer.sign()
                SigningResult(
                    success = true,
                    signedApkPath = signedOutputApk.absolutePath,
                    signerAlias = localDebugAlias,
                    subjectDn = cert.subjectX500Principal.name,
                    message = "APK signed locally with v1, v2, and v3 signature schemes."
                )
            }.getOrElse { err ->
                SigningResult(
                    success = false,
                    signedApkPath = null,
                    signerAlias = localDebugAlias,
                    subjectDn = "",
                    message = "Signing failed: ${err.message}"
                )
            }
        }

    suspend fun signWithPkcs12Keystore(
        unsignedApk: File,
        signedOutputApk: File,
        p12File: File,
        storePassword: String,
        keyAlias: String,
        keyPassword: String
    ): SigningResult = withContext(Dispatchers.IO) {
        runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(p12File).use { fis ->
                ks.load(fis, storePassword.toCharArray())
            }
            val resolvedAlias = keyAlias.ifBlank { ks.aliases().nextElement() }
            val privateKey = ks.getKey(resolvedAlias, keyPassword.toCharArray()) as PrivateKey
            val chain = ks.getCertificateChain(resolvedAlias).map { it as X509Certificate }

            val signerConfig = ApkSigner.SignerConfig.Builder(
                resolvedAlias,
                privateKey,
                chain
            ).build()

            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedOutputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setCreatedBy("RE FORGE User Keystore Signer")
                .build()
                .sign()

            SigningResult(
                success = true,
                signedApkPath = signedOutputApk.absolutePath,
                signerAlias = resolvedAlias,
                subjectDn = chain.first().subjectX500Principal.name,
                message = "APK signed locally with user PKCS#12 keystore (v1/v2/v3)."
            )
        }.getOrElse { err ->
            SigningResult(
                success = false,
                signedApkPath = null,
                signerAlias = keyAlias,
                subjectDn = "",
                message = "PKCS#12 signing failed: ${err.message}"
            )
        }
    }

    private fun getOrCreateAndroidKeystoreIdentity(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance(androidKeystoreProvider).apply { load(null) }
        if (!ks.containsAlias(localDebugAlias)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, androidKeystoreProvider)
            val spec = KeyGenParameterSpec.Builder(
                localDebugAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=RE FORGE Local Debug, O=org.bimz.reforge"))
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        val entry = ks.getEntry(localDebugAlias, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to (entry.certificate as X509Certificate)
    }
}
