package eu.tintera.ai.playground

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as PlaygroundApplication

    /** Výsledek dialogu Google Play o souhlas s instalací runtime. */
    private val confirmation = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val request = app.confirmations.takeShown() ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) request.declined()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App(app.playground) }

        // Notifikace průběhu stahování (Android 13+ se ptá).
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Play si před instalací runtime může říct o souhlas (SplitInstallConfirmations, README).
        val manager = SplitInstallManagerFactory.create(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.confirmations.pending.filterNotNull().collect { request ->
                    app.confirmations.markShown(request)
                    val shown = runCatching { manager.startConfirmationDialogForResult(request.state, confirmation) }
                    if (shown.getOrDefault(false) != true) request.failed()
                }
            }
        }
    }
}
