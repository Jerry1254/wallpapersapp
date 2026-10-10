package com.qingjing.qingjing_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityEnvironmentChecksTest {
    @Test fun disabledRulesNeverReadSettingsOrOtherDetectors() {
        val reads = mutableListOf<String>()
        val detector = SecurityEnvironmentChecks(
            { reads.add("developer"); 1 }, { reads.add("usb"); 1 },
            { reads.add("root"); true }, { reads.add("emulator"); true },
            { reads.add("wifi"); 1 },
        )
        assertEquals(emptyMap<String, String>(), detector.check(emptyList()))
        assertEquals(mapOf("UNSUPPORTED" to "UNKNOWN"), detector.check(listOf("UNSUPPORTED")))
        assertEquals(emptyList<String>(), reads)
    }

    @Test fun developerAndUsbRulesAreIndependentAndOnlyExplicitEnabledValueIsRisk() {
        var developer = 1
        var usb = 0
        var developerReads = 0
        var usbReads = 0
        val detector = SecurityEnvironmentChecks(
            { developerReads++; developer }, { usbReads++; usb }, { false }, { false },
        )
        assertEquals(mapOf("DEVELOPER_MODE" to "RISK"), detector.check(listOf("DEVELOPER_MODE")))
        assertEquals(1, developerReads)
        assertEquals(0, usbReads)
        assertEquals(mapOf("USB_DEBUGGING" to "UNKNOWN"), detector.check(listOf("USB_DEBUGGING")))
        assertEquals(1, developerReads)
        assertEquals(1, usbReads)
        developer = 0
        usb = 1
        assertEquals(mapOf("DEVELOPER_MODE" to "UNKNOWN", "USB_DEBUGGING" to "RISK"),
            detector.check(listOf("DEVELOPER_MODE", "USB_DEBUGGING")))
    }

    @Test fun missingRedactedAndUnexpectedSettingsNeverBecomeRiskOrClaimNormal() {
        for (value in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            val detector = SecurityEnvironmentChecks({ value }, { value }, { false }, { false })
            assertEquals(mapOf("DEVELOPER_MODE" to "UNKNOWN", "USB_DEBUGGING" to "UNKNOWN"),
                detector.check(listOf("DEVELOPER_MODE", "USB_DEBUGGING")))
        }
    }

    @Test fun failedReadDoesNotHideAnEnabledIndependentRule() {
        val detector = SecurityEnvironmentChecks(
            { throw SecurityException("System denies the read") }, { 1 }, { false }, { false },
        )
        assertEquals(mapOf("DEVELOPER_MODE" to "UNKNOWN", "USB_DEBUGGING" to "RISK", "ROOT_JAILBREAK" to "NORMAL"),
            detector.check(listOf("DEVELOPER_MODE", "USB_DEBUGGING", "ROOT_JAILBREAK")))
        val inverse = SecurityEnvironmentChecks({ 1 }, { throw IllegalStateException("Provider unavailable") }, { false }, { false })
        assertEquals(mapOf("DEVELOPER_MODE" to "RISK", "USB_DEBUGGING" to "UNKNOWN"),
            inverse.check(listOf("DEVELOPER_MODE", "USB_DEBUGGING")))
    }

    @Test fun duplicateRulesDoNotRepeatSystemReads() {
        var reads = 0
        val detector = SecurityEnvironmentChecks({ reads++; 1 }, { 0 }, { false }, { false })
        assertEquals(mapOf("DEVELOPER_MODE" to "RISK"), detector.check(listOf("DEVELOPER_MODE", "DEVELOPER_MODE")))
        assertEquals(1, reads)
    }

    @Test fun wirelessDebuggingIsRiskWithUsbOffAndDeveloperRuleDisabled() {
        val detector = SecurityEnvironmentChecks(
            { throw AssertionError("Developer rule must stay off") }, { 0 }, { false }, { false }, { 1 },
        )
        assertEquals(mapOf("USB_DEBUGGING" to "RISK"), detector.check(listOf("USB_DEBUGGING")))
    }

    @Test fun deniedTransportCannotHideTheOtherPositiveAndUnknownNeverBecomesRisk() {
        val wifi = SecurityEnvironmentChecks({ 0 }, { throw SecurityException() }, { false }, { false }, { 1 })
        val usb = SecurityEnvironmentChecks({ 0 }, { 1 }, { false }, { false }, { throw SecurityException() })
        val unknown = SecurityEnvironmentChecks({ 0 }, { 0 }, { false }, { false }, { throw SecurityException() })
        assertEquals("RISK", wifi.check(listOf("USB_DEBUGGING"))["USB_DEBUGGING"])
        assertEquals("RISK", usb.check(listOf("USB_DEBUGGING"))["USB_DEBUGGING"])
        assertEquals("UNKNOWN", unknown.check(listOf("USB_DEBUGGING"))["USB_DEBUGGING"])
    }
}
