package dev.mylive2d.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import kotlin.test.*

class PsdReaderTest {
    private fun block(write: DataOutputStream.()->Unit): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use(write) }.toByteArray()
    private fun DataOutputStream.sized(b: ByteArray) { writeInt(b.size); write(b) }
    private fun fixture(compression: Int=0, blend: String="norm", mask: Boolean=false): ByteArray {
        val planes=listOf(byteArrayOf(255.toByte(),0,10,20),byteArrayOf(0,255.toByte(),30,40),byteArrayOf(0,0,255.toByte(),50),byteArrayOf(255.toByte(),0,128.toByte(),255.toByte()))
        val encoded=planes.map { plane -> block {
            writeShort(compression)
            when(compression) {
                0 -> write(plane)
                1 -> { writeShort(3); writeShort(3); writeByte(1); write(plane,0,2); writeByte(1); write(plane,2,2) }
                2 -> { val z=ByteArrayOutputStream(); DeflaterOutputStream(z).use { it.write(plane) }; write(z.toByteArray()) }
            }
        } }
        val extra=block {
            sized(if(mask) byteArrayOf(1,2) else byteArrayOf()); sized(byteArrayOf())
            writeByte(3); writeBytes("Eye")
        }
        val record=block {
            writeInt(2); writeInt(3); writeInt(4); writeInt(5); writeShort(4)
            listOf(0,1,2,-1).forEachIndexed { i,id -> writeShort(id); writeInt(encoded[i].size) }
            writeBytes("8BIM"); writeBytes(blend); writeByte(200); writeByte(0); writeByte(0); writeByte(0); sized(extra)
        }
        val layers=block { writeShort(1); write(record); encoded.forEach { write(it) } }
        val layerMask=block { sized(layers); writeInt(0) }
        return block { writeBytes("8BPS"); writeShort(1); write(ByteArray(6)); writeShort(4); writeInt(8); writeInt(8); writeShort(8); writeShort(3); sized(byteArrayOf()); sized(byteArrayOf()); sized(layerMask) }
    }
    @Test fun importsLayerAndTransparencyAcrossCompressionModes() {
        for(mode in 0..2) {
            val doc=PsdReader.read(ByteArrayInputStream(fixture(mode))); val layer=doc.layers.single()
            assertEquals(8,doc.width); assertEquals(8,doc.height); assertEquals("Eye",layer.name)
            assertEquals(3,layer.left); assertEquals(2,layer.top); assertEquals(2,layer.width); assertEquals(2,layer.height)
            assertEquals(200/255f,layer.opacity,.001f); assertTrue(layer.visible)
            assertEquals(0xffff0000.toInt(),layer.pixels[0]); assertEquals(0x0000ff00,layer.pixels[1]); assertEquals(0x800a1eff.toInt(),layer.pixels[2])
        }
    }
    @Test fun rejectsUnsupportedAndTruncatedFiles() {
        assertFails { PsdReader.read(ByteArrayInputStream(fixture(blend="mul "))) }
        assertFails { PsdReader.read(ByteArrayInputStream(fixture(mask=true))) }
        assertFails { PsdReader.read(ByteArrayInputStream(fixture().dropLast(10).toByteArray())) }
    }
}
