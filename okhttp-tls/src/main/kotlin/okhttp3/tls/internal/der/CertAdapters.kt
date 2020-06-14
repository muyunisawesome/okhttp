package okhttp3.tls.internal.der

import okio.ByteString
import java.math.BigInteger

internal object CertAdapters {
  /**
   * Time ::= CHOICE {
   *   utcTime        UTCTime,
   *   generalTime    GeneralizedTime
   * }
   */
  // TODO(jwilson): fix choice support
//  internal val time = DerChoiceAdapter(
//      choices = listOf(
//          DerAdapter.UTC_TIME,
//          DerAdapter.GENERALIZED_TIME
//      )
//  )
  internal val time = DerAdapter.UTC_TIME

  /**
   * Validity ::= SEQUENCE {
   *   notBefore      Time,
   *   notAfter       Time
   * }
   */
  internal val validity = DerSequenceAdapter(
      members = listOf(
          time,
          time
      )
  ) {
    Validity(it[0] as Long, it[1] as Long)
  }

  /**
   * AlgorithmIdentifier ::= SEQUENCE  {
   *   algorithm      OBJECT IDENTIFIER,
   *   parameters     ANY DEFINED BY algorithm OPTIONAL
   * }
   */
  internal val algorithmIdentifier = DerSequenceAdapter(
      members = listOf(
          DerAdapter.OBJECT_IDENTIFIER,
          AnyValue.ADAPTER.copy(
              isOptional = true, defaultValue = null
          )
      )
  ) {
    AlgorithmIdentifier(
        it[0] as String, it[1] as AnyValue?
    )
  }

  /**
   * Extension ::= SEQUENCE  {
   *   extnID      OBJECT IDENTIFIER,
   *   critical    BOOLEAN DEFAULT FALSE,
   *   extnValue   OCTET STRING
   *     -- contains the DER encoding of an ASN.1 value
   *     -- corresponding to the extension type identified
   *     -- by extnID
   * }
   */
  internal val extension = DerSequenceAdapter(
      members = listOf(
          DerAdapter.OBJECT_IDENTIFIER,
          DerAdapter.BOOLEAN.copy(
              isOptional = true, defaultValue = false
          ),
          DerAdapter.OCTET_STRING
      )
  ) {
    Extension(
        it[0] as String, it[1] as Boolean, it[3] as ByteString
    )
  }

  /**
   * AttributeTypeAndValue ::= SEQUENCE {
   *   type     AttributeType,
   *   value    AttributeValue
   * }
   *
   * AttributeType ::= OBJECT IDENTIFIER
   *
   * AttributeValue ::= ANY -- DEFINED BY AttributeType
   */
  internal val attributeTypeAndValue =
    DerSequenceAdapter(
        members = listOf(
            DerAdapter.OBJECT_IDENTIFIER,
            AnyValue.ADAPTER
        )
    ) {
      AttributeTypeAndValue(
          it[0] as String, it[1] as AnyValue
      )
    }

  /**
   * RDNSequence ::= SEQUENCE OF RelativeDistinguishedName
   *
   * RelativeDistinguishedName ::= SET SIZE (1..MAX) OF AttributeTypeAndValue
   */
  internal val rdnSequence = attributeTypeAndValue.asSequenceOf()
      .copy(
          tagClass = DerReader.TAG_CLASS_UNIVERSAL,
          tag = DerReader.TAG_SET
      )
      .asSequenceOf()

  /**
   * Name ::= CHOICE {
   *   -- only one possibility for now --
   *   rdnSequence  RDNSequence
   * }
   */
// TODO(jwilson): fix choice support
//  internal val name = DerChoiceAdapter(
//      choices = listOf(
//          rdnSequence
//      )
//  )
  internal val name = rdnSequence

  /**
   * SubjectPublicKeyInfo ::= SEQUENCE  {
   *   algorithm            AlgorithmIdentifier,
   *   subjectPublicKey     BIT STRING
   * }
   */
  internal val subjectPublicKeyInfo = DerSequenceAdapter(
      members = listOf(
          algorithmIdentifier,
          DerAdapter.BIT_STRING
      )
  ) {
    SubjectPublicKeyInfo(
        it[0] as AlgorithmIdentifier,
        it[1] as BitString
    )
  }

  /**
   * TBSCertificate ::= SEQUENCE  {
   *   version         [0]  EXPLICIT Version DEFAULT v1,
   *   serialNumber         CertificateSerialNumber,
   *   signature            AlgorithmIdentifier,
   *   issuer               Name,
   *   validity             Validity,
   *   subject              Name,
   *   subjectPublicKeyInfo SubjectPublicKeyInfo,
   *   issuerUniqueID  [1]  IMPLICIT UniqueIdentifier OPTIONAL, -- If present, version MUST be v2 or v3
   *   subjectUniqueID [2]  IMPLICIT UniqueIdentifier OPTIONAL, -- If present, version MUST be v2 or v3
   *   extensions      [3]  EXPLICIT Extensions OPTIONAL -- If present, version MUST be v3
   * }
   */
  internal val tbsCertificate = DerSequenceAdapter(
      members = listOf(
          DerAdapter.INTEGER_AS_LONG.copy(
              tagClass = DerReader.TAG_CLASS_CONTEXT_SPECIFIC,
              tag = 0,
              isOptional = true,
              defaultValue = 0 // v1 == 0
          ),
          DerAdapter.INTEGER_AS_BIG_INTEGER,
          algorithmIdentifier,
          name,
          validity,
          name,
          subjectPublicKeyInfo,
          DerAdapter.BIT_STRING.copy(
              tagClass = DerReader.TAG_CLASS_CONTEXT_SPECIFIC,
              tag = 1,
              isOptional = true,
              defaultValue = null
          ),
          DerAdapter.BIT_STRING.copy(
              tagClass = DerReader.TAG_CLASS_CONTEXT_SPECIFIC,
              tag = 2,
              isOptional = true,
              defaultValue = null
          ),
          extension.asSequenceOf()
              .withExplicitBox(
                  tagClass = DerReader.TAG_CLASS_CONTEXT_SPECIFIC,
                  tag = 3
              ).copy(
                  isOptional = true,
                  defaultValue = listOf()
              )
      )
  ) {
    TbsCertificate(
        it[0] as Long,
        it[1] as BigInteger,
        it[2] as AlgorithmIdentifier,
        it[3] as List<List<AttributeTypeAndValue>>,
        it[4] as Validity,
        it[5] as List<List<AttributeTypeAndValue>>,
        it[6] as SubjectPublicKeyInfo,
        it[7] as BitString?,
        it[8] as BitString?,
        it[9] as List<Extension>
    )
  }

  /**
   * Certificate ::= SEQUENCE  {
   *   tbsCertificate       TBSCertificate,
   *   signatureAlgorithm   AlgorithmIdentifier,
   *   signatureValue       BIT STRING
   * }
   */
  internal val certificate = DerSequenceAdapter(
      members = listOf(
          tbsCertificate,
          algorithmIdentifier,
          DerAdapter.BIT_STRING
      )
  ) {
    Certificate(
        it[0] as TbsCertificate,
        it[1] as AlgorithmIdentifier,
        it[2] as BitString
    )
  }
}
