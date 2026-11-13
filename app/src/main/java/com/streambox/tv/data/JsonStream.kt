package com.streambox.tv.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Streams a (possibly huge) JSON array of flat objects without loading it all in memory.
 * Nested arrays/objects inside each element are skipped. Some panels return an object
 * keyed by id instead of an array; that shape is handled too.
 */
object JsonStream {

    fun readObjects(input: InputStream, onObject: (Map<String, String>) -> Unit) {
        val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        r.isLenient = true
        try {
            when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() == JsonToken.BEGIN_OBJECT) onObject(readFlat(r)) else r.skipValue()
                    }
                    r.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        r.nextName()
                        if (r.peek() == JsonToken.BEGIN_OBJECT) onObject(readFlat(r)) else r.skipValue()
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        } finally {
            try {
                r.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readFlat(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                JsonToken.NULL -> r.nextNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }
}
