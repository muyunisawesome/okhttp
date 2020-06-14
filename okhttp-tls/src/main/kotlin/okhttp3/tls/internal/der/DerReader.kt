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

import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ForwardingSource
import okio.IOException
import okio.Source
import okio.buffer
import java.math.BigInteger
import java.net.ProtocolException

/**
 * ASN.1: encoding
 * DER: distinguished rules to constrain ASN.1
 * BER: basic rules to constrain ASN.1
 *
 * Distinguished Encoding Rules (DER) as specified by X.690.
 *
 * https://www.itu.int/rec/T-REC-X.690
 * https://letsencrypt.org/docs/a-warm-welcome-to-asn1-and-der/
 *
 * Abstract Syntax Notation One (ASN.1)
 */
internal class DerReader(source: Source) {
  private val countingSource: CountingSource = CountingSource(source)
  private val source: BufferedSource = countingSource.buffer()

  /** Total bytes read thus far. */
  private val byteCount: Long
    get() = countingSource.bytesRead - source.buffer.size

  /** How many bytes to read before [next] should return false, or -1L for no limit. */
  var limit: Long = -1L

  private val bytesLeft: Long
    get() = if (limit == -1L) -1L else limit - byteCount

  /** Bits 7,8. 00=Universal, 01=Application, 10=Context-Specific, 11=Private */
  private var tagClass: Int = -1

  private var tag: Long = -1L

  /** Bit 6. 0=Primitive, 1=Constructed */
  private var constructed: Boolean = false

  private var length: Long = -1L

  /**
   * The handler accepts a tag class, tag, constructed bit, and length.
   *
   * Returns true if a value was read. Otherwise the stream was exhausted.
   */
  inline fun readValue(block: (Int, Long, Boolean, Long) -> Unit): Boolean {
    if (!next()) return false

    val pushedLimit = limit
    val pushedTagClass = tagClass
    val pushedTag = tag
    val pushedLength = length
    val pushedConstructed = constructed

    limit = if (length != -1L) byteCount + length else -1L
    tagClass = -1
    tag = -1L
    length = -1L
    try {
      block(pushedTagClass, pushedTag, pushedConstructed, pushedLength)
      return true
    } finally {
      limit = pushedLimit
      tagClass = pushedTagClass
      tag = pushedTag
      length = pushedLength
      constructed = pushedConstructed
    }
  }

  /** Returns the number of values read. */
  fun readAll(block: (Int, Long, Boolean, Long) -> Unit): Int {
    var result = 0
    while (readValue(block)) {
      result++
    }
    return result
  }

  /** Require no more values in the current scope. */
  fun requireNoMoreValues() {
    // No more values expected.
    readValue { tagClass, tag, constructed, length ->
      throw IOException("unexpected $tagClass/$tag")
    }
  }

  /**
   * Returns true if a tag was read and there's a value to process.
   *
   * This returns false if:
   *
   *  * The stream is exhausted.
   *  * We've read all of the bytes of an object whose length is known.
   *  * We've reached the [TAG_END_OF_CONTENTS] of an object whose length is unknown.
   */
  private fun next(): Boolean {
    if (byteCount == limit) {
      return false // We've hit a local limit.
    }

    if (limit == -1L && source.exhausted()) {
      return false // We've exhausted the source stream.
    }

    // Read the tag.
    val tagAndClass = source.readByte().toInt() and 0xff
    tagClass = tagAndClass and 0b1100_0000
    constructed = (tagAndClass and 0b0010_0000) == 0b0010_0000
    val tag0 = tagAndClass and 0b0001_1111 // TODO: confirm 31 is right here, breaks SET OF
    if (tag0 == 0b0001_1111) {
      var tagBits = 0L
      while (true) {
        val tagN = source.readByte().toInt() and 0xff
        tagBits += (tagN and 0b0111_1111)
        if (tagN and 0b1000_0000 == 0b1000_0000) break
        tagBits = tagBits shl 7
      }
      tag = tagBits
    } else {
      tag = tag0.toLong()
    }

    // Read the length.
    val length0 = source.readByte().toInt() and 0xff
    if (length0 == 0b1000_0000) {
      // Indefinite length.
      length = -1L
    } else if (length0 and 0b1000_0000 == 0b1000_0000) {
      // Length specified over multiple bytes.
      val lengthBytes = length0 and 0b0111_1111
      var lengthBits = source.readByte()
          .toLong() and 0xff
      for (i in 1 until lengthBytes) {
        lengthBits = lengthBits shl 8
        lengthBits += source.readByte()
            .toInt() and 0xff
      }
      length = lengthBits
    } else {
      // Length is 127 or fewer bytes.
      length = (length0 and 0b0111_1111).toLong()
    }

    return tagClass != TAG_CLASS_UNIVERSAL || tag != TAG_END_OF_CONTENTS
  }

