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
package okhttp3.tls.internal.der

import okio.ByteString
import okio.IOException
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * Reads a DER tag class, tag, length, and value and decodes it as value.
 */
internal abstract class DerAdapter<T>(
  /** The tag class this adapter expects, or -1 to match any tag class. */
  val tagClass: Int,

  /** The tag this adapter expects, or -1 to match any tag. */
  val tag: Long,

  /** True if the default value should be used if this value is absent during decoding. */
  val isOptional: Boolean = false,

  /** The value to return if this value is absent. Undefined unless this is optional. */
  val defaultValue: T? = null
) {
  /**
   * Returns true if this adapter decodes values with [tagClass] and [tag]. Most adapters match
   * exactly one tag class and tag, but ANY adapters match anything and CHOICE adapters match
   * multiple.
   */
  open fun matches(tagClass: Int, tag: Long): Boolean {
    if (tagClass == -1 || tag == -1L) return false

    return (this.tagClass == -1 || tagClass == this.tagClass) &&
        (this.tag == -1L || tag == this.tag)
  }

  open fun readWithTagAndLength(reader: DerReader): T? {
    var result: T? = null
    val handled = reader.readValue { tagClass, tag, constructed, length ->
      if (!matches(tagClass, tag)) {
        throw IOException("expected ${this.tagClass}/${this.tag} but was ${tagClass}/${tag}")
      }

      result = read(reader)
    }

    if (!handled) {
      if (!isOptional) throw IOException("expected $tagClass/$tag not found")
      return defaultValue
    }

    reader.requireNoMoreValues()

    return result
  }

  abstract fun read(reader: DerReader): T

  /**
   * Returns a copy of this adapter with a different tag class, tag, or default.
   *
   * Use this to rewrite the tag class from the default to a custom tag class, such as with IMPLICIT
   * tag types:
   *
   * ```
   * [5] IMPLICIT UTF8String
   * ```
   */
  fun copy(
    tagClass: Int = this.tagClass,
    tag: Long = this.tag,
    isOptional: Boolean = this.isOptional,
    defaultValue: T? = this.defaultValue
  ): DerAdapter<T> = object : DerAdapter<T>(tagClass, tag, isOptional, defaultValue) {
    override fun read(reader: DerReader) = this@DerAdapter.read(reader)
  }

  /**
   * Returns an adapter that expects this value wrapped by another value. Typically this occurs
   * when a value has both a context or application tag and a universal tag.
   *
   * Use this for EXPLICIT tag types:
   *
   * ```
   * [5] EXPLICIT UTF8String
   * ```
   */
  @Suppress("UNCHECKED_CAST") // read() produces a single element of the expected type.
  fun withExplicitBox(tagClass: Int, tag: Long): DerSequenceAdapter<T> {
    return DerSequenceAdapter(
        tagClass = tagClass,
        tag = tag,
        members = listOf(this)
    ) { list ->
      list[0] as T
    }
  }

  /** Returns an adapter that returns a list of values of this type. */
  fun asSequenceOf(): DerAdapter<List<T>> {
    val member = this
    return object : DerAdapter<List<T>>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_SEQUENCE
    ) {
      override fun read(reader: DerReader): List<T> {
        val result = mutableListOf<T>()

        reader.readAll { tagClass, tag, constructed, length ->
          if (!member.matches(tagClass, tag)) {
            throw IOException("expected ${member.tagClass}/${member.tag} but was $tagClass/$tag")
          }
          result += member.read(reader)
        }

        return result
      }
    }
  }

  override fun toString() = "$tagClass/$tag"

  companion object {
    val BOOLEAN = object : DerAdapter<Boolean>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_BOOLEAN
    ) {
      override fun read(reader: DerReader) = reader.readBoolean()
    }

    val INTEGER_AS_LONG = object : DerAdapter<Long>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_INTEGER
    ) {
      override fun read(reader: DerReader) = reader.readLong()
    }

    val INTEGER_AS_BIG_INTEGER = object : DerAdapter<BigInteger>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_INTEGER
    ) {
      override fun read(reader: DerReader) = reader.readBigInteger()
    }

    val UTF8_STRING = object : DerAdapter<String>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_UTF8_STRING
    ) {
      override fun read(reader: DerReader) = reader.readUtf8String()
    }

    /**
     * Permits alphanumerics, spaces, and these:
     *
     * ```
     *   ' () + , - . / : = ?
     * ```
     */
    val PRINTABLE_STRING = object : DerAdapter<String>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_PRINTABLE_STRING
    ) {
      // TODO(jwilson): constrain to printable string characters.
      override fun read(reader: DerReader) = reader.readUtf8String()
    }

    /**
     * Based on International Alphabet No. 5. Note that there are bytes that IA5 and US-ASCII
     * disagree on interpretation.
     */
    val IA5_STRING = object : DerAdapter<String>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_IA5_STRING
    ) {
      // TODO(jwilson): constrain to IA5 characters.
      override fun read(reader: DerReader) = reader.readUtf8String()
    }

    /**
     * A timestamp like "191215190210-0800" for 2019-12-15T19:02:10-08:00. The cutoff of the 2-digit
     * year is 1950-01-01T00:00:00Z.
     */
    val UTC_TIME = object : DerAdapter<Long>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_UTC_TIME
    ) {
      override fun read(reader: DerReader): Long {
        val string = reader.readUtf8String()
        return parseUtcTime(string)
      }
    }

    internal fun parseUtcTime(string: String): Long {
      val utc = TimeZone.getTimeZone("GMT")
      val dateFormat = SimpleDateFormat("yyMMddHHmmssXX").apply {
        timeZone = utc
        set2DigitYearStart(Date(-631152000000L)) // 1950-01-01T00:00:00Z.
      }

      val parsed = dateFormat.parse(string)
      return parsed.time
    }

    /**
     * A timestamp like "20191215190210-0800" for 2019-12-15T19:02:10-08:00. This is the same as
     * [UTC_TIME] with the exception of the 4-digit year.
     */
    val GENERALIZED_TIME = object : DerAdapter<Long>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_GENERALIZED_TIME
    ) {
      override fun read(reader: DerReader): Long {
        val string = reader.readUtf8String()
        return parseGeneralizedTime(
            string
        )
      }
    }

    internal fun parseGeneralizedTime(string: String): Long {
      val utc = TimeZone.getTimeZone("GMT")
      val dateFormat = SimpleDateFormat("yyyyMMddHHmmssXX").apply {
        timeZone = utc
      }

      val parsed = dateFormat.parse(string)
      return parsed.time
    }

    val OBJECT_IDENTIFIER = object : DerAdapter<String>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_OBJECT_IDENTIFIER
    ) {
      override fun read(reader: DerReader): String {
        return reader.readObjectIdentifier()
      }
    }

    val NULL = object : DerAdapter<Unit?>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_NULL
    ) {
      override fun read(reader: DerReader): Unit? {
        return null
      }
    }

    val BIT_STRING = object : DerAdapter<BitString>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_BIT_STRING
    ) {
      override fun read(reader: DerReader): BitString {
        return reader.readBitString()
      }
    }

    val OCTET_STRING = object : DerAdapter<ByteString>(
        tagClass = DerReader.TAG_CLASS_UNIVERSAL,
        tag = DerReader.TAG_OCTET_STRING
    ) {
      override fun read(reader: DerReader): ByteString {
        return reader.readOctetString()
      }
    }
  }
}
