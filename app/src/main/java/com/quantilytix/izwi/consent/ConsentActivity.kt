package com.quantilytix.izwi.consent

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.R
import com.quantilytix.izwi.data.ConsentRecordEntity
import com.quantilytix.izwi.databinding.ActivityConsentBinding
import com.quantilytix.izwi.recording.RecordingActivity
import com.quantilytix.izwi.session.ScriptRepository
import com.quantilytix.izwi.session.SessionManager
import com.quantilytix.izwi.ui.applySystemBarInsetPadding
import kotlinx.coroutines.launch

class ConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetPadding(applyTop = true, applyBottom = true)

        if (SessionManager.hasActiveSession(this)) {
            startActivity(RecordingActivity.intent(this))
            finish()
            return
        }

        fun refreshButtonState() {
            binding.startSessionButton.isEnabled =
                binding.consentCheckbox.isChecked && binding.speakerIdInput.text.trim().isNotEmpty()
        }

        binding.consentCheckbox.setOnCheckedChangeListener { _, _ -> refreshButtonState() }
        binding.speakerIdInput.doAfterTextChangedCompat { refreshButtonState() }

        binding.startSessionButton.setOnClickListener {
            val speakerId = binding.speakerIdInput.text.toString().trim()
            val consentVersion = getString(R.string.consent_version)
            val scriptVersion = ScriptRepository(this).activeScriptVersion()

            val sessionId = SessionManager.startNewSession(this, speakerId, consentVersion, scriptVersion)

            val app = application as IzwiApplication
            lifecycleScope.launch {
                app.database.consentDao().upsert(
                    ConsentRecordEntity(
                        sessionId = sessionId,
                        speakerId = speakerId,
                        consentVersion = consentVersion,
                        acceptedAtUtc = SessionManager.nowUtcIso(),
                        accepted = true,
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    )
                )
                startActivity(RecordingActivity.intent(this@ConsentActivity))
                finish()
            }
        }
    }
}

private fun android.widget.EditText.doAfterTextChangedCompat(action: () -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = action()
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}
