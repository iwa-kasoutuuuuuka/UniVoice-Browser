package com.univoice.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.univoice.browser.R
import com.univoice.browser.databinding.ActivityUnivoiceTranscriptBinding
import com.univoice.browser.model.UniVoiceSubtitleCue
import com.univoice.browser.transcript.TranscriptRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 日英対訳スクリプト履歴画面
 * 視聴中に蓄積された字幕を一覧表示し、語学学習用に Markdown やテキストでエクスポート可能
 */
class UniVoiceTranscriptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnivoiceTranscriptBinding
    private lateinit var repository: TranscriptRepository
    private val adapter = TranscriptAdapter()

    override fun attachBaseContext(newBase: android.content.Context) {
        val locale = java.util.Locale.JAPANESE
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnivoiceTranscriptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = TranscriptRepository.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupActions()
        observeTranscripts()
    }

    private fun setupToolbar() {
        binding.toolbarTranscript.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        binding.rvTranscripts.layoutManager = LinearLayoutManager(this)
        binding.rvTranscripts.adapter = adapter
    }

    private fun setupActions() {
        // Markdown形式でクリップボードへコピー
        binding.btnCopyMarkdown.setOnClickListener {
            val md = repository.exportAsMarkdown()
            if (md.isBlank() || adapter.itemCount == 0) {
                Toast.makeText(this, "コピーするスクリプト履歴がありません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("UniVoice Transcript Markdown", md)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Markdown形式でクリップボードにコピーしました", Toast.LENGTH_SHORT).show()
        }

        // 共有インテント
        binding.btnShareTranscript.setOnClickListener {
            val md = repository.exportAsMarkdown()
            if (md.isBlank() || adapter.itemCount == 0) {
                Toast.makeText(this, "共有するスクリプト履歴がありません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "UniVoice 日英対訳スクリプト")
                putExtra(Intent.EXTRA_TEXT, md)
            }
            startActivity(Intent.createChooser(intent, "スクリプトを共有"))
        }

        // 履歴クリア
        binding.btnClearHistory.setOnClickListener {
            repository.clearAll()
            Toast.makeText(this, "スクリプト履歴を消去しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeTranscripts() {
        lifecycleScope.launch {
            repository.cuesFlow.collectLatest { cues ->
                adapter.submitList(cues)
                binding.tvCountLabel.text = "保存済み字幕: ${cues.size}件"
                if (cues.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvTranscripts.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.rvTranscripts.visibility = View.VISIBLE
                }
            }
        }
    }

    // --- RecyclerView Adapter ---

    private class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.ViewHolder>() {

        private var items: List<UniVoiceSubtitleCue> = emptyList()

        fun submitList(newItems: List<UniVoiceSubtitleCue>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transcript_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTimestamp: TextView = itemView.findViewById(R.id.tvRowTimestamp)
            private val tvJapanese: TextView = itemView.findViewById(R.id.tvRowJapanese)
            private val tvEnglish: TextView = itemView.findViewById(R.id.tvRowEnglish)

            fun bind(cue: UniVoiceSubtitleCue) {
                val sec = (cue.startTimeMs / 1000) % 60
                val min = (cue.startTimeMs / (1000 * 60)) % 60
                val hrs = cue.startTimeMs / (1000 * 60 * 60)
                val timeStr = if (hrs > 0) {
                    String.format(Locale.getDefault(), "%02d:%02d:%02d", hrs, min, sec)
                } else {
                    String.format(Locale.getDefault(), "%02d:%02d", min, sec)
                }

                tvTimestamp.text = timeStr
                tvJapanese.text = cue.translatedText ?: cue.cleanText
                tvEnglish.text = cue.cleanText
            }
        }
    }
}
