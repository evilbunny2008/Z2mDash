# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn io.netty.channel.epoll.Epoll
-dontwarn io.netty.channel.epoll.EpollEventLoopGroup
-dontwarn io.netty.channel.epoll.EpollSocketChannel
-dontwarn io.netty.handler.proxy.HttpProxyHandler
-dontwarn io.netty.handler.proxy.ProxyHandler
-dontwarn io.netty.handler.proxy.Socks4ProxyHandler
-dontwarn io.netty.handler.proxy.Socks5ProxyHandler
-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.bouncycastle.asn1.pkcs.PrivateKeyInfo
-dontwarn org.bouncycastle.openssl.PEMDecryptorProvider
-dontwarn org.bouncycastle.openssl.PEMEncryptedKeyPair
-dontwarn org.bouncycastle.openssl.PEMKeyPair
-dontwarn org.bouncycastle.openssl.PEMParser
-dontwarn org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
-dontwarn org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
-dontwarn org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
-dontwarn org.bouncycastle.operator.InputDecryptorProvider
-dontwarn org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
-dontwarn org.conscrypt.BufferAllocator
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.HandshakeListener
-dontwarn org.eclipse.jetty.alpn.ALPN$ClientProvider
-dontwarn org.eclipse.jetty.alpn.ALPN$Provider
-dontwarn org.eclipse.jetty.alpn.ALPN$ServerProvider
-dontwarn org.eclipse.jetty.alpn.ALPN
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ClientProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$Provider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego
-dontwarn org.slf4j.ILoggerFactory
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
-dontwarn org.slf4j.Marker
-dontwarn org.slf4j.helpers.FormattingTuple
-dontwarn org.slf4j.helpers.MessageFormatter
-dontwarn org.slf4j.helpers.NOPLoggerFactory
-dontwarn org.slf4j.spi.LocationAwareLogger
# Optional Reactor/Netty integration hook this app doesn't use or depend on
# directly - referenced only via a META-INF/services entry that R8 flags
# during minification even though the file itself is excluded from the
# final package (that packaging.resources.excludes rule only applies to the
# final packaging step, after R8 has already processed the merged resources).
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# Netty's ResourceLeakDetector.addExclusions() self-registers these exact methods by
# their literal source name via reflection in each class's <clinit> - renaming them
# makes that lookup throw IllegalArgumentException, crashing with ExceptionInInitializerError
# the moment the MQTT/Netty transport is first used (e.g. on connect). Members only
# need their names kept, not the whole class - they're already reachable normally.
-keepclassmembernames class io.netty.buffer.AbstractByteBufAllocator {
    *** toLeakAwareBuffer(...);
}
-keepclassmembernames class io.netty.buffer.AdvancedLeakAwareByteBuf {
    *** touch(...);
    *** recordLeakNonRefCountingOperation(...);
}
-keepclassmembernames class io.netty.util.ReferenceCountUtil {
    *** touch(...);
}

# Netty's MessageToMessageEncoder/-Decoder and friends resolve their generic type parameter
# (e.g. <I>) at runtime via TypeParameterMatcher, which walks the real class hierarchy's
# generic signatures. R8's vertical class merging collapses concrete subclasses like
# HttpRequestEncoder into their caller (observed as "R8$$REMOVED$$CLASS$$..." in the mapping
# file), destroying that hierarchy and making the walk fail with
# "IllegalStateException: unknown type parameter 'I'" the moment a WebSocket/HTTP-upgrade MQTT
# connection is attempted (HiveMQ's own MQTT codec doesn't use Netty's generic base classes, so
# plain TCP/SSL connections aren't at risk - only WS/WSS, which goes through Netty's own HTTP
# upgrade handshake). "-optimizations !class/merging/*" is NOT honoured by this R8 version (still
# merged with it present) - a keep on the whole package is what actually prevents it. Renaming
# and removal of genuinely-unused members are still allowed, only merging is blocked.
-keep,allowobfuscation,allowshrinking class io.netty.handler.codec.** { *; }