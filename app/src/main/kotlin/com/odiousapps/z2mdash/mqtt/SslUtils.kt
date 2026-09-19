package com.odiousapps.z2mdash.mqtt

import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory

object SslUtils {
    /**
     * Builds a TrustManagerFactory that trusts exactly one certificate, supplied as Base64
     * (from the file chooser in Add/Edit Broker) - lets the app connect to a self-signed
     * broker without disabling certificate validation entirely.
     */
    fun trustManagerFactoryFromCertBase64(certBase64: String): TrustManagerFactory {
        val certBytes = Base64.decode(certBase64, Base64.DEFAULT)
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certBytes))

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("z2mdash-ca", cert)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf
    }
}
