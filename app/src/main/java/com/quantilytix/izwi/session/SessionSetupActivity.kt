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
import kotlinx.coroutines.launch

/**
 * Launcher entry point while the consent screen is disabled for this
 * internal recording pass — ConsentActivity is kept intact and just wired
 * back in as the launcher once this ships to a public or external speaker.
 * A consent record is still written (accepted=true) so the relay's
 * consent.json contract is unaffected either way.
 */
class SessionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (SessionManager.hasActiveSession(this)) {
            startActivity(RecordingActivity.intent(this))
            finish()
            return
        }

        binding.speakerIdInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.startSessionButton.isEnabled = s?.toString()?.trim()?.isNotEmpty() == true
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.startSessionButton.setOnClickListener {
            val speakerId = binding.speakerIdInput.text.toString().trim()
            val consentVersion = getString(R.string.consent_version)
            val scriptVersion = ScriptRepository(this).scriptVersion()

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
                startActivity(RecordingActivity.intent(this@SessionSetupActivity))
                finish()
            }
        }
    }
}
