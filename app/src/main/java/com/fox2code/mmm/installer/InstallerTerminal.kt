@file:Suppress("unused")

package com.fox2code.mmm.installer

import android.graphics.Typeface
import android.text.Spannable
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fox2code.androidansi.AnsiContext
import com.fox2code.mmm.installer.InstallerTerminal.TextViewHolder

class InstallerTerminal(
    recyclerView: RecyclerView, isLightTheme: Boolean,
    foreground: Int, mmtReborn: Boolean
) : RecyclerView.Adapter<TextViewHolder>() {
    private val recyclerView: RecyclerView
    private val terminal: ArrayList<ProcessedLine>
    private val ansiContext: AnsiContext
    private val lock = Any()
    private val foreground: Int
    private val mmtReborn: Boolean
    var isAnsiEnabled = false
        private set

    init {
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        this.recyclerView = recyclerView
        this.foreground = foreground
        this.mmtReborn = mmtReborn
        terminal = ArrayList()
        ansiContext = (if (isLightTheme) AnsiContext.LIGHT else AnsiContext.DARK).copy()
        this.recyclerView.adapter = this
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextViewHolder {
        return TextViewHolder(TextView(parent.context), foreground)
    }

    override fun onBindViewHolder(holder: TextViewHolder, position: Int) {
        terminal[position].setText(holder.textView)
    }

    override fun getItemCount(): Int {
        return terminal.size
    }

    fun addLine(line: String) {
        synchronized(lock) {
            val bottom = !recyclerView.canScrollVertically(1)
            val index = terminal.size
            terminal.add(process(line))
            notifyItemInserted(index)
            if (bottom) recyclerView.scrollToPosition(index)
        }
    }

    var lastLine: String
        get() {
            synchronized(lock) {
                val size = terminal.size
                return if (size == 0) "" else terminal[size - 1].line
            }
        }
        set(line) {
            synchronized(lock) {
                val size = terminal.size
                if (size == 0) {
                    terminal.add(process(line))
                    notifyItemInserted(0)
                } else {
                    terminal[size - 1] = process(line)
                    this.notifyItemChanged(size - 1)
                }
            }
        }

    fun removeLastLine() {
        synchronized(lock) {
            val size = terminal.size
            if (size != 0) {
                terminal.removeAt(size - 1)
                notifyItemRemoved(size - 1)
            }
        }
    }

    fun clearTerminal() {
        synchronized(lock) {
            val size = terminal.size
            if (size != 0) {
                terminal.clear()
                notifyItemRangeRemoved(0, size)
            }
        }
    }

    fun scrollUp() {
        synchronized(lock) { recyclerView.scrollToPosition(0) }
    }

    fun scrollDown() {
        synchronized(lock) { recyclerView.scrollToPosition(terminal.size - 1) }
    }

    fun enableAnsi() {
        isAnsiEnabled = true
    }

    fun disableAnsi() {
        isAnsiEnabled = false
        ansiContext.reset()
    }

    private fun process(line: String): ProcessedLine {
        @Suppress("NAME_SHADOWING") var line = line
        if (line.isEmpty()) return ProcessedLine(" ", null)
        if (mmtReborn) {
            if (line.startsWith("- ")) {
                line = "[*] " + line.substring(2)
            } else if (line.startsWith("! ")) {
                line = "[!] " + line.substring(2)
            }
        }
        return ProcessedLine(line, if (isAnsiEnabled) ansiContext.parseAsSpannable(line) else null)
    }

    class TextViewHolder(val textView: TextView, foreground: Int) : RecyclerView.ViewHolder(
        textView
    ) {
        init {
            textView.typeface = Typeface.MONOSPACE
            textView.setTextColor(foreground)
            textView.textSize = 12f
            textView.setLines(1)
            textView.text = " "
        }
    }

    private class ProcessedLine(val line: String, val spannable: Spannable?) {
        fun setText(textView: TextView) {
            textView.text = spannable ?: line
        }
    }
}