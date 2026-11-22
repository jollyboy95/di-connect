package com.andrerinas.openheadunit.gwm

import com.google.protobuf.Message
import com.google.protobuf.ByteString

/** Presence and timing evidence only: no road names, destination names, image bytes or coordinates. */
internal object GwmNavigationEvidence {
    fun describe(message: Message): String = buildString {
        fun fields(value: Message, depth: Int) {
            if (depth > 3) return
            for ((field, data) in value.allFields) {
                append(field.name).append('=')
                when (data) {
                    is Message -> { append('{'); fields(data, depth + 1); append('}') }
                    is List<*> -> {
                        append("count:").append(data.size)
                        data.take(2).filterIsInstance<Message>().forEach { append('{'); fields(it, depth + 1); append('}') }
                    }
                    is String -> append("text-present:").append(data.isNotEmpty())
                    is ByteString -> append("bytes:").append(data.size())
                    is Number -> if (field.name.contains("time") || field.name.contains("seconds") || field.name.contains("meters")) append(data) else append("present")
                    else -> append("present")
                }
                append(' ')
            }
            if (value.unknownFields.asMap().isNotEmpty()) {
                append("unknownFields=").append(value.unknownFields.asMap().keys).append(' ')
                // Wire type of each unknown field, never its contents.
                append("unknownWire=").append(value.unknownFields.asMap().map { (n, f) ->
                    "$n:" + listOfNotNull(
                        "varint".takeIf { f.varintList.isNotEmpty() }, "fixed32".takeIf { f.fixed32List.isNotEmpty() },
                        "fixed64".takeIf { f.fixed64List.isNotEmpty() }, "len".takeIf { f.lengthDelimitedList.isNotEmpty() },
                        "group".takeIf { f.groupList.isNotEmpty() }).joinToString("+")
                }).append(' ')
            }
        }
        fields(message, 0)
    }.take(1800)
}
