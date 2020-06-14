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

import okio.IOException

/**
 * A composite adapter for a struct or data class. This may be used for both SEQUENCE and SET types.
 *
 * The fields are specified as a list of member adapters. When decoding, a value for each
 * non-optional member but be included in sequence.
 *
 * TODO: for sets, sort by tag when encoding.
 * TODO: for set ofs, sort by encoded value when encoding.
 */
internal class DerSequenceAdapter<T>(
  tagClass: Int = DerReader.TAG_CLASS_UNIVERSAL,
  tag: Long = DerReader.TAG_SEQUENCE,
  isOptional: Boolean = false,
  defaultValue: T? = null,
  val members: List<DerAdapter<*>>,
  val constructor: (List<*>) -> T
) : DerAdapter<T>(tagClass, tag, isOptional, defaultValue) {
  override fun read(reader: DerReader): T {
    val list = mutableListOf<Any?>()

    reader.readAll { tagClass, tag, constructed, length ->
      // Skip absent optional members until we find one with this tag.
      while (true) {
        if (list.size == members.size) throw IOException("unexpected $tagClass/$tag")

        val member = members[list.size]
        if (member.matches(tagClass, tag)) {
          list += member.read(reader)
          break
        } else if (member.isOptional) {
          list += member.defaultValue
        } else {
          throw IOException("expected ${member.tagClass}/${member.tag} but was $tagClass/$tag")
        }
      }
    }


    // All remaining members must be optional.
    while (list.size < members.size) {
      val member = members[list.size]
      if (member.isOptional) {
        list += member.defaultValue
      } else {
        throw IOException("expected ${member.tagClass}/${member.tag} not found")
      }
    }

    return constructor(list)
  }
}
