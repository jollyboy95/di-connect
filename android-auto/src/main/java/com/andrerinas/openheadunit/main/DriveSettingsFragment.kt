package com.andrerinas.openheadunit.main

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.google.android.material.appbar.MaterialToolbar

class DriveSettingsFragment : Fragment(R.layout.aa_fragment_drive_settings) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialToolbar>(R.id.aa_toolbar).setNavigationOnClickListener {
            requireActivity().finish()
        }
        mapOf(
            R.id.aa_da_connection_setup to R.id.aa_connectionSetupFragment,
            R.id.aa_da_automation to R.id.aa_autoStartFragment,
            R.id.aa_da_display to R.id.aa_settingsFragment,
            R.id.aa_da_permissions to R.id.aa_permissionsFragment,
            R.id.aa_da_diagnostics to R.id.aa_diagnosticsFragment,
            R.id.aa_da_about to R.id.aa_aboutFragment
        ).forEach { (button, destination) ->
            view.findViewById<View>(button).setOnClickListener { findNavController().navigate(destination) }
        }
    }
}