  fun readBoolean(): Boolean {
    // TODO(jwilson): is the tag always 1 ?
    if (bytesLeft != 1L) throw ProtocolException("unexpected length: $bytesLeft")
    return source.readByte().toInt() != 0
  }

  fun readBigInteger(): BigInteger {
    if (bytesLeft == 0L) throw ProtocolException("unexpected length: $bytesLeft")

    val byteArray = source.readByteArray(bytesLeft)
    return BigInteger(byteArray)
  }

  fun readLong(): Long {
    if (bytesLeft !in 1..8) throw ProtocolException("unexpected length: $bytesLeft")

    var result = source.readByte().toLong() // No "and 0xff" because this is a signed value.
    while (byteCount < limit) {
      result = result shl 8
      result += source.readByte().toInt() and 0xff
    }
    return result
  }

  fun readBitString(): BitString {
    val buffer = Buffer()
    val unusedBitCount = readBitString(buffer)
    return BitString(buffer.readByteString(), unusedBitCount)
  }

  fun readBitString(sink: Buffer): Int {
    if (bytesLeft != -1L) {
      val unusedBitCount = source.readByte().toInt() and 0xff
      source.read(sink, bytesLeft)
      return unusedBitCount
    } else {
      var unusedBitCount = 0
      readAll { tagClass, tag, constructed, length ->
        unusedBitCount = readBitString(sink)
      }
      return unusedBitCount
    }
  }

  fun readOctetString(): ByteString {
    val buffer = Buffer()
    readOctetString(buffer)
    return buffer.readByteString()
  }

  fun readOctetString(sink: Buffer) {
    if (bytesLeft != -1L && !constructed) {
      source.read(sink, bytesLeft)
    } else {
      readAll { tagClass, tag, constructed, length ->
        readOctetString(sink)
      }
    }
  }

  fun readUtf8String(): String {
    val buffer = Buffer()
    readUtf8String(buffer)
    return buffer.readUtf8()
  }

  fun readUtf8String(sink: Buffer) {
    if (bytesLeft != -1L && !constructed) {
      source.read(sink, bytesLeft)
    } else {
      readAll { tagClass, tag, constructed, length ->
        readUtf8String(sink)
      }
    }
  }

  fun readObjectIdentifier(): String {
    val result = Buffer()
    val dot = '.'.toByte().toInt()
    when (val xy = readSubidentifier()) {
      in 0L until 40L -> {
        result.writeDecimalLong(0)
        result.writeByte(dot)
        result.writeDecimalLong(xy)
      }
      in 40L until 80L -> {
        result.writeDecimalLong(1)
        result.writeByte(dot)
        result.writeDecimalLong(xy - 40L)
      }
      else -> {
        result.writeDecimalLong(2)
        result.writeByte(dot)
        result.writeDecimalLong(xy - 80L)
      }
    }
    while (byteCount < limit) {
      result.writeByte(dot)
      result.writeDecimalLong(readSubidentifier())
    }
    return result.readUtf8()
  }

  fun readRelativeObjectIdentifier(): String {
    val result = Buffer()
    val dot = '.'.toByte().toInt()
    while (byteCount < limit) {
      if (result.size > 0) {
        result.writeByte(dot)
      }
      result.writeDecimalLong(readSubidentifier())
    }
    return result.readUtf8()
  }

  private fun readSubidentifier(): Long {
    var result = 0L
    while (true) {
      val byteN = source.readByte().toLong() and 0xff
      if (byteN and 0b1000_0000L == 0b1000_0000L) {
        result = (result + (byteN and 0b0111_1111)) shl 7
      } else {
        return result + byteN
      }
    }
  }

  /** A source that keeps track of how many bytes it's consumed. */
  private class CountingSource(source: Source) : ForwardingSource(source) {
    var bytesRead = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
      val result = delegate.read(sink, byteCount)
      if (result == -1L) return -1L
      bytesRead += result
      return result
    }
  }

  companion object {
    /** Bits 7,8. 00=Universal, 01=Application, 10=Context-Specific, 11=Private */
    val TAG_CLASS_UNIVERSAL = 0b0000_0000
    val TAG_CLASS_APPLICATION = 0b0100_0000
    val TAG_CLASS_CONTEXT_SPECIFIC = 0b1000_0000
    val TAG_CLASS_PRIVATE = 0b1100_0000

    val TAG_END_OF_CONTENTS = 0L
    val TAG_BOOLEAN = 1L
    val TAG_INTEGER = 2L
    val TAG_BIT_STRING = 3L
    val TAG_OCTET_STRING = 4L
    val TAG_NULL = 5L
    val TAG_OBJECT_IDENTIFIER = 6L
    val TAG_UTF8_STRING = 12L
    val TAG_SEQUENCE = 16L
    val TAG_SET = 17L
    val TAG_PRINTABLE_STRING = 19L
    val TAG_IA5_STRING = 22L
    val TAG_UTC_TIME = 23L
    val TAG_GENERALIZED_TIME = 24L
  }
}
