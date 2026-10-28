package com.andrerinas.openheadunit.gwm

import com.andrerinas.openheadunit.hud.ClusterMapConfig
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.google.protobuf.UnknownFieldSet
import org.junit.Assert.*
import org.junit.Test

class GwmMapExperimentsTest {
    @Test fun stockConstructorInsetsAreRecoveredExactlyAtTheirOriginalSize() {
        assertEquals(ClusterMapConfig.Insets(140, 100, 240, 240), GwmMapExperiments.insets(
            GwmMapExperiments.Mode.STOCK_INSETS, 1280, 720, ClusterMapConfig.Insets(0, 0, 0, 0)))
    }
    @Test fun allModesPreservePhysicalWindowAspectAndKeepTheSafeAreaInsideIt() {
        for (mode in GwmMapExperiments.Mode.entries) for ((w, h) in listOf(1920 to 720, 960 to 720, 480 to 720)) {
            val p = GwmClusterProfile.profile(w, h, ClusterMapConfig.Detail.EXTRA_LARGE, 0, 0, mode)
            assertEquals(w.toFloat() / h, p.contentWidth.toFloat() / p.contentHeight, .003f)
            val i = p.contentInsets
            assertTrue(i.left >= 0 && i.right >= 0 && i.top >= 0 && i.bottom >= 0)
            assertTrue(i.left + i.right < p.contentWidth && i.top + i.bottom < p.contentHeight)
            assertEquals(p.codecWidth, p.contentWidth + p.widthMargin)
            assertEquals(p.codecHeight, p.contentHeight + p.heightMargin)
        }
    }
    @Test fun stockStreamHasKnownCodecAndDensityWithoutChangingBaseline() {
        val normal = GwmClusterProfile.profile(1920, 720, ClusterMapConfig.Detail.EXTRA_LARGE, 0, 0)
        val stock = GwmClusterProfile.profile(1920, 720, ClusterMapConfig.Detail.EXTRA_LARGE, 0, 0, GwmMapExperiments.Mode.STOCK_STREAM)
        assertEquals(1920, normal.codecWidth); assertEquals(240, normal.density)
        assertEquals(1280, stock.codecWidth); assertEquals(160, stock.density)
        assertEquals(480, stock.contentHeight)
        assertEquals(GwmMapExperiments.Mode.CURRENT, GwmMapExperiments.Mode.of("unrecognised"))
    }
    @Test fun defaultPlacementOmitsBothInsetFieldsButRetainsCodecMargins() {
        val p = GwmClusterProfile.profile(1920, 720, ClusterMapConfig.Detail.NORMAL, 0, 0)
        val normal = GwmMapExperiments.uiConfig(p, GwmMapExperiments.Mode.CURRENT)
        assertTrue(normal.hasContentInsets()); assertTrue(normal.hasStableContentInsets())
        val automatic = GwmMapExperiments.uiConfig(p, GwmMapExperiments.Mode.PHONE_DEFAULT)
        assertFalse(automatic.hasContentInsets()); assertFalse(automatic.hasStableContentInsets())
        assertEquals(normal.margins, automatic.margins)
        val densityOnly = GwmClusterProfile.profile(1920, 720, ClusterMapConfig.Detail.EXTRA_LARGE, 0, 0, GwmMapExperiments.Mode.STOCK_DPI)
        assertEquals(p.codecWidth, densityOnly.codecWidth); assertEquals(160, densityOnly.density)
    }
    @Test fun imageTestAdvertisesCompleteImageOptionsAndBaselineRetainsCodesOnly() {
        val codes = GwmMapExperiments.navigationService(false)
        assertEquals(Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly, codes.type)
        assertFalse(codes.hasImageOptions())
        val images = GwmMapExperiments.navigationService(true)
        assertEquals(Control.Service.NavigationStatusService.ClusterType.CustomImageSupported, images.type)
        assertEquals(128, images.imageOptions.width); assertEquals(128, images.imageOptions.height)
        assertEquals(32, images.imageOptions.colourDethBits)
    }
    @Test fun evidencePreservesTimingAndUnknownFieldNumbersWithoutExportingText() {
        val arrival = NavigationStatus.NavigationDestinationDistance.newBuilder()
            .setEstimatedTimeAtArrival("SECRET ARRIVAL TEXT").setTimeToArrivalSeconds(420).build()
        val position = NavigationStatus.NavigationCurrentPosition.newBuilder()
            .addDestinationDistances(arrival)
            .setCurrentRoad(NavigationStatus.NavigationRoad.newBuilder().setName("SECRET ROAD"))
            .setUnknownFields(UnknownFieldSet.newBuilder().addField(99,
                UnknownFieldSet.Field.newBuilder().addLengthDelimited(com.google.protobuf.ByteString.copyFromUtf8("SECRET UNKNOWN")).build()).build())
            .build()
        val evidence = GwmNavigationEvidence.describe(position)
        assertTrue(evidence.contains("time_to_arrival_seconds=420"))
        assertTrue(evidence.contains("unknownFields=[99]"))
        assertFalse(evidence.contains("SECRET"))
        assertTrue(evidence.length <= 1800)
    }

