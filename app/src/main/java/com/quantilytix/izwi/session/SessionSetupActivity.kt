package com.quantilytix.izwi.session

import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.quantilytix.izwi.IzwiApplication
import com.quantilytix.izwi.R
import com.quantilytix.izwi.data.ConsentRecordEntity
import com.quantilytix.izwi.databinding.ActivitySessionSetupBinding
import com.quantilytix.izwi.recording.RecordingActivity
import com.quantilytix.izwi.ui.applySystemBarInsetPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher entry point while the consent screen is disabled for this
 * internal recording pass. ConsentActivity is kept intact and just wired
 * back in as the launcher once this ships to a public or external speaker.
 * A consent record is still written (accepted=true) so the relay's
 * consent.json contract is unaffected either way.
 *
 * The session's language (Shona or isiNdebele) is chosen here and
 * remembered per speaker; the Prompts screen can switch it mid-session.
 */
class SessionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionSetupBinding

    private val selectedLanguage: String
        get() = if (binding.languageToggle.checkedButtonId == R.id.languageNdebeleButton) Languages.NDEBELE else Languages.SHONA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetPadding(applyTop = true, applyBottom = true)

        binding.manageScriptButton.setOnClickListener {
            startActivity(ScriptEditorActivity.intent(this, speakerIdText(), selectedLanguage))
        }

        if (SessionManager.hasActiveSession(this)) {
            startActivity(RecordingActivity.intent(this))
            finish()
            return
        }

        binding.speakerIdInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.startSessionButton.isEnabled = s?.toString()?.trim()?.isNotEmpty() == true
                showRememberedLanguage()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // A tap on a language is her choice for this speaker. Remembering it
        // at once keeps onResume from flipping it back to the last session's
        // language after a visit to the Prompts screen or a screen-off.
        binding.languageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val code = if (checkedId == R.id.languageNdebeleButton) Languages.NDEBELE else Languages.SHONA
            SessionManager.rememberLanguage(this, speakerIdText(), code)
        }

        binding.startSessionButton.setOnClickListener {
            binding.startSessionButton.isEnabled = false
            val speakerId = speakerIdText()
            val language = selectedLanguage
            val consentVersion = getString(R.string.consent_version)
            val app = application as IzwiApplication
            lifecycleScope.launch {
                // Reading the script parses all of it; keep that off the main thread.
                val scriptVersion = withContext(Dispatchers.IO) {
                    ScriptRepository(this@SessionSetupActivity).activeScriptVersion(language)
                }
                val sessionId = SessionManager.startNewSession(
                    this@SessionSetupActivity, speakerId, consentVersion, scriptVersion, language,
                )
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
                startActivity(RecordingActivity.intent(this@SessionSetupActivity))
                finish()
            }
        }
    }

    /** The Prompts screen may have switched languages for this speaker. */
    override fun onResume() {
        super.onResume()
        if (!isFinishing) showRememberedLanguage()
    }

    private fun speakerIdText() = binding.speakerIdInput.text.toString().trim()

    private fun showRememberedLanguage() {
        val remembered = SessionManager.rememberedLanguage(this, speakerIdText()) ?: return
        binding.languageToggle.check(
            if (remembered == Languages.NDEBELE) R.id.languageNdebeleButton else R.id.languageShonaButton
        )
    }
}
