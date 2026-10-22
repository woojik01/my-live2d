package dev.mylive2d.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mylive2d.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class AnimationSequenceExporterDeviceTest {
    private data class Fixture(val project: Project, val texture: Bitmap, val clipId: String)

    private fun fixture(duration: Int = 3): Fixture {
        val asset = Asset(width=8,height=8)
        val part = Part(name="Square",assetId=asset.id,mesh=Mesh.grid(8f,8f,1,1))
        val compressed = part.mesh.vertices.map { Vec2(it.x,it.y*.2f) }
        val parameter = Parameter(name="Squash",min=0f,default=0f,max=1f,
            bindings=listOf(Binding(part.id,listOf(Keyform(0f,part.mesh.vertices),Keyform(1f,compressed)))))
        var clip = AnimationClip(name="Squash test",durationFrames=duration,fps=30,loop=false)
        clip = AnimationEngine.putKey(clip,parameter.id,0,0f)
        clip = AnimationEngine.putKey(clip,parameter.id,duration-1,1f)
        val project = Project(name="Export test",assets=listOf(asset),parts=listOf(part),parameters=listOf(parameter),animations=listOf(clip))
        Validation.check(project)
        val texture = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        return Fixture(project,texture,clip.id)
    }

    @Test fun exportsTransparentPngFramesWithStableAnimationBoundsAndManifest() {
        val f=fixture()
        val bytes=ByteArrayOutputStream()
        try {
            AnimationSequenceExporter.write(f.project,f.clipId,mapOf(f.project.assets.single().id to f.texture),256,bytes)
            val entries=mutableMapOf<String,ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    entries[entry.name]=zip.readBytes()
                    zip.closeEntry()
                }
            }
            assertEquals(setOf("manifest.json","frames/frame_0000.png","frames/frame_0001.png","frames/frame_0002.png"),entries.keys)
            val manifest=JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
            assertEquals("MyLive2D PNG sequence",manifest.getString("format"))
            assertEquals(30,manifest.getInt("fps")); assertEquals(3,manifest.getInt("frames"))
            assertTrue(manifest.getBoolean("alpha"))
            val first=BitmapFactory.decodeByteArray(entries.getValue("frames/frame_0000.png"),0,entries.getValue("frames/frame_0000.png").size)
            val lastBytes=entries.getValue("frames/frame_0002.png")
            val last=BitmapFactory.decodeByteArray(lastBytes,0,lastBytes.size)
            try {
                assertEquals(256,first.width); assertEquals(256,first.height)
                assertEquals(Color.TRANSPARENT,first.getPixel(0,0))
                assertTrue(Color.alpha(first.getPixel(128,128))>0)
                assertTrue(alphaPixels(first)>alphaPixels(last))
            } finally { first.recycle(); last.recycle() }
        } finally { f.texture.recycle() }
    }

    @Test fun refusesExpensiveExportBeforeWritingZipBytes() {
        val f=fixture(duration=300)
        val bytes=ByteArrayOutputStream()
        try {
            assertFalse(AnimationSequenceExporter.canExport(300,1024))
            assertTrue(AnimationSequenceExporter.canExport(300,512))
            try {
                AnimationSequenceExporter.write(f.project,f.clipId,mapOf(f.project.assets.single().id to f.texture),1024,bytes)
                fail("Expected export budget to reject the request")
            } catch(_: IllegalArgumentException) { }
            assertEquals(0,bytes.size())
        } finally { f.texture.recycle() }
    }

    private fun alphaPixels(bitmap: Bitmap): Int {
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return pixels.count { Color.alpha(it)>0 }
    }
}
