package com.andrerinas.openheadunit.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.DiagnosticExportStore
import com.andrerinas.openheadunit.utils.DiagnosticReport
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsFragment : Fragment(R.layout.aa_fragment_diagnostics) {
    private var exporting = false
    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) export(uri)
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<MaterialToolbar>(R.id.aa_toolbar).setNavigationOnClickListener { findNavController().popBackStack() }
        view.findViewById<View>(R.id.aa_save_diagnostic_report).setOnClickListener {
            export() // Android 9 saves privately instead of launching an OEM picker/interceptor.
        }
        view.findViewById<View>(R.id.aa_choose_diagnostic_location).setOnClickListener { chooseLocation() }
    }
    private fun fileName() = "DiAuto-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
    private fun chooseLocation() {
        runCatching { destination.launch(fileName()) }.onFailure {
            Toast.makeText(requireContext(), getString(R.string.aa_diagnostic_file_picker_unavailable), Toast.LENGTH_LONG).show()
        }
    }
    private fun export(destination: Uri? = null) {
        if (exporting) return
        exporting = true
        val context = requireContext().applicationContext
        val fileName = fileName()
        val button = view?.findViewById<MaterialButton>(R.id.aa_save_diagnostic_report)
        button?.apply { isEnabled = false; text = getString(R.string.aa_diagnostic_saving_report) }
        view?.findViewById<View>(R.id.aa_choose_diagnostic_location)?.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = DiagnosticReport.build(context)
                    if (destination != null) {
                        DiagnosticExportStore.write(context.contentResolver, destination, report)
                        destination
                    } else if (Build.VERSION.SDK_INT >= 29) {
                        DiagnosticExportStore.saveToDownloads(context.contentResolver, fileName, report)
                    } else {
                        DiagnosticExportStore.savePrivate(context, fileName, report)
                    }
                }
            }
            exporting = false
            if (!isAdded || view == null) return@launch
            button?.apply { isEnabled = true; text = getString(R.string.aa_save_diagnostic_report) }
            view?.findViewById<View>(R.id.aa_choose_diagnostic_location)?.isEnabled = true
            result.onSuccess { uri ->
                MaterialAlertDialogBuilder(requireContext(), R.style.aa_DarkAlertDialog)
                    .setTitle(getString(R.string.aa_diagnostic_report_saved))
                    .setMessage(when {
                        destination != null -> getString(R.string.aa_diagnostic_saved_to_your_selected_location)
                        Build.VERSION.SDK_INT >= 29 -> "Downloads/DiAuto/$fileName"
                        else -> null // The saved report is available through the Share button below.
                    })
                    .setPositiveButton(R.string.aa_share) { _, _ ->
                        runCatching {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = android.content.ClipData.newRawUri("Diagnostic report", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, getString(R.string.aa_diagnostic_share_diagnostic_report)))
                        }.onFailure { Toast.makeText(requireContext(), getString(R.string.aa_diagnostic_share_manually), Toast.LENGTH_LONG).show() }
                    }.setNegativeButton(getString(R.string.aa_diagnostic_done), null).show()
            }.onFailure {
                MaterialAlertDialogBuilder(requireContext(), R.style.aa_DarkAlertDialog)
                    .setTitle(getString(R.string.aa_diagnostic_could_not_save_the_report))
                    .setMessage(getString(R.string.aa_diagnostic_save_failure_hint))
                    .setPositiveButton(getString(R.string.aa_diagnostic_choose_location)) { _, _ -> chooseLocation() }
                    .setNegativeButton(R.string.aa_close, null).show()
            }
        }
    }
}