    @Test fun protocolVersionsEncodeAsBigEndianMajorAndMinor() {
        assertArrayEquals(byteArrayOf(0, 1, 0, 2), GwmMapExperiments.versionPayload(GwmMapExperiments.Protocol.BASELINE))
        assertArrayEquals(byteArrayOf(0, 1, 0, 6), GwmMapExperiments.versionPayload(GwmMapExperiments.Protocol.RICH_GUIDANCE))
        assertArrayEquals(byteArrayOf(0, 4, 0, 3), GwmMapExperiments.versionPayload(GwmMapExperiments.Protocol.DISPLAY_FLAGS))
        assertEquals(GwmMapExperiments.Protocol.BASELINE, GwmMapExperiments.Protocol.of("unrecognised"))
        assertFalse(GwmMapExperiments.Protocol.RICH_GUIDANCE.readsHiddenUiElements)
        assertTrue(GwmMapExperiments.Protocol.DISPLAY_FLAGS.readsHiddenUiElements)
        assertArrayEquals(byteArrayOf(0, 6, 0, 2), GwmMapExperiments.versionPayload(GwmMapExperiments.Protocol.NEWEST))
        assertTrue(GwmMapExperiments.Protocol.NEWEST.readsHiddenUiElements)
    }
    @Test fun turnCardFlagIsWrittenAsHiddenUiElementFiveOnlyWhenAsked() {
        val p = GwmClusterProfile.profile(1920, 720, ClusterMapConfig.Detail.NORMAL, 0, 0)
        val plain = GwmMapExperiments.uiConfig(p, GwmMapExperiments.Mode.CURRENT)
        assertTrue(plain.unknownFields.asMap().isEmpty())
        val flagged = GwmMapExperiments.uiConfig(p, GwmMapExperiments.Mode.CURRENT, turnCard = true)
        assertEquals(listOf(5L), flagged.unknownFields.getField(5).varintList)
        // On the wire: field 5, varint (tag 0x28), value 5; everything else unchanged.
        val bytes = flagged.toByteArray()
        assertTrue(bytes.toList().windowed(2).any { it == listOf(0x28.toByte(), 5.toByte()) })
        assertEquals(plain, com.andrerinas.openheadunit.aap.protocol.proto.Media.UiConfig.parseFrom(bytes).toBuilder()
            .setUnknownFields(UnknownFieldSet.getDefaultInstance()).build())
    }
    @Test fun evidenceNamesTheWireTypeOfUnknownFields() {
        val position = NavigationStatus.NavigationCurrentPosition.newBuilder()
            .setUnknownFields(UnknownFieldSet.newBuilder()
                .addField(9, UnknownFieldSet.Field.newBuilder().addVarint(3).build())
                .addField(12, UnknownFieldSet.Field.newBuilder().addLengthDelimited(com.google.protobuf.ByteString.copyFromUtf8("SECRET")).build())
                .build()).build()
        val evidence = GwmNavigationEvidence.describe(position)
        assertTrue(evidence, evidence.contains("9:varint") && evidence.contains("12:len"))
        assertFalse(evidence.contains("SECRET"))
    }
}
