/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.tls

import okhttp3.tls.internal.der.AlgorithmIdentifier
import okhttp3.tls.internal.der.AnyValue
import okhttp3.tls.internal.der.AttributeTypeAndValue
import okhttp3.tls.internal.der.BitString
import okhttp3.tls.internal.der.CertAdapters
import okhttp3.tls.internal.der.Certificate
import okhttp3.tls.internal.der.DerReader
import okhttp3.tls.internal.der.SubjectPublicKeyInfo
import okhttp3.tls.internal.der.TbsCertificate
import okhttp3.tls.internal.der.Validity
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.math.BigInteger

class DerCertificatesTest {
  @Test
  fun happyPath() {
    val certificateString = """
        |MIIBmjCCAQOgAwIBAgIBATANBgkqhkiG9w0BAQsFADATMREwDwYDVQQDEwhjYXNo
        |LmFwcDAeFw03MDAxMDEwMDAwMDBaFw03MDAxMDEwMDAwMDFaMBMxETAPBgNVBAMT
        |CGNhc2guYXBwMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCApFHhtrLan28q
        |+oMolZuaTfWBA0V5aMIvq32BsloQu6LlvX1wJ4YEoUCjDlPOtpht7XLbUmBnbIzN
        |89XK4UJVM6Sqp3K88Km8z7gMrdrfTom/274wL25fICR+yDEQ5fUVYBmJAKXZF1ao
        |I0mIoEx0xFsQhIJ637v2MxJDupd61wIDAQABMA0GCSqGSIb3DQEBCwUAA4GBADam
        |UVwKh5Ry7es3OxtY3IgQunPUoLc0Gw71gl9Z+7t2FJ5VkcI5gWfutmdxZ2bDXCI8
        |8V0vxo1pHXnbBrnxhS/Z3TBerw8RyQqcaWOdp+pBXyIWmR+jHk9cHZCqQveTIBsY
        |jaA9VEhgdaVhxBsT2qzUNDsXlOzGsliznDfoqETb
        |""".trimMargin()
    val certificateString2 = """
        |-----BEGIN CERTIFICATE-----
        |$certificateString
        |-----END CERTIFICATE-----
        |""".trimMargin()

    val buffer = Buffer()
        .write(certificateString.decodeBase64()!!)
    val derReader = DerReader(buffer)
    val javaCertificate = certificateString2.decodeCertificatePem()
    val okHttpCertificate = CertAdapters.certificate.readWithTagAndLength(derReader)!!

    // assertThat(okHttpCertificate.tbsCertificate.subjectPublicKeyInfo.subjectPublicKey.byteString.hex())
    //    .isEqualTo(javaCertificate.publicKey.encoded.toByteString().hex())
    assertThat(okHttpCertificate.signatureValue.byteString)
        .isEqualTo(javaCertificate.signature.toByteString())

    // TODO(jwilson): fix the tag and tagClass in the expected value for Any.
    assertThat(okHttpCertificate).isEqualTo(
        Certificate(
            tbsCertificate = TbsCertificate(
                version = 131330L,
                serialNumber = BigInteger.ONE,
                signature = AlgorithmIdentifier(
                    algorithm = "1.2.840.113549.1.1.11",
                    parameters = AnyValue(
                        tagClass = 0, tag = 0, constructed = false, length = 0,
                        bytes = ByteString.EMPTY
                    )
                ),
                issuer = listOf(
                    listOf(
                        AttributeTypeAndValue(
                            type = "2.5.4.3",
                            value = AnyValue(
                                tagClass = 0, tag = 0, constructed = false, length = 0,
                                bytes = "cash.app".encodeUtf8()
                            )
                        )
                    )
                ),
                validity = Validity(
                    notBefore = 0L,
                    notAfter = 1000L
                ),
                subject = listOf(
                    listOf(
                        AttributeTypeAndValue(
                            type = "2.5.4.3",
                            value = AnyValue(
                                tagClass = 0,
                                tag = 0,
                                constructed = false,
                                length = 0, bytes = "cash.app".encodeUtf8()
                            )
                        )
                    )
                ),
                subjectPublicKeyInfo = SubjectPublicKeyInfo(
                    algorithm = AlgorithmIdentifier(
                        algorithm = "1.2.840.113549.1.1.1",
                        parameters = AnyValue(
                            tagClass = 0, tag = 0, constructed = false, length = 0,
                            bytes = ByteString.EMPTY
                        )
                    ),
                    subjectPublicKey = BitString(
                        // TODO(jwilson): this doesn't match up with the Java parse.
                        byteString = "3081890281810080a451e1b6b2da9f6f2afa8328959b9a4df58103457968c22fab7d81b25a10bba2e5bd7d70278604a140a30e53ceb6986ded72db5260676c8ccdf3d5cae1425533a4aaa772bcf0a9bccfb80caddadf4e89bfdbbe302f6e5f20247ec83110e5f51560198900a5d91756a8234988a04c74c45b1084827adfbbf6331243ba977ad70203010001".decodeHex(),
                        unusedBitsCount = 0
                    )
                ),
                issuerUniqueID = null,
                subjectUniqueID = null,
                extensions = listOf()
            ),
            signatureAlgorithm = AlgorithmIdentifier(
                algorithm = "1.2.840.113549.1.1.11",
                parameters = AnyValue(
                    tagClass = 0, tag = 0, constructed = false, length = 0,
                    bytes = ByteString.EMPTY
                )
            ),
            signatureValue = BitString(
                byteString = "36a6515c0a879472edeb373b1b58dc8810ba73d4a0b7341b0ef5825f59fbbb76149e5591c2398167eeb667716766c35c223cf15d2fc68d691d79db06b9f1852fd9dd305eaf0f11c90a9c69639da7ea415f2216991fa31e4f5c1d90aa42f793201b188da03d54486075a561c41b13daacd4343b1794ecc6b258b39c37e8a844db".decodeHex(),
                unusedBitsCount = 0
            )
        )
    )
  }
}
