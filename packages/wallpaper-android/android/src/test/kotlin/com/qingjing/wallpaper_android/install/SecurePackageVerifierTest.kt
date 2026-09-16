package com.qingjing.wallpaper_android.install

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecurePackageVerifierTest {
    private val signer = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val verifier = SecurePackageVerifier { _, file -> require(file.length() > 0); MediaInfo(512,512,true) }
    private data class Fixture(val e: PackageExpectation, val encrypted: ByteArray, val key: ByteArray)
    private fun fixture(type: String = "STATIC_IMAGE", version: Int = 1, manifestEdit: (String) -> String = { it },
                        extra: Pair<String,ByteArray>? = null, signatureKey: KeyPair = signer,
                        zipEdit: (ByteArray) -> ByteArray = { it },purpose: PackagePurpose = PackagePurpose.FORMAL,
                        parallaxAngle: Int = 10): Fixture {
        val payloads = when(type) {
            "STATIC_IMAGE" -> listOf(Triple("STATIC_IMAGE", "image/png", byteArrayOf(1,2,3)))
            "VIDEO" -> listOf(Triple("VIDEO", "video/mp4", byteArrayOf(4,5,6)))
            else -> listOf(Triple("BACKGROUND", "image/png", byteArrayOf(1)), Triple("FOREGROUND", "image/png", byteArrayOf(2)),
                Triple("PARALLAX_CONFIG", "application/json",
                    """{"formatVersion":2,"canvas":{"width":512,"height":512},"motion":{"maxAngle":$parallaxAngle},"layers":[{"index":1,"offsetPercent":300,"direction":"follow","scale":1.1,"opacity":1,"blendMode":"normal"},{"index":2,"offsetPercent":20,"direction":"reverse","scale":1,"opacity":1,"blendMode":"normal"}]}""".toByteArray()))
        }
        val paths = payloads.map { "payload/${it.first.lowercase()}-0.${when(it.second) { "image/png" -> "png"; "video/mp4" -> "mp4"; else -> "json" }}" }
        val files = payloads.mapIndexed { i,p -> """{"path":"${paths[i]}","role":"${p.first}","ordinal":0,"mimeType":"${p.second}","sizeBytes":${p.third.size},"sha256":"${SecurePackageVerifier.hash(p.third)}"}""" }.joinToString(",")
        val extraPurpose = if(purpose == PackagePurpose.APP_PREVIEW) "\"purpose\":\"APP_PREVIEW\"," else ""
        val manifest = manifestEdit("""{"formatVersion":${purpose.format},${extraPurpose}"wallpaperId":"10","variantId":"20","versionNo":$version,"resourceType":"$type","signingKeyId":"test-root","files":[$files]}""").toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(signatureKey.private); update(manifest); sign() }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun write(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name).apply { time=0 }); zip.write(data); zip.closeEntry() }
            write("manifest.json", manifest); write("manifest.sig", signature)
            payloads.forEachIndexed { i,p -> write(paths[i],p.third) }; extra?.let { write(it.first,it.second) }
        }
        val plaintext = zipEdit(output.toByteArray()); val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val e = PackageExpectation("10","20",(30+version).toString(),version,type,plaintext.size+36L,plaintext.size.toLong(),"0".repeat(64),
            SecurePackageVerifier.hash(plaintext),SecurePackageVerifier.hash(manifest),"test-root")
        val encrypted = encrypt(plaintext,key,e,purpose)
        return Fixture(e.copy(encryptedHash=SecurePackageVerifier.hash(encrypted)),encrypted,key)
    }
    private fun encrypt(plaintext: ByteArray,key: ByteArray,e: PackageExpectation,purpose: PackagePurpose): ByteArray {
        val nonce=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce)); cipher.updateAAD(e.aad(purpose))
        return purpose.magic.toByteArray()+nonce+cipher.doFinal(plaintext)
    }
    private fun verify(f: Fixture, root: File,purpose: PackagePurpose = PackagePurpose.FORMAL): File {
        val encrypted=File(root,"input-${System.nanoTime()}"); encrypted.writeBytes(f.encrypted)
        val stage=File(root,"stage-${System.nanoTime()}").also { it.mkdir() }
        SecurePackageVerifier(purpose) { _,file -> require(file.length() > 0);MediaInfo(512,512,true) }
            .verify(encrypted,stage,f.e,f.key,"test-root",signer.public)
        assertFalse(File(stage,"package.zip").exists()); return stage
    }
    private fun reject(f: Fixture) = temporary { root ->
        try { verify(f,root); fail("Invalid package was accepted") } catch (_: Exception) { }
    }
    private fun temporary(block:(File)->Unit) {
        val root=Files.createTempDirectory("qj-verifier-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun installsAllThreeTypesAndPreservesOriginalManifestBytes() = temporary { root ->
        for(type in listOf("STATIC_IMAGE","VIDEO","LAYER_PARALLAX")) {
            val f=fixture(type,manifestEdit={ it.replace("{\"formatVersion", "{ \n\"formatVersion") })
            val stage=verify(f,root)
            assertEquals(f.e.manifestHash,SecurePackageVerifier.hash(File(stage,"manifest.json")))
            assertTrue(File(stage,"payload").isDirectory)
        }
    }
    @Test fun acceptsSignedOffsetConfigurationThroughSeventyFiveDegrees() = temporary { root ->
        assertTrue(verify(fixture("LAYER_PARALLAX",parallaxAngle=75),root).isDirectory)
        reject(fixture("LAYER_PARALLAX",parallaxAngle=76))
    }
    @Test fun rejectsCiphertextTamperingEvenIfTransportHashMatches() {
        val f=fixture(); val changed=f.encrypted.clone(); changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte()
        reject(f.copy(encrypted=changed,e=f.e.copy(encryptedHash=SecurePackageVerifier.hash(changed))))
    }
    @Test fun rejectsWrongVersionKeyAndSignatureRoot() {
        val f=fixture(); reject(f.copy(e=f.e.copy(versionNo=2))); reject(f.copy(key=ByteArray(32)))
        reject(fixture(signatureKey=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()))
        temporary { root ->
            val source=File(root,"encrypted").also { it.writeBytes(f.encrypted) }; val stage=File(root,"stage").also { it.mkdir() }
            try { verifier.verify(source,stage,f.e,f.key,"other-root",signer.public); fail() } catch (_: Exception) { }
        }
    }
    @Test fun rejectsSignedTraversalUnknownFilesDuplicateKeysAndInvalidRoles() {
        reject(fixture(manifestEdit={ it.replace("payload/static_image-0.png","../escape.png") }))
        reject(fixture(extra="payload/unknown-0.png" to byteArrayOf(1)))
        reject(fixture(manifestEdit={ it.replace("\"formatVersion\":2", "\"formatVersion\":1,\"formatVersion\":2") }))
        reject(fixture(manifestEdit={ it.replace("\"role\":\"STATIC_IMAGE\"", "\"role\":\"VIDEO\"") }))
        reject(fixture(manifestEdit={ it+"{}" }))
    }
    @Test fun rejectsSignedPayloadSizeDigestAndParallaxViolations() {
        reject(fixture(manifestEdit={ it.replace("\"sizeBytes\":3", "\"sizeBytes\":4") }))
        reject(fixture(manifestEdit={ it.replace(SecurePackageVerifier.hash(byteArrayOf(1,2,3)),"f".repeat(64)) }))
        val invalid=SecurePackageVerifier { _,_ -> MediaInfo(256,512,false) }
        temporary { root ->
            val f=fixture("LAYER_PARALLAX"); val source=File(root,"enc").also { it.writeBytes(f.encrypted) }; val stage=File(root,"stage").also { it.mkdir() }
            try { invalid.verify(source,stage,f.e,f.key,"test-root",signer.public); fail() } catch (_: Exception) { }
        }
    }
    @Test fun rejectsZipLinksAndTrailingBytesBeforeExtraction() {
        reject(fixture(zipEdit={ bytes ->
            val changed=bytes.clone(); val header=byteArrayOf(0x50,0x4b,0x01,0x02)
            val at=(0..changed.size-4).first { changed.copyOfRange(it,it+4).contentEquals(header) }
            changed[at+38]=0; changed[at+39]=0; changed[at+40]=0xff.toByte(); changed[at+41]=0xa1.toByte(); changed
        }))
        reject(fixture(zipEdit={ it+byteArrayOf(0) }))
    }
    @Test fun updateFailureRetainsOldVersionAndRecoveryOnlyCleansStaging() = temporary { root ->
        val store=AtomicPackageStore(File(root,"store")); val old=fixture(); val oldStage=verify(old,root)
        val move=store.staging(); oldStage.copyRecursively(move,overwrite=true); oldStage.deleteRecursively()
        val oldId=store.commit(move,old.e); val update=fixture(version=2); val next=verify(update,root)
        val nextStage=store.staging(); next.copyRecursively(nextStage,overwrite=true)
        try { store.commit(nextStage,update.e) { error("Simulated pointer failure") }; fail() } catch (_: Exception) { }
        assertEquals(oldId,store.current(old.e.slot)); assertTrue(store.directory(oldId).isDirectory)
        assertFalse(store.directory(update.e.installedId).exists())
        val orphan=store.staging(); File(orphan,"partial").writeText("incomplete"); val partial=store.partial().also { it.writeText("partial") }
        store.recover(); assertFalse(orphan.exists()); assertFalse(partial.exists()); assertEquals(oldId,store.current(old.e.slot))
    }
    @Test fun clearCachePreservesActiveResourcesAndRejectsOpaqueIdTraversal() = temporary { root ->
        val store=AtomicPackageStore(File(root,"store")); val old=fixture(); val source=verify(old,root)
        val stage=store.staging(); source.copyRecursively(stage,overwrite=true); val id=store.commit(stage,old.e)
        store.markActive(id); assertEquals(0L,store.clearUnused()); assertTrue(store.directory(id).isDirectory)
        try { store.directory("../escape"); fail() } catch (_: Exception) { }
        assertEquals(id,store.active())
    }
    @Test fun strictJsonRejectsMalformedUtf8SurrogatesDepthAndNumbers() {
        for(bytes in listOf(byteArrayOf(0xc3.toByte()),"{\"x\":1,\"x\":2}".toByteArray(),"[01]".toByteArray(),"[NaN]".toByteArray(),
            "[\"\\ud800\"]".toByteArray(),("[".repeat(18)+"0"+"]".repeat(18)).toByteArray())) {
            try { StrictJson.parse(bytes); fail() } catch (_: Exception) { }
        }
    }
    @Test fun verifiedUpdateRejectsDowngradeAndCacheKeepsBothActiveTargets() = temporary { root ->
        val store=AtomicPackageStore(File(root,"store"))
        fun commit(f: Fixture): String {
            val verified=verify(f,root); val stage=store.staging(); verified.copyRecursively(stage,overwrite=true)
            return store.commit(stage,f.e)
        }
        val old=fixture(); val oldId=commit(old); store.markActive(oldId,"lock")
        val update=fixture(version=2); val updateId=commit(update); store.markActive(updateId,"home")
        assertEquals(updateId,store.current(update.e.slot))
        try { commit(old); fail("Downgrade accepted") } catch (_: Exception) { }
        assertEquals(updateId,store.current(update.e.slot)); assertEquals(setOf(oldId,updateId),store.activeIds())
        store.clearUnused(); assertTrue(store.directory(oldId).isDirectory); assertTrue(store.directory(updateId).isDirectory)
    }
    @Test fun repeatInstallReusesVerifiedCacheButRejectsChangedCachedPayload() = temporary { root ->
        val store=AtomicPackageStore(File(root,"store")); val f=fixture()
        fun commit(): String {
            val verified=verify(f,root); val stage=store.staging(); verified.copyRecursively(stage,overwrite=true)
            return store.commit(stage,f.e)
        }
        val id=commit(); assertEquals(id,commit())
        File(store.directory(id),"payload/static_image-0.png").writeBytes(byteArrayOf(9,9,9))
        try { commit(); fail("Changed cache was reused") } catch (_: Exception) { }
        assertEquals(id,store.current(f.e.slot))
    }
    @Test fun persistedPackagesRequireOriginalSignatureIdentityAndPayloadBeforeUse() = temporary { root ->
        val store = AtomicPackageStore(File(root,"store")); val f = fixture()
        val source = verify(f,root); val stage = store.staging(); source.copyRecursively(stage,overwrite=true)
        val id = store.commit(stage,f.e); val reader = InstalledPackageVerifier("test-root",signer.public)
        assertEquals(id,reader.verify(store,id,"STATIC_IMAGE").id)
        fun rejects(block: () -> Unit) { try { block(); fail("Unchecked installed bytes were used") } catch (_: Exception) {} }
        rejects { reader.verify(store,id,"VIDEO") }
        rejects { InstalledPackageVerifier("other-root",signer.public).verify(store,id,"STATIC_IMAGE") }
        val signature = File(store.directory(id),"manifest.sig"); val original = signature.readBytes()
        signature.writeBytes(ByteArray(256)); rejects { reader.verify(store,id,"STATIC_IMAGE") }; signature.writeBytes(original)
        val payload = File(store.directory(id),"payload/static_image-0.png")
        payload.writeBytes(byteArrayOf(9,9,9)); rejects { reader.verify(store,id,"STATIC_IMAGE") }
        payload.delete(); Files.createSymbolicLink(payload.toPath(),File(source,"payload/static_image-0.png").toPath())
        rejects { reader.verify(store,id,"STATIC_IMAGE") }
    }
    @Test fun simultaneousPreviewLeasesAndPendingLiveSelectionSurviveCacheCleaning() = temporary { root ->
        val store = AtomicPackageStore(File(root,"store")); val f = fixture()
        val source = verify(f,root); val stage = store.staging(); source.copyRecursively(stage,overwrite=true)
        val id = store.commit(stage,f.e); val first = store.pin(id); val second = store.pin(id)
        first.close(); first.close(); assertEquals(0L,store.clearUnused()); assertTrue(store.directory(id).isDirectory)
        second.close(); store.hold("picker",id); assertEquals(0L,store.clearUnused())
        store.hold("live",id); store.hold("picker",null); assertEquals(id,store.held("live")); assertEquals(0L,store.clearUnused())
        store.hold("live-video",id); store.hold("live",null); assertEquals(0L,store.clearUnused())
        store.hold("live-parallax",id); store.hold("live-video",null); assertEquals(0L,store.clearUnused())
        store.hold("live-parallax",null); assertTrue(store.clearUnused() > 0); assertFalse(store.directory(id).exists())
    }
    @Test fun reducedPreviewsVerifyButFormalInstallAndPersistedReadersRejectThem() = temporary { root ->
        for(type in listOf("STATIC_IMAGE","VIDEO","LAYER_PARALLAX")) {
            val f = fixture(type,purpose = PackagePurpose.APP_PREVIEW)
            val source = verify(f,root,PackagePurpose.APP_PREVIEW)
            try { verify(f,root);fail("Formal verifier accepted a preview") } catch(_: Exception) {}
            try { verify(fixture(type),root,PackagePurpose.APP_PREVIEW);fail("Preview verifier accepted a formal package") } catch(_: Exception) {}
            val store = AtomicPackageStore(File(root,"preview-$type"));val stage = store.staging();source.copyRecursively(stage,overwrite = true)
            val id = store.commit(stage,f.e)
            assertEquals(id,InstalledPackageVerifier("test-root",signer.public,PackagePurpose.APP_PREVIEW).verify(store,id,type).id)
            try { InstalledPackageVerifier("test-root",signer.public).verify(store,id,type);fail("Formal playback accepted preview bytes") } catch(_: Exception) {}
            store.hold("trial",id);val lease = store.pin(id);store.hold("trial",null)
            assertEquals(0L,store.clearUnused());lease.close();assertTrue(store.clearUnused() > 0)
            assertFalse(store.directory(id).exists())
        }
    }
    @Test fun signedWrongPurposeAndOversizedPreviewsRemainInvalid() = temporary { root ->
        val wrong = fixture(purpose = PackagePurpose.APP_PREVIEW,manifestEdit = { it.replace("APP_PREVIEW","SYSTEM_WALLPAPER") })
        try { verify(wrong,root,PackagePurpose.APP_PREVIEW);fail("Wrong signed purpose was accepted") } catch(_: Exception) {}
        val f = fixture(purpose = PackagePurpose.APP_PREVIEW)
        val source = File(root,"encrypted").also { it.writeBytes(f.encrypted) };val stage = File(root,"stage").also { it.mkdir() }
        try {
            SecurePackageVerifier(PackagePurpose.APP_PREVIEW) { _,_ -> MediaInfo(1920,1080,true) }.verify(source,stage,f.e,f.key,"test-root",signer.public)
            fail("An oversized preview was accepted")
        } catch(_: Exception) {}
    }
}
