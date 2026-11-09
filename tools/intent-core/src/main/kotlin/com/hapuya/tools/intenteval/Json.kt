package com.hapuya.tools.intenteval

import com.fasterxml.jackson.databind.ObjectMapper

private val mapper = ObjectMapper()

/** Reads the model's JSON object into an [Intent]; missing fields become "". */
fun intentFromJson(text: String): Intent {
    val n = mapper.readTree(text)
    fun f(name: String) = n.path(name).let { if (it.isMissingNode || it.isNull) "" else it.asText() }
    return Intent(f("intent"), f("title"), f("when_local"), f("recurrence"), f("duration_min"), f("expect"))
}
