package dev.mylive2d.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/** Strict, bounded PSD 1/8-bit RGB raster-layer importer. Unsupported visual features fail explicitly. */
object PsdReader {
    private const val MAX_BYTES=64*1024*1024
    data class Layer(val name: String,val left: Int,val top: Int,val width: Int,val height: Int,
        val opacity: Float,val visible: Boolean,val pixels: IntArray)
    data class Document(val width: Int,val height: Int,val layers: List<Layer>)
    private data class Channel(val id: Int,val length: Int)
    private data class Record(val name: String,val left: Int,val top: Int,val width: Int,val height: Int,
        val opacity: Float,val visible: Boolean,val channels: List<Channel>)
    private fun ByteBuffer.bytes(n: Int): ByteArray {
        require(n>=0 && n<=remaining()) { "Truncated PSD section" }
        return ByteArray(n).also { get(it) }
    }
    private fun ByteBuffer.u16()=short.toInt() and 0xffff
    private fun ByteBuffer.u8()=get().toInt() and 0xff
    private fun ByteBuffer.section(n: Int): ByteBuffer = ByteBuffer.wrap(bytes(n)).order(ByteOrder.BIG_ENDIAN)
    private fun ByteBuffer.text(n: Int)=String(bytes(n),Charsets.ISO_8859_1)
    private fun ByteBuffer.sized()=section(int)
    private fun input(input: InputStream): ByteArray {
        val output=ByteArrayOutputStream(); val buf=ByteArray(8192)
        while(true) { val n=input.read(buf); if(n<0) break; if(n==0) continue
            require(output.size()+n<=MAX_BYTES) { "PSD exceeds 64 MB" }; output.write(buf,0,n) }
        return output.toByteArray()
    }
    fun read(input: InputStream): Document {
        try { return parse(ByteBuffer.wrap(input(input)).order(ByteOrder.BIG_ENDIAN)) }
        catch(e: java.nio.BufferUnderflowException) { throw IllegalArgumentException("Truncated PSD",e) }
        catch(e: NegativeArraySizeException) { throw IllegalArgumentException("Invalid PSD length",e) }
    }
    private fun parse(file: ByteBuffer): Document {
        require(file.text(4)=="8BPS" && file.u16()==1) { "Only PSD (not PSB) is supported" }
        file.bytes(6); require(file.u16() in 3..16) { "Unsupported PSD channels" }
        val height=file.int; val width=file.int
        require(width in 1..2048 && height in 1..2048) { "PSD canvas exceeds 2048 pixels" }
        require(file.u16()==8 && file.u16()==3) { "Only 8-bit RGB PSD is supported" }
        file.sized(); file.sized()
        val layerSection=file.sized(); require(layerSection.remaining()>=4) { "PSD has no editable layers" }
        val info=layerSection.sized(); require(info.remaining()>=2) { "PSD has no editable layers" }
        if(layerSection.remaining()>=4) require(layerSection.sized().remaining()==0) { "Global layer masks are not supported" }
        require(!layerSection.hasRemaining()) { "Unsupported PSD global layer features" }
        val count=abs(info.short.toInt()); require(count in 1..32) { "PSD must contain 1–32 raster layers" }
        val records=(0 until count).map { index ->
            val top=info.int; val left=info.int; val bottom=info.int; val right=info.int
            val w=right.toLong()-left; val h=bottom.toLong()-top
            require(w in 1L..2048L && h in 1L..2048L && left in -100_000..100_000 && top in -100_000..100_000) { "Unsupported empty, group or oversized layer" }
            val n=info.u16(); require(n in 3..4) { "Unsupported PSD layer channels or mask" }
            val channels=(0 until n).map { Channel(info.short.toInt(),info.int) }
            require(channels.map { it.id }.distinct().size==n && channels.all { it.id in -1..2 && it.length>=2 } && (0..2).all { id -> channels.any { it.id==id } }) { "Unsupported PSD channels or mask" }
            require(info.text(4)=="8BIM" && info.text(4)=="norm") { "Only normal layer blending is supported" }
            val opacity=info.u8()/255f; val clipping=info.u8(); val flags=info.u8(); info.u8()
            require(clipping==0 && !((flags and 0x18)==0x18)) { "Clipping or adjustment layer is not supported" }
            val extra=info.sized(); val mask=extra.sized(); require(mask.remaining()==0) { "Layer masks are not supported" }
            val ranges=extra.sized()
            // Default blend ranges are 0,0,255,255 per range. Custom blend-if changes appearance.
            require(ranges.remaining()<=256) { "Unsupported layer blending ranges" }
            val rangeBytes=ranges.bytes(ranges.remaining())
            require(rangeBytes.size%4==0 && rangeBytes.toList().chunked(4).all { b -> b.map { it.toInt() and 255 }==listOf(0,0,255,255) }) { "Custom layer blending ranges are not supported" }
            val len=extra.u8(); var name=String(extra.bytes(len),Charsets.ISO_8859_1)
            val padding=(4-((len+1)%4))%4; extra.bytes(padding)
            while(extra.remaining()>=12) {
                require(extra.text(4)=="8BIM") { "Unsupported PSD tagged block" }
                val key=extra.text(4); val blockLength=extra.int; val block=extra.section(blockLength)
                if(blockLength%2==1) extra.bytes(1)
                when(key) {
                    "luni" -> { val chars=block.int; require(chars in 0..1024 && chars*2<=block.remaining()); name=String(block.bytes(chars*2),Charsets.UTF_16BE) }
                    "lsct","lsdk" -> error("PSD layer groups are not supported yet; export layers without groups")
                    "lyid","lclr","fxrp","lspf","shmd","tsly" -> Unit
                    else -> error("Unsupported PSD layer feature: $key")
                }
            }
            require(extra.remaining()==0 || extra.bytes(extra.remaining()).all { it.toInt()==0 }) { "Invalid PSD layer metadata" }
            Record(name.trim().take(120).ifBlank { "Layer ${index+1}" },left,top,w.toInt(),h.toInt(),opacity,(flags and 2)==0,channels)
        }
        require(records.sumOf { it.width.toLong()*it.height }<=16_777_216L) { "PSD layers exceed the image memory budget" }
        val layers=records.map { rec ->
            val size=rec.width*rec.height; val planes=mutableMapOf<Int,ByteArray>()
            rec.channels.forEach { c ->
                val data=info.section(c.length); val compression=data.u16()
                planes[c.id]=decode(data,compression,rec.width,rec.height)
            }
            val pixels=IntArray(size) { i ->
                val a=planes[-1]?.get(i)?.toInt()?.and(255) ?: 255
                (a shl 24) or ((planes.getValue(0)[i].toInt() and 255) shl 16) or
                    ((planes.getValue(1)[i].toInt() and 255) shl 8) or (planes.getValue(2)[i].toInt() and 255)
            }
            Layer(rec.name,rec.left,rec.top,rec.width,rec.height,rec.opacity,rec.visible,pixels)
        }
        return Document(width,height,layers.asReversed()) // PSD is stored front-to-back; editor stores back-to-front.
    }
    private fun decode(data: ByteBuffer, compression: Int, width: Int, height: Int): ByteArray {
        val size=width*height
        val result=when(compression) {
            0 -> data.bytes(size)
            1 -> {
                val lengths=IntArray(height) { data.u16() }; val out=ByteArray(size)
                for(y in 0 until height) {
                    val row=data.section(lengths[y]); var x=0
                    while(row.hasRemaining()) {
                        val control=row.get().toInt()
                        when {
                            control in 0..127 -> { val count=control+1; require(x+count<=width); val bytes=row.bytes(count); bytes.copyInto(out,y*width+x); x+=count }
                            control in -127..-1 -> { val count=1-control; require(x+count<=width); val value=row.get(); repeat(count) { out[y*width+x++]=value } }
                        }
                    }
                    require(x==width) { "Invalid PSD RLE row" }
                }
                out
            }
            2,3 -> {
                val compressed=data.bytes(data.remaining()); val out=ByteArray(size)
                InflaterInputStream(ByteArrayInputStream(compressed)).use { stream ->
                    var offset=0; while(offset<size) { val n=stream.read(out,offset,size-offset); require(n>0) { "Truncated PSD ZIP channel" }; offset+=n }
                    require(stream.read()==-1) { "PSD ZIP channel is too large" }
                }
                if(compression==3) for(y in 0 until height) for(x in 1 until width) {
                    val i=y*width+x; out[i]=(out[i].toInt()+out[i-1].toInt()).toByte()
                }
                out
            }
            else -> error("Unsupported PSD compression")
        }
        require(data.remaining()<=1 && (!data.hasRemaining() || data.u8()==0)) { "Unexpected PSD channel bytes" }
        return result
    }
}
