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

import okhttp3.tls.internal.der.BitString
import okhttp3.tls.internal.der.DerReader
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DerReaderTest {
  @Test fun `tag and length`() {
    val buffer = Buffer()
        .writeByte(0b00011110)
        .writeByte(0b10000001)
        .writeByte(0b11001001)

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_UNIVERSAL)
      assertThat(tag).isEqualTo(30)
      assertThat(constructed).isFalse()
      assertThat(length).isEqualTo(201)
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `primitive bit string`() {
    val buffer = Buffer()
        .write("0307040A3B5F291CD0".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(3L)
      assertThat(derReader.readBitString()).isEqualTo(BitString("0A3B5F291CD0".decodeHex(), 4))
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `constructed bit string`() {
    val buffer = Buffer()
        .write("2380".decodeHex())
        .write("0303000A3B".decodeHex())
        .write("0305045F291CD0".decodeHex())
        .write("0000".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(3L)
      assertThat(derReader.readBitString()).isEqualTo(BitString("0A3B5F291CD0".decodeHex(), 4))
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun sequence() {
    val buffer = Buffer()
        .write("300A".decodeHex())
        .write("1505".decodeHex())
        .write("Smith".encodeUtf8())
        .write("01".decodeHex())
        .write("01".decodeHex())
        .write("FF".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(16L)

      assertThat(derReader.readValue { tagClass, tag, constructed, length ->
        assertThat(tag).isEqualTo(21L)
        assertThat(derReader.readOctetString()).isEqualTo("Smith".encodeUtf8())
      }).isTrue()

      assertThat(derReader.readValue { tagClass, tag, constructed, length ->
        assertThat(tag).isEqualTo(1L)
        assertThat(derReader.readBoolean()).isTrue()
      }).isTrue()

      derReader.requireNoMoreValues()
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `primitive string`() {
    val buffer = Buffer()
        .write("1A054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(26L)
      assertThat(constructed).isFalse()
      assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_UNIVERSAL)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `constructed string`() {
    val buffer = Buffer()
        .write("3A0904034A6F6E04026573".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(26L)
      assertThat(constructed).isTrue()
      assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_UNIVERSAL)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    val buffer = Buffer()
        .write("43054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(3L)
      assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_APPLICATION)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `tagged implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type3 ::= [2] Type2
    val buffer = Buffer()
        .write("A20743054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(2L)
      assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_CONTEXT_SPECIFIC)
      assertThat(length).isEqualTo(7L)

      assertThat(derReader.readValue { tagClass, tag, constructed, length ->
        assertThat(tag).isEqualTo(3L)
        assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_APPLICATION)
        assertThat(length).isEqualTo(5L)
        assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
      }).isTrue()

      derReader.requireNoMoreValues()
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `implicit tagged implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type3 ::= [2] Type2
    // Type4 ::= [APPLICATION 7] IMPLICIT Type3
    val buffer = Buffer()
        .write("670743054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(7L)
      assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_APPLICATION)
      assertThat(length).isEqualTo(7L)

      assertThat(derReader.readValue { tagClass, tag, constructed, length ->
        assertThat(tag).isEqualTo(3L)
        assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_APPLICATION)
        assertThat(length).isEqualTo(5L)
        assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
      }).isTrue()

      derReader.requireNoMoreValues()
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `implicit implicit prefixed type`() {
    // Type1 ::= VisibleString
    // Type2 ::= [APPLICATION 3] IMPLICIT Type1
    // Type5 ::= [2] IMPLICIT Type2
    val buffer = Buffer()
        .write("82054A6F6E6573".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(2L)
      assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_CONTEXT_SPECIFIC)
      assertThat(length).isEqualTo(5L)
      assertThat(derReader.readOctetString()).isEqualTo("Jones".encodeUtf8())
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `object identifier`() {
    val buffer = Buffer()
        .write("0603883703".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(6L)
      assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_UNIVERSAL)
      assertThat(length).isEqualTo(3L)
      assertThat(derReader.readObjectIdentifier()).isEqualTo("2.999.3")
    }).isTrue()

    derReader.requireNoMoreValues()
  }

  @Test fun `relative object identifier`() {
    val buffer = Buffer()
        .write("0D04c27B0302".decodeHex())

    val derReader = DerReader(buffer)

    assertThat(derReader.readValue { tagClass, tag, constructed, length ->
      assertThat(tag).isEqualTo(13L)
      assertThat(tagClass).isEqualTo(DerReader.TAG_CLASS_UNIVERSAL)
      assertThat(length).isEqualTo(4L)
      assertThat(derReader.readRelativeObjectIdentifier()).isEqualTo("8571.3.2")
    }).isTrue()

    derReader.requireNoMoreValues()
  }
}
