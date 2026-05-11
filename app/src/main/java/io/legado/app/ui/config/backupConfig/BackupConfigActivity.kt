package io.legado.app.ui.config.backupConfig

import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

class BackupConfigActivity : BaseComposeActivity() {

    @Composable
    override fun Content() {
        BackupConfigScreen(
            onBackClick = { finish() }
        )
    }
}
