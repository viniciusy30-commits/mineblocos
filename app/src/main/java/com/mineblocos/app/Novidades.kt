package com.mineblocos.app

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog

object Novidades {
    class Entry(val id: String, val titulo: String, val itens: List<String>)

    fun load(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val text = ctx.assets.open("novidades.txt").bufferedReader().use { it.readText() }
            var id: String? = null
            var titulo = ""
            var itens = ArrayList<String>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.startsWith("#id ")) {
                    if (id != null) out.add(Entry(id, titulo, itens))
                    id = line.substring(4).trim()
                    titulo = ""
                    itens = ArrayList()
                } else if (line.startsWith("#titulo ")) {
                    titulo = line.substring(8).trim()
                } else if (line.startsWith("- ")) {
                    itens.add(line.substring(2).trim())
                }
            }
            if (id != null) out.add(Entry(id, titulo, itens))
        } catch (e: Exception) {
            // sem arquivo: lista vazia
        }
        return out
    }

    fun showIfNew(a: Activity) {
        val list = load(a)
        if (list.isEmpty()) return
        val top = list[0]
        val p = a.getSharedPreferences("novidades", Context.MODE_PRIVATE)
        if (p.getString("visto", "") == top.id) return
        p.edit().putString("visto", top.id).apply()
        show(a, listOf(top))
    }

    fun showAll(a: Activity) {
        show(a, load(a))
    }

    private fun show(a: Activity, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val sb = StringBuilder()
        for (e in entries) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("★ ").append(e.titulo)
            for (i in e.itens) sb.append("\n• ").append(i)
        }
        AlertDialog.Builder(a)
            .setTitle("O que mudou")
            .setMessage(sb.toString())
            .setPositiveButton("Legal!", null)
            .show()
    }
}
